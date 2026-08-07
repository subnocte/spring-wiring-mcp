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

## Test-side wiring (Milestone 4: `testWiring` / `testDoubleUsage`)

Everything above resolves *production* wiring. `testWiring` and `testDoubleUsage` do the same for the test side — the boundary that decides which of a test's collaborators are the real thing and which are stand-ins:

> "What does `CheckoutServiceTest` mock, and what's the real subject under test?"

`testWiring` resolves one JUnit5 test class (fully-qualified name, or a unique simple name) to its declared wiring:
- `realSubjects` — types built as the real thing: `@InjectMocks` fields, or `new X(...)` assigned in a field initializer or a `@BeforeEach` method (so plain constructor-injection-style tests, with no Mockito extension at all, are still seen)
- `mocked` — test doubles: `@Mock` / `@Spy` / `@MockBean` / `@SpyBean` / `@MockitoBean` / `@MockitoSpyBean` fields, or `mock(X.class)` / `spy(...)` calls, each kind-tagged
- `staticMocks` — types frozen via `mockStatic(X.class)`, with the scope it's frozen in: a method name, `@BeforeEach`, or a field
- `slice` — the class's Spring test-slice annotations (`@SpringBootTest`, `@WebMvcTest(controllers = ...)`, etc.) and `@ExtendWith` contents
- `unresolved` — declarations it couldn't classify, with a reason, never guessed (an annotation whose import doesn't match the known type, a `mockStatic` argument that isn't a class literal)

`@Nested` classes are aggregated under the top-level class rather than reported separately: each declaration carries the nested scope it was found in, and a `@Mock` declared on the *outer* class is recorded once at the top level — it still covers every `@Nested` test, matching JUnit5's real behavior, instead of being invisible to them or duplicated per nested class.

`testDoubleUsage` is the reverse lookup:

> "Which tests mock `PaymentGateway`, and which ones treat it as real?"

returns three declaration-based buckets for a queried type — `realSubject`, `mocked`, `staticMocked` — plus `unclassifiedContextTests`: Spring context-slice tests (`@SpringBootTest`, `@WebMvcTest`, etc.) whose declarations never mention the type at all. Absence from that list is *not* proof of safety — whether a Spring context actually wires the type at runtime is out of scope for a static analysis, so these are self-reported as unclassified rather than assumed unaffected.

This is deliberately named "test-side wiring," not "Mockito support": a test double is the test-time replacement for Spring wiring, the same concept the production tools above resolve, just declared differently. And it is a *declaration* analysis, same discipline as everything else in this project — `testWiring`/`testDoubleUsage` never claim a Spring context actually starts, or that a test actually exercises the path it appears to.

### Out of scope for v1

- Local-variable `mock(X.class)` — only field-level test doubles are tracked.
- JUnit4 / TestNG — JUnit5 constructs only; other frameworks simply produce no wiring (not reported as "unsupported").
- `@Configuration` / `@TestConfiguration` bean-override *resolution* — detecting and classifying exactly which bean an override replaces is future work.
- Context reality — whether a Spring context actually starts, and which beans it actually wires at runtime, is never modeled; this is a static analysis of what the test source declares, nothing more.

## Analyzing other directories and refs (`root` / `ref`)

Every tool above (`resolveEndpoint`, `indexStatus`, `traceEndpoint`, `beanDependencies`, `beanDependents`, `transactionalBoundaries`, `testWiring`, `testDoubleUsage`) also accepts two optional parameters, so an agent isn't limited to the one codebase the server happened to start against:

- `root` — an absolute path to a directory to analyze instead of the server's startup `CODE_ROOT`. Its own `CodeIndexes` is built lazily on first use and cached for reuse.
- `ref` — a git branch, tag, or commit to analyze instead of the working tree, resolved in the repository at the *effective* root (`root`, or `CODE_ROOT` if omitted). Internally this resolves the ref to a commit SHA with `git rev-parse`, extracts that commit's sources with `git archive` into a local scratch directory, and analyzes that directory — the checked-out working tree is never touched. The ref name itself is **never** cached: it is re-resolved on every call, so a branch that moved (e.g. after a `git fetch`) is picked up on the next call without restarting the server. This project never fetches on your behalf — if a remote branch moved and you haven't fetched, `ref` resolves against what you last fetched.

`root` and `ref` are independent and freely composable — asking for `ref` alone analyzes that commit in `CODE_ROOT`'s repository; adding `root` analyzes that commit in a different repository instead. Whenever `ref` is supplied, the response carries `resolvedCommit` (the requested ref, the SHA it resolved to, and that commit's timestamp), so you can tell exactly which snapshot the answer came from.

> "What does `GET /orders/{id}` look like on `origin/develop`, without touching my working tree?"

is one `traceEndpoint` call with `ref: "origin/develop"` — no `git checkout`, no stashing uncommitted work.

Bad input is always self-reported rather than silently misinterpreted: an invalid `root` or an unresolvable `ref` comes back as `rootError` on the response (distinct from a domain miss like "no such endpoint" or "no such bean"), with the reason inline (e.g. what `git rev-parse` said). `indexStatus` additionally reports `knownRoots` — every directory currently cached, tagged `default` / `root` / `ref` — so you can see how much ad hoc usage has accumulated in the server's lifetime.

Both caches are bounded so a long-running server doesn't grow forever: at most `spring-wiring.max-roots` directories (default 8, including `CODE_ROOT`, which is never evicted) and `spring-wiring.max-ref-generations` materialized commits (default 5) are kept, LRU-evicting past that. Both are Spring properties, overridable the same way as `code.root` (`--spring-wiring.max-roots=16`, or the matching `SPRING_WIRING_MAX_ROOTS` environment variable). `testWiring`/`testDoubleUsage` share the production tools' `ref` cache (a materialized commit holds both production and test sources) and share the `spring-wiring.max-roots` cap on a separate, test-wiring-only cache — but that cache is built lazily, root by root, only once a test-wiring tool is actually called for it, rather than eagerly for `CODE_ROOT` at startup like the production index.

### Trust model

This server has no sandboxing around `root`: it is designed to run locally over stdio under your own OS user account, the same trust boundary as any other local MCP server or CLI tool you'd run yourself. `root` accepts any absolute path the process's user can read — there is no allowlist and no confinement to a subtree of `CODE_ROOT`. `ref` shells out to the `git` binary on `PATH` via `ProcessBuilder` (never through a shell, so ref names are never subject to shell interpretation) against the repository at the effective root; it does not fetch, push, or write to that repository. Materialized ref snapshots are written under `java.io.tmpdir`. If you would not run `git archive` or read arbitrary paths yourself in this environment, don't grant an agent unattended access to this server either.

## Installation

Requires Java 21. The server itself runs on Spring Boot 4.1 with Spring AI 2.0 (MCP Server starter), but that only concerns the server's own runtime — the codebase being analyzed is parsed as plain source with JavaParser, so Spring Boot 3.x (or any Spring MVC) projects are perfectly valid analysis targets.

Build the executable jar with the bundled Gradle wrapper:

```bash
./gradlew bootJar
```

This produces `build/libs/spring-wiring-mcp.jar`.

## Connecting to Claude Code / Claude Desktop

The server communicates over stdio, so it's launched as a subprocess by the MCP client rather than run as a standalone service. Point `CODE_ROOT` at the Spring Boot codebase you want indexed by default — a repository root is fine, monorepo included: the scanner prunes hidden directories (`.git`, `.gradle`, …), build output and dependency trees (`build/`, `target/`, `out/`, `bin/`, `node_modules/`), and test source sets (`src/test`, `src/integrationTest`, `src/testFixtures`) from the *production* index, so `resolveEndpoint`/`beanDependencies`/etc. only ever see production sources. Those excluded test source sets are exactly what `testWiring`/`testDoubleUsage` analyze instead, via a separate index built lazily on first use. `CODE_ROOT` is just the default: the `root` and `ref` tool parameters (see above) can point any individual call at a different directory or git ref without restarting the server.

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

`CODE_ROOT` can also be supplied as a command-line argument instead of an environment variable: `--code.root=/absolute/path/to/target-spring-boot-project` (Spring Boot's relaxed property binding maps both to the same `code.root` property). The default root's index is built at startup and held in memory, and stays consistent with the sources: every tool call checks a cheap per-file fingerprint (path, size, mtime) and rebuilds the index before answering if anything under `CODE_ROOT` was added, edited, or deleted — answers always reflect the code on disk at call time. The same freshness check applies to any directory reached via `root`; a directory materialized from `ref` never needs it, since a commit's contents are immutable once resolved.

## Roadmap

- **Method-level tracing**: `traceEndpoint` is bean-level; following actual call chains (which service method a handler invokes) needs call-graph analysis
- **Transactional attributes**: surface `propagation`/`readOnly` values, and `@Transactional` semantics on interface-declared methods
- **Provider-style injection**: `ObjectProvider<X>` / `Optional<X>` sites
- **`@TestConfiguration` override resolution**: `testWiring` detects and lists these, but doesn't yet resolve which bean an override replaces
- **Local-variable test doubles**: `testWiring`/`testDoubleUsage` are field-only in v1
- **Distribution**: publish via jbang; evaluate a GraalVM native image once the Spring AI MCP starter's native support is verified

## Demo

_(placeholder — a short GIF walking through `resolveEndpoint` against a real codebase will go here)_

## Benchmarks

_(placeholder — token-cost / accuracy comparison against grep-based and generic code-search MCP servers will go here)_

## License

MIT — see [LICENSE](LICENSE).
