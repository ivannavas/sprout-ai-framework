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
| `io.github.ivannavas.sprout.example.rag` | RAG end to end: an agent answers from a knowledge base indexed into the built-in vector store | `mvn -pl sprout-examples -am -Prag exec:exec` |
| `io.github.ivannavas.sprout.example.events` | Observe an agent run through the event bus — subscribe to the prefab lifecycle events | `mvn -pl sprout-examples -am -Pevents exec:exec` |
| `io.github.ivannavas.sprout.example.monitoring` | Track usage, tokens and cost across agent runs with `sprout-monitoring`, then print the report | `mvn -pl sprout-examples -am -Pmonitoring exec:exec` |
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

`OrchestrationExampleApplication` is a research desk that shows `sprout-orchestration`'s three patterns
**composing** around one hub — a **supervisor that delegates** each question to a `MathSpecialist` or a
`HistorySpecialist`. A `TriageAgent` front desk sits in front of it. It uses deterministic stub models,
so no API key is needed:

```bash
mvn -pl sprout-examples -am -Porchestration exec:exec
```

The composition is the point — delegation happens *inside* the supervisor's run, so it nests naturally
under the other two:

1. **Delegation** — the supervisor exposes the specialists as tools through an `AgentDelegation` (a
   `ToolProvider`, the same SPI MCP uses), routes a question to one, and composes the reply.
2. **Orchestration × delegation** — an `AgentOrchestrator` runs a whole batch through that delegating
   supervisor **concurrently** (one worker thread and session per item), then collects the answers by id.
3. **Hand-off × delegation** — an `AgentHandoff` lets the triage agent **transfer control** to the same
   supervisor (over a shared `TeamConversationStore`), which then delegates and produces the final answer.

Expected output:

```
== Delegation: supervisor -> specialist ==
  What is 6 times 7? -> 6 times 7 is 42.
== Orchestration x Delegation: a concurrent batch, each delegated ==
  What is 8 times 9? -> 8 times 9 is 72.
  Who painted the Mona Lisa? -> Leonardo da Vinci painted the Mona Lisa, around 1503-1506.
  What is 100 plus 23? -> 100 plus 23 is 123.
== Hand-off x Delegation: triage -> supervisor -> specialist ==
  Who painted the Mona Lisa?
    path:   triage -> supervisor (supervisor delegates internally)
    answer: Leonardo da Vinci painted the Mona Lisa, around 1503-1506.
  What is 12 plus 30?
    path:   triage -> supervisor (supervisor delegates internally)
    answer: 12 plus 30 is 42.
```

## rag

`RagExampleApplication` shows **retrieval-augmented generation** end to end, reusing the two RAG
building blocks shipped in `sprout-core`. At startup a `KnowledgeBaseService` indexes a few documents;
then `DocsAgent` answers questions by retrieving the relevant ones and feeding them to its model. It
uses a deterministic offline model, so no API key is needed:

```bash
mvn -pl sprout-examples -am -Prag exec:exec
```

How the pieces fit:

1. **One shared store.** `DocsAgent` declares `@Agent(vectorStore = InMemoryVectorStore.class,
   embeddingModel = HashingEmbeddingModel.class, retrievalTopK = 2)` — `sprout-core`'s built-in
   defaults. `KnowledgeBaseService` constructor-injects the *same* two singletons and wraps them in a
   `Retriever` to index documents. Because the store is a shared managed bean, what the service indexes
   is what the agent finds. This is the usual RAG split: ingestion is one concern, querying another.
2. **Retrieval per turn.** Before each turn the agent embeds the question, pulls the closest passages
   from the store and prepends them to the prompt; the original question is what gets persisted, so a
   reloaded conversation never carries stale context. `KnowledgeBaseModel` then answers from the
   top-ranked passage, proving the retrieved text reached the model.
3. **Scan path.** The `rag` profile sets `sprout.scan.base-packages` to this example's package **plus**
   `io.github.ivannavas.sprout.impl`, so `InMemoryVectorStore` and `HashingEmbeddingModel` are
   registered as managed singletons and can be shared. For real use, supply your own
   `@VectorStore`/`@Embedding` beans (a vector database, a provider-backed embedding model) and name
   those in `@Agent` instead.

The built-in `HashingEmbeddingModel` matches on shared words, not meaning, which is enough for this
offline demo; for production-quality retrieval swap in a semantic embedding model — e.g.
`OpenaiEmbeddingModel` from `sprout-openai` — by naming it in `@Agent(embeddingModel = ...)`. Expected output:

```
== RAG: agent answers from an indexed knowledge base ==
  What does Sprout use dependency injection for?
    -> Based on the knowledge base: Sprout is a dependency injection framework for building AI agents in Java, using annotations such as Agent, Model and Service.
  How does an agent retrieve relevant documents?
    -> Based on the knowledge base: An agent enables RAG by declaring a vector store and an embedding model, then it retrieves the most relevant documents and adds them to the prompt before every turn.
  What vector store and embedding model does Sprout ship by default?
    -> Based on the knowledge base: By default Sprout ships an in memory vector store that ranks documents by cosine similarity, and a hashing embedding model that turns text into vectors without any API key, so retrieval runs offline.
```

## events

`EventsExampleApplication` observes an agent run through Sprout's **event bus**, fully offline. The
container auto-registers the default `InMemoryEventBus`; the example obtains it with
`container.eventBus()`, subscribes one catch-all `Event.class` listener (a `switch` over the event
types), then runs `WeatherAgent` (backed by a deterministic stub model). As the loop runs it publishes
the prefab lifecycle events, and the listener prints them in publish order:

```bash
mvn -pl sprout-examples -am -Pevents exec:exec
```

Expected output:

```
== Events: observing an agent run through the bus ==
  AgentStartedEvent -> WeatherAgent started: "What's the weather in Madrid?"
  ModelRequestEvent
  ModelResponseEvent -> WeatherStubModel produced 8 output tokens
  ToolCalledEvent -> tool forecast returned "Sunny, 25°C in Madrid"
  ModelRequestEvent
  ModelResponseEvent -> WeatherStubModel produced 12 output tokens
  AgentCompletedEvent -> finished in 2 iteration(s)
Answer: Here is your forecast: "Sunny, 25°C in Madrid"
```

The `ModelRequestEvent`/`ModelResponseEvent` come from the model: the agent loop runs it through
`ModelExecutor.invoke(...)`, and calling `model.invoke(...)` yourself emits them with no agent involved.
The default bus delivers synchronously, in-process. Implement
`AbstractEventBus` over Redis pub/sub, Kafka or a broker and mark it `@EventBus` to fan the same events
across services, with no change to publishers or subscribers — and any component (or an agent subclass,
via its `publish(Event)` helper) can define and emit its own `Event` types on the same bus.

## monitoring

`MonitoringExampleApplication` shows **`sprout-monitoring`** working, fully offline. Monitoring activates
automatically once the module is on the classpath: with no `@UsageStore` of its own, the app gets the
shipped in-memory store and a collector subscribed to the event bus — nothing to scan or configure. The
app runs `WeatherAgent` for three cities, then reads `container.getSingleton("usageStore").snapshot()` and
prints the per-model, per-agent and per-tool breakdown with costs:

```bash
mvn -pl sprout-examples -am -Pmonitoring exec:exec
```

Costs come from a rate the app sets before bootstrapping (`sprout.monitoring.pricing.WeatherStubModel.*`,
priced per one million tokens); in a real app these live in `sprout.properties`, and an unpriced model
simply reports zero cost. Expected output:

```
== Monitoring: usage, tokens and cost across agent runs ==
Models:
  WeatherStubModel: 6 calls, 297 in + 60 out tokens, $0.001791
Agents:
  WeatherAgent: 3 runs (3 ok / 0 failed), 6 iterations, 357 tokens
Tools:
  forecast: 3 calls
Totals: 6 model calls, 357 tokens, $0.001791
```

Each run makes two model calls (the tool request and the final answer), so three runs total six calls.
To persist usage instead of holding it in memory, implement `AbstractUsageStore`, mark it `@UsageStore`
and let it be scanned in your own package; it replaces the in-memory default.

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
