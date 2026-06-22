# sprout-examples

Runnable demonstrations of what Sprout buys you: a plain-Java agent talking to a real model, an MCP
server and a client agent that consumes it, and a Spring Boot app where an agent injects a Spring
`@Service` for its tool and persists conversations to a database. Each example lives in its own
package so that Sprout's package-scoped component scanning keeps them isolated even though they share
one classpath:

| Package | Example | Run with |
|---|---|---|
| `io.github.ivannavas.sprout.example.basic` | Plain Java app backed by the Anthropic model, then orchestrating concurrent agent runs | `mvn -pl sprout-examples -am exec:exec` |
| `io.github.ivannavas.sprout.example.mcp` | An MCP server exposed from `@Tool` methods and an agent that connects to it as a client | `mvn -pl sprout-examples -am -Pmcp exec:exec` |
| `io.github.ivannavas.sprout.example.orchestration` | A tour of `sprout-orchestration`: concurrent runs, supervisor delegation and conversation hand-off, sharing one cast of agents | `mvn -pl sprout-examples -am -Porchestration exec:exec` |
| `io.github.ivannavas.sprout.example.spring` | End-to-end Spring Boot web app using `sprout-spring-boot-starter`, with a concurrent batch endpoint | `mvn -pl sprout-examples spring-boot:run` |

> **Why packages, not modules?** Sprout scans for components under the entry point's package (or
> `sprout.scan.base-packages`). Giving each example a distinct package means launching one never
> picks up another's `@Agent`/`@Model`/`@Service` beans. The `basic` example needs the Anthropic
> model package on its scan path, so it sets `sprout.scan.base-packages` via the `basic` Maven
> profile (default) rather than in the shared `sprout.properties`, which would otherwise leak into
> the other examples.

## basic

`ExampleApplication` boots the container and calls `GreetingService`, which delegates to the
`@Qualifier("anthropic")` model. It then shows **orchestration**: it wraps `AssistantAgent` in an
`AgentOrchestrator` and fans three questions out **concurrently** with `sprout-orchestration`, so they
hit the API on separate worker threads instead of one after another, and reads each answer back by its
id. It needs an API key:

```bash
export ANTHROPIC_API_KEY=sk-...
mvn -pl sprout-examples -am exec:exec
```

## mcp

`McpAgentApplication` runs `MathAgent`, an agent with no `@Tool` methods of its own — all its tools
come from the MCP server it connects to. The `@UseMcp` command launches `McpServerApplication`
(package `...example.mcp.server`, which publishes `MathTools`) in a fresh JVM reusing this
process's classpath, then speaks MCP over its stdio. It uses a deterministic stub model, so no API
key is needed:

```bash
mvn -pl sprout-examples -am -Pmcp exec:exec
```

## orchestration

`OrchestrationExampleApplication` is one runnable tour of [`sprout-orchestration`](../sprout-orchestration)'s
three multi-agent patterns, sharing a single cast of agents — a `MathSpecialist` and a
`HistorySpecialist`, plus a `SupervisorAgent` and a `TriageAgent`. It uses deterministic stub models, so
no API key is needed:

```bash
mvn -pl sprout-examples -am -Porchestration exec:exec
```

1. **Concurrent orchestration** — an `AgentOrchestrator` fans three questions out to the history
   specialist at once (each `execute(prompt, id, session)` on its own worker thread and session), then
   `waitForExecutions()` blocks and the answers are read back by id with `getResult(id)`.
2. **Delegation** — the supervisor exposes the specialists as tools through an `AgentDelegation` (a
   `ToolProvider`, the same SPI MCP uses); it routes each question to one, which runs as an isolated
   sub-task, and composes the reply. Control stays with the supervisor.
3. **Hand-off** — an `AgentHandoff` wires a `handoff_to_<member>` tool onto every team member; the
   triage agent transfers the conversation to a specialist, which **takes over** the shared transcript
   (the team shares a `TeamConversationStore`) and produces the final answer.

Expected output:

```
== Concurrent orchestration ==
  Tell me about the Mona Lisa -> Leonardo da Vinci painted the Mona Lisa, around 1503-1506.
  Tell me about the Moon -> Apollo 11 first landed humans on the Moon in 1969.
  Tell me about Rome -> Rome was, according to legend, founded in 753 BC.
== Delegation ==
  What is 6 times 7? -> 6 times 7 is 42.
  Who painted the Mona Lisa? -> Leonardo da Vinci painted the Mona Lisa, around 1503-1506.
== Hand-off ==
    path:   triage -> math
    answer: 8 times 9 is 72.
    path:   triage -> history
    answer: Leonardo da Vinci painted the Mona Lisa, around 1503-1506.
```

## spring

An end-to-end example that runs Sprout inside a Spring Boot web app via
[`sprout-spring-boot-starter`](../sprout-spring-boot-starter). It exercises **both** integration
directions:

| Component | Managed by | Demonstrates |
|---|---|---|
| `WeatherController` (`@RestController`) | Spring | injects `weatherAgentExecutor`, a Sprout-built bean → **Sprout → Spring** |
| `WeatherAgent` (`@Agent`) | Sprout | constructor-injects `WeatherService` (a Spring `@Service`) → **Spring → Sprout** |
| `WeatherService` (`@Service`) | Spring | plain Spring bean used as an agent tool backend |
| `JpaConversationStore` (`@ConversationStore`) | Sprout | a Sprout store that constructor-injects a Spring Data JPA repository → **Spring → Sprout** |
| `StubChatModel` (`@Model`) | Sprout | offline, deterministic model so it runs without API keys |

The agent's conversation history is persisted to an in-memory **H2** database via Spring Data JPA:
`WeatherAgent` declares `@Agent(conversationStore = JpaConversationStore.class)`, the store
constructor-injects the Spring Data `ConversationMessageRepository` (a Spring bean flowing into a
Sprout component), and each `Message` is stored as a JSON row so tool calls and results round-trip.

```bash
mvn -pl sprout-examples -am spring-boot:run
# then:
curl "http://localhost:8080/weather?city=Madrid"
# orchestration: one request fans out to a concurrent agent run per city
curl "http://localhost:8080/weather/batch?cities=Madrid,Paris,London"
```

`/weather/batch` wraps the same `weatherAgentExecutor` in an `AgentOrchestrator` and runs one agent
per city **concurrently** (each on its own session), then returns every forecast as a JSON map once the
batch finishes — `sprout-orchestration` working inside a Spring controller.

`application.yml` pins `sprout.scan.base-packages` to `io.github.ivannavas.sprout.example.spring`
so the Spring example only scans its own components, and configures the in-memory H2 datasource.

### Test

```bash
mvn -pl sprout-examples -am test
```

`WeatherEndToEndTest` boots the full Spring context, asserts the controller returns the forecast
produced by the Spring-backed tool, and that the conversation is persisted to H2 and reloaded across
turns.
