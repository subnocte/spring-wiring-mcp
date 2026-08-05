# spring-wiring-mcp

An MCP server that statically resolves Spring Boot's implicit wiring, so an AI coding agent can reach the correct answer using few tokens and without guessing.

## Why not a generic code-analysis MCP server?

Generic code-search / code-analysis MCP servers can grep for `@GetMapping` and hand back a list of matches, but they don't understand Spring's own resolution semantics: which candidate actually wins once `@Primary`, `@Qualifier`, `@Profile`, conditional beans, and AOP proxies are taken into account. That's the gap this project targets — Spring-aware wiring resolution, not generic text search.

## What it does today (Milestone 1: REST endpoint resolution)

Point it at a Spring Boot codebase and ask, in one tool call:

> "Which method handles `GET /users/42`?"

and get back the fully-qualified handler (`com.example.UserController#getUser`), the source file, and the line number — resolved by parsing the AST with [JavaParser](https://javaparser.org/), not by running the application or grepping for annotation strings.

It understands:
- `@RestController` and `@Controller`
- Class-level `@RequestMapping` combined with method-level `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` / `@RequestMapping(method = ...)`
- Path variables (`/users/{id}`) when matching a concrete request path
- Multiple HTTP methods on one mapping (`@RequestMapping(method = {GET, POST})`), including statically imported `RequestMethod` constants (`method = GET`)
- `@RequestMapping` with no `method` attribute, which Spring treats as matching any HTTP method
- Paths referenced through `static final String` constants — same class, other scanned classes via qualified access, or static imports
- Mappings declared on an implemented interface when its source is under the scanned root (common with interface-driven controllers), following Spring's precedence rules: implementation annotations win, class-level prefixes are not concatenated

When there's no exact match, it returns the closest candidates instead of an empty result, so the agent isn't left guessing why a route "isn't there."

The tool declares MCP tool annotations (`readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: true`, `openWorldHint: false`), so clients know up front that it's a safe, retryable, local-only lookup that never mutates anything.

## Bean dependency graph (Milestone 2: `beanDependencies` / `beanDependents`)

Ask, in one tool call:

> "What beans does `AdGroupService` depend on, and where is each one defined?"

and get back every dependency resolved to the bean that wins at injection time, with file and line. Dependencies are modeled as the bean's instance fields — one rule that covers field `@Autowired`, Lombok `@AllArgsConstructor`/`@RequiredArgsConstructor`, and hand-written constructor injection without expanding Lombok. (The one blind spot, by design: a constructor parameter never stored in a field. That produces a missing edge, never a wrong one.)

Resolution follows Spring's semantics:
- a concrete bean class resolves to itself; an interface resolves to its single scanned implementation, the `@Primary` one, or the one a field `@Qualifier` names (qualifier beats primary)
- collection sites (`List<X>` / `Set<X>` / `Collection<X>` / `Map<String, X>`) resolve to every bound element implementation, the way Spring injects them
- MyBatis `@Mapper` interfaces and Spring Data repositories are *terminal beans* — including repositories built on a project-local base interface, followed transitively through the extends chain
- `@Bean` factory-method return types join the bean universe, with the factory method's parameters as their dependency edges; annotations meta-annotated with a stereotype (custom stereotypes, transitively) make their classes beans; setter injection is covered by the field model (the setter stores into the field the index already sees)
- sites that cannot be decided statically are reported with a reason and the candidate list, never guessed: multiple candidates without a disambiguator, candidates behind `@Profile`/`@ConditionalOn...` (the winner is environment-dependent), interfaces with no scanned implementation, and maps with non-String keys

`beanDependents` is the reverse direction — impact analysis before changing a bean or its contract:

> "Who depends on `TAdGroupRepository`?"

returns every bean whose dependency sites reference the queried class or interface, each tagged with how: resolved to it (`target`), declared as the field's type (`declared-type`), or listed among an unresolved site's candidates (`candidate` — a *possible* dependent, surfaced rather than hidden).

## Tracing and transactions (Milestone 3: `traceEndpoint` / `transactionalBoundaries`)

`traceEndpoint` connects the two graphs:

> "From `GET /ad/group/list`, what does the request touch, down to the database?"

resolves the handler, then walks resolved bean edges breadth-first from its controller to the persistence boundary — every hop with field and depth, terminal repositories/mappers collected, and unresolved sites listed as *blocked* so an incomplete trace is visible as such. Bean-level by design: it reports which beans are reachable from the handler's controller, not which methods call which. On hub-heavy codebases a full trace can run to 150+ hops, so the tool takes optional shaping parameters: `maxDepth` bounds the walk (a cut-off trace carries `truncated: true` — it never masquerades as complete), and `terminalsOnly` omits the hop list, returning just the persistence boundary and blocked sites.

`transactionalBoundaries` answers the question `@Transactional` annotations alone cannot:

> "Which of this class's methods actually run in a transaction?"

Per method: the effective status on the proxy path (own annotation, class-level annotation — which covers non-private methods only — or none). Plus the two classic silent failures: same-class calls to `@Transactional` methods, where the proxy is bypassed and the callee's annotation does not apply, and `@Transactional` on private methods, which the proxy can never intercept.

### Never silently wrong

Anything the static analysis cannot resolve is reported, never guessed and never silently dropped:

- endpoint mappings it can't resolve — wildcard patterns (`*` / `**`), string-concatenation paths, mappings on interfaces generated outside the scanned sources (e.g. by openapi-generator) — are collected as *unresolved mappings* (file, line, reason)
- bean injection sites it can't decide are reported per-edge with a reason and the candidates
- files the parser cannot process are reported as *parse failures* instead of silently vanishing from the index (sources are parsed at the Java 21 language level)
- the `indexStatus` tool returns all of this coverage up front: endpoint count, bean count, scanned file count, unresolved mappings, unresolved injections by reason, and parse failures — call it first to know how much to trust the index for a given project
- when `resolveEndpoint` misses, the response includes the unresolved mappings alongside the close-match suggestions, since the endpoint you're looking for may be among them

## Installation

Requires Java 21. The server itself runs on Spring Boot 4.1 with Spring AI 2.0 (MCP Server starter), but that only concerns the server's own runtime — the codebase being analyzed is parsed as plain source with JavaParser, so Spring Boot 3.x (or any Spring MVC) projects are perfectly valid analysis targets.

Build the executable jar with the bundled Gradle wrapper:

```bash
./gradlew bootJar
```

This produces `build/libs/spring-wiring-mcp.jar`.

## Connecting to Claude Code / Claude Desktop

The server communicates over stdio, so it's launched as a subprocess by the MCP client rather than run as a standalone service. Point `CODE_ROOT` at the Spring Boot codebase you want indexed — a repository root is fine, monorepo included: the scanner prunes hidden directories (`.git`, `.gradle`, …), build output and dependency trees (`build/`, `target/`, `out/`, `bin/`, `node_modules/`), and test source sets (`src/test`, `src/integrationTest`, `src/testFixtures`), so only production sources are indexed.

### Claude Code (`.mcp.json` or `claude mcp add`)

```json
{
  "mcpServers": {
    "spring-wiring": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/spring-wiring-mcp.jar"],
      "env": {
        "CODE_ROOT": "/absolute/path/to/target-spring-boot-project"
      }
    }
  }
}
```

### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "spring-wiring": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/spring-wiring-mcp.jar"],
      "env": {
        "CODE_ROOT": "/absolute/path/to/target-spring-boot-project"
      }
    }
  }
}
```

`CODE_ROOT` can also be supplied as a command-line argument instead of an environment variable: `--code.root=/absolute/path/to/target-spring-boot-project` (Spring Boot's relaxed property binding maps both to the same `code.root` property). The index is built at startup and held in memory, and stays consistent with the sources: every tool call checks a cheap per-file fingerprint (path, size, mtime) and rebuilds the index before answering if anything under `CODE_ROOT` was added, edited, or deleted — answers always reflect the code on disk at call time.

## Roadmap

- **Method-level tracing**: `traceEndpoint` is bean-level; following actual call chains (which service method a handler invokes) needs call-graph analysis
- **Transactional attributes**: surface `propagation`/`readOnly` values, and `@Transactional` semantics on interface-declared methods
- **Provider-style injection**: `ObjectProvider<X>` / `Optional<X>` sites
- **Distribution**: publish via jbang; evaluate a GraalVM native image once the Spring AI MCP starter's native support is verified

## Demo

_(placeholder — a short GIF walking through `resolveEndpoint` against a real codebase will go here)_

## Benchmarks

_(placeholder — token-cost / accuracy comparison against grep-based and generic code-search MCP servers will go here)_

## License

MIT — see [LICENSE](LICENSE).
