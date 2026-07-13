# spring-wiring-mcp

An MCP server that statically resolves Spring Boot's implicit wiring, so an AI coding agent can reach the correct answer using few tokens and without guessing.

## Why not a generic code-analysis MCP server?

Generic code-search / code-analysis MCP servers can grep for `@GetMapping` and hand back a list of matches, but they don't understand Spring's own resolution semantics: which candidate actually wins once `@Primary`, `@Qualifier`, `@Profile`, conditional beans, and AOP proxies are taken into account. That's the gap this project targets — Spring-aware wiring resolution, not generic text search.

The first milestone, described below, only covers REST endpoint resolution. The bean-graph features that motivate the "generic tools don't understand this" pitch are on the [roadmap](#roadmap).

## What it does today (Milestone 1: REST endpoint resolution)

Point it at a Spring Boot codebase and ask, in one tool call:

> "Which method handles `GET /users/42`?"

and get back the fully-qualified handler (`com.example.UserController#getUser`), the source file, and the line number — resolved by parsing the AST with [JavaParser](https://javaparser.org/), not by running the application or grepping for annotation strings.

It understands:
- `@RestController` and `@Controller`
- Class-level `@RequestMapping` combined with method-level `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` / `@RequestMapping(method = ...)`
- Path variables (`/users/{id}`) when matching a concrete request path
- Multiple HTTP methods on one mapping (`@RequestMapping(method = {GET, POST})`)
- `@RequestMapping` with no `method` attribute, which Spring treats as matching any HTTP method

When there's no exact match, it returns the closest candidates instead of an empty result, so the agent isn't left guessing why a route "isn't there."

## Installation

Requires Java 21. The server itself runs on Spring Boot 4.1 with Spring AI 2.0 (MCP Server starter), but that only concerns the server's own runtime — the codebase being analyzed is parsed as plain source with JavaParser, so Spring Boot 3.x (or any Spring MVC) projects are perfectly valid analysis targets.

Build the executable jar with the bundled Gradle wrapper:

```bash
./gradlew bootJar
```

This produces `build/libs/spring-wiring-mcp.jar`.

## Connecting to Claude Code / Claude Desktop

The server communicates over stdio, so it's launched as a subprocess by the MCP client rather than run as a standalone service. Point `CODE_ROOT` at the Spring Boot codebase you want indexed.

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

`CODE_ROOT` can also be supplied as a command-line argument instead of an environment variable: `--code.root=/absolute/path/to/target-spring-boot-project` (Spring Boot's relaxed property binding maps both to the same `code.root` property). The index is built once at startup and held in memory for the lifetime of the process.

## Roadmap

- **Bean dependency graph**: resolve `@Autowired`/`@Qualifier`/`@Primary` to the concrete bean that wins at injection time, including conditional (`@Profile`, `@ConditionalOn...`) beans
- **Endpoint → Repository tracing**: follow a handler method down through service and repository layers to the persistence boundary
- **`@Transactional` boundary visualization**: show where transactional boundaries actually start and end once self-invocation and AOP proxying are accounted for

## Demo

_(placeholder — a short GIF walking through `resolveEndpoint` against a real codebase will go here)_

## Benchmarks

_(placeholder — token-cost / accuracy comparison against grep-based and generic code-search MCP servers will go here)_

## License

MIT — see [LICENSE](LICENSE).
