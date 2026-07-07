# Sprout AI Framework

Sprout is a **foundation for building AI tooling in Java**: a simple, modular, Spring-style and Spring-compatible container
with a provider-agnostic model layer, automatic tool/JSON-schema plumbing and Model Context Protocol
support, all behind one open extension SPI. A cohesive, extensible base like this is still uncommon in
the Java ecosystem, where most options are thin API clients — Sprout is meant to be the **cornerstone
you keep building on**.

Its flagship capability today is **agents**: an agent is just an annotated class — declare a model,
write `@Tool` methods, and Sprout runs the model/tool loop for you. But the same base does more than
agents: you can publish `@Tool` methods as an MCP server with no agent at all, swap model providers
without touching your code, or add a new AI capability as a module — every capability (models, agents,
MCP, even the bean-construction pipeline itself) is built on the same public processor SPI, so you or
a third-party module can extend it without patching the framework.

**Why it's useful today:** if your backend already runs on the JVM, you can add these capabilities
without standing up a separate Python service or adopting a new programming model. Components drop
into the dependency injection, configuration and tests you already use, and tools are ordinary methods
backed by the services you already have. Sprout is also **fully Spring-compatible**: drop in the
Spring Boot starter and Sprout and Spring beans inject into each other transparently, in both
directions.

## Modules

| Module | What it provides |
|---|---|
| `sprout-core` | IoC container, component scanning, dependency injection, configuration, an event bus, and the agent/model/tool/RAG abstractions — including a built-in in-memory vector store and embedding model. |
| `sprout-anthropic` | `ModelExecutor` for Anthropic's Messages API (`@Model("anthropic")`), plus a Voyage AI `EmbeddingModel` (`@Embedding`) for RAG — the embedding provider Anthropic recommends. |
| `sprout-openai` | `ModelExecutor` for OpenAI's Chat Completions API (`@Model("openai")`), plus an `EmbeddingModel` (`@Embedding`) for OpenAI's embeddings API. |
| `sprout-mcp` | Model Context Protocol support: expose `@Tool` methods as an MCP server, and consume remote MCP servers from an agent. |
| `sprout-orchestration` | Run agent prompts concurrently, let a supervisor delegate subtasks to specialist agents, and hand a conversation off between agents. |
| `sprout-monitoring` | Tracks agent/model usage, tokens and cost off the event bus, into a swappable `@UsageStore` component (in-memory default). Scan its store package, or declare your own `@UsageStore`. |
| `sprout-spring-boot-starter` | Runs Sprout inside Spring Boot, bridging beans and configuration both ways. See its [README](sprout-spring-boot-starter/README.md). |
| `sprout-examples` | Runnable examples (basic, MCP, orchestration, RAG, events, monitoring, Spring). See its [README](sprout-examples/README.md). |

## Requirements

- Java 21
- Maven 3.9+

## Core concepts

### IoC container

Annotate classes and let the container discover, instantiate and wire them:

- `@Component` (and the `@Service` stereotype) mark managed singletons.
- `@Autowired` injects dependencies by type into fields or through a constructor; `@Qualifier`
  selects a specific bean by name.
- `@PostConstruct` runs initialisation once dependencies are injected.
- `@Value` and `@Configuration`/`@ConfigurationProperty` bind configuration, with Spring-style
  `${key:default}` placeholders resolved against `sprout.properties`, system properties and
  environment variables.

The richer stereotypes — `@Service`, `@Model`, `@Agent`, `@ConversationStore`, `@Mcp` — are all
meta-annotated with `@Component`. So **a model and an agent are ordinary managed singletons too**:
the container builds and wires them, and you inject them anywhere with `@Autowired` (by type, or by
name with `@Qualifier`) exactly like any other bean. They are not a separate kind of object.

Scanning starts from the entry point's package, or from the packages listed in
`sprout.scan.base-packages`.

### Models

A model is a `ModelExecutor` subclass annotated with `@Model`. `sprout-anthropic` and `sprout-openai`
ship implementations; you can add your own (including offline stubs for tests) by extending
`ModelExecutor` and implementing `chat(ModelRequest)`. Call `invoke(ModelRequest)` to run a model as an
observable execution (it wraps `chat` with the model lifecycle [events](#events)); the agent loop uses
it, so an agent's model calls are observed automatically. `chat`, `invoke` and `chatStream` each take a
leading `String modelName` overload to target a specific model per call, overriding the one configured
externally (e.g. `anthropic.model.name`) — the built-in Anthropic and OpenAI executors honour it.

Being a component, the model is a managed singleton you inject like any other bean — by type
(`ModelExecutor`) or by name with `@Qualifier`. The bean name is the class name in camelCase plus the
`@Model("name")` value if one is given, so the Anthropic model is injectable as
`@Autowired @Qualifier("anthropic") ModelExecutor`.

### Agents and tools

`@Agent` turns a class into an agent backed by a `@Model`. The class extends `AgentExecutor` — so the
agent *is* its own executor, mirroring how a `@Model` extends `ModelExecutor`. Its `@Tool` methods
become callable by the model, with the parameter JSON-Schema generated automatically from the method
signature (compile with `-parameters`) — primitives, enums and collections are mapped, and every
parameter is required by default. Add `@ToolParam` to a parameter to describe it or mark it optional;
a good description noticeably improves how reliably the model fills the argument in. The container
registers the agent under `<agentName>Executor`:

```java
@Agent(model = AnthropicModelExecutor.class, systemPrompt = "You are a helpful assistant.")
public class WeatherAgent extends AgentExecutor {

    @Tool(description = "Look up the weather forecast for a city")
    public String lookup(String city) {
        return "Sunny, 25°C in " + city;
    }
}
```

```java
SproutContainer container = SproutApplication.run(MyApp.class);
AgentExecutor agent = container.getSingleton("weatherAgentExecutor");
System.out.println(agent.execute("session-1", "What's the weather in Madrid?").response());
```

The executor loops: it sends the conversation to the model, dispatches any tool calls, feeds the
results back, and repeats until the model produces a final answer or `maxIterations` is reached.
Conversation history is persisted through an `AbstractConversationStore` — a thread-safe in-memory
one by default (an agent is a shared singleton, so its store is too); swap in your own to persist it.
The system prompt is applied at the head of each run rather than stored, so it always reflects the
agent's current configuration — and an agent that picks up a conversation another started (a hand-off)
governs its turns with its own prompt.

To stream a run instead of blocking, call `executeStream(conversationId, prompt, listener)`:
assistant tokens, tool calls and the final response are delivered through a `StreamListener` as they
happen. Token granularity depends on the model — a provider that overrides `chatStream` emits
incremental chunks, otherwise each turn's text arrives in one piece.

Since the agent is itself a managed bean, you don't have to look it up — you can `@Autowired` it by
type (`AgentExecutor`) or by its `<agentName>Executor` name, which is exactly what the Spring
example's controller does.

### Orchestrating concurrent runs

With `sprout-orchestration`, an `AgentOrchestrator` wraps an `AgentExecutor` to run several prompts at
once. Each `execute` is scheduled on a worker thread and returns immediately, so the calls fan out
concurrently; results (and failures) land on an internal replay stream you can read by id, as a
reactive stream, or by blocking until a batch finishes. A failing run is isolated as a failed
`Execution` and never tears down the others, and the orchestrator is `AutoCloseable`:

```java
AgentExecutor agent = container.getSingleton("researchAgentExecutor");

try (AgentOrchestrator orchestrator = AgentOrchestrator.of(agent)) {
    orchestrator.execute("Tell me about Mars", "mars", "mars-session")
                .execute("Tell me about Venus", "venus", "venus-session")
                .waitForExecutions();

    System.out.println(orchestrator.getResult("mars").block().response());
}
```

Each `execute` takes the prompt, an execution id to read the result back by, and an optional per-run
session so independent runs keep separate conversations.

Optional knobs — `withMaxConcurrency`, `withTimeout` and `withRetries` — bound how the runs execute.
This is concurrent execution of independent runs (cross-agent *delegation* is the
[next section](#agent-delegation)). It shows up across the
[examples](sprout-examples/README.md): the basic app fans concurrent questions out to a live model, the
Spring app exposes a `/weather/batch` endpoint that runs a forecast per city at once, and there is a
dedicated offline orchestration example.

### Agent delegation

The same module lets one agent delegate to others. An `AgentDelegation` exposes a set of **specialist**
agents to a **supervisor** as tools — one per specialist — through the very same `ToolProvider` SPI the
agent already uses for its `@Tool` methods and for MCP servers, so the executor needs no special-casing.
When the supervisor's model decides a subtask belongs to a specialist, it "calls" it like any other
tool; the specialist runs and its answer flows back as the tool result, so the supervisor composes a
final reply from its team's work:

```java
AgentExecutor supervisor = container.getSingleton("supervisorAgentExecutor");

AgentDelegation.builder()
        .specialist("math", "Solves arithmetic and number problems.", mathAgent)
        .specialist("history", "Answers history and general-knowledge questions.", historyAgent)
        .attachTo(supervisor);

// The supervisor's model routes each question to the right specialist, which actually runs.
System.out.println(supervisor.execute("session", "What is 6 times 7?").response());
```

Each delegation runs as an independent sub-conversation, so specialists stay stateless across calls —
including from supervisor runs an `AgentOrchestrator` drives concurrently. See it in the runnable
[orchestration example](sprout-examples/README.md).

### Agent hand-off

Hand-off goes a step further than delegation: instead of calling a specialist as an isolated sub-task
and composing the reply itself, an agent **transfers control**. An `AgentHandoff` gives every member of
a team a `handoff_to_<member>` tool; when the active agent calls one, the conversation passes to that
agent, which continues the *same* shared transcript and produces the final answer (and may hand off
again). The loop ends when an agent finishes without handing off:

```java
AgentHandoff team = AgentHandoff.builder()
        .member("triage", "First point of contact; routes the user.", triageAgent)
        .member("billing", "Handles invoices, payments and refunds.", billingAgent)
        .member("tech", "Handles login, passwords and technical errors.", techAgent)
        .build();

AgentHandoff.HandoffResult result = team.run("I have a question about my invoice.");
System.out.println(result.path());     // [triage, billing]
System.out.println(result.response()); // the billing agent's answer
```

The team members share one conversation store (point them at a common `@ConversationStore` bean) so the
receiving agent sees the whole history, while still applying its *own* system prompt to its turns;
`maxHandoffs` bounds the transfers. See it in the runnable
[orchestration example](sprout-examples/README.md).

### Composing the patterns

The three compose. Because delegation happens *inside* an agent's own run, a delegating **supervisor** is
just an `AgentExecutor` — so you can orchestrate it concurrently **and** make it the target of a hand-off,
all around one hub:

```java
AgentDelegation.builder()
        .specialist("math", "Arithmetic.", mathAgent)
        .specialist("history", "History questions.", historyAgent)
        .attachTo(supervisor);

// Orchestration × delegation — run a batch through the delegating supervisor, concurrently.
try (AgentOrchestrator orchestrator = AgentOrchestrator.of(supervisor)) {
    questions.forEach(q -> orchestrator.execute(q, q, "batch-" + q));
    orchestrator.waitForExecutions();
}

// Hand-off × delegation — a triage front desk transfers control to that same supervisor.
AgentHandoff desk = AgentHandoff.builder()
        .member("triage", "Front desk; escalates to the supervisor.", triage)
        .member("supervisor", "Researches by delegating to specialists.", supervisor)
        .build();
desk.run("Who painted the Mona Lisa?");   // triage -> supervisor (which delegates) -> answer
```

The [orchestration example](sprout-examples/README.md) runs exactly this. And since these are plain
objects over `AgentExecutor` beans, the same composition drops straight into a Spring `@RestController` —
the same way the [`/weather/batch`](#examples) endpoint fans agent runs out concurrently inside Spring.

### Retrieval-augmented generation (RAG)

An agent can ground its answers in your own documents. Declare a **vector store** and an **embedding
model** on `@Agent`, and before each turn Sprout embeds the user's prompt, retrieves the `retrievalTopK`
most relevant documents and prepends them to the prompt as context. The original question is what gets
persisted (retrieval is redone per turn, like the system prompt), so a reloaded conversation never
carries stale context forward:

```java
@Agent(
        model = AnthropicModelExecutor.class,
        vectorStore = InMemoryVectorStore.class,
        embeddingModel = VoyageEmbeddingModel.class,
        retrievalTopK = 4)
public class DocsAgent extends AgentExecutor {
}
```

The building blocks live in `sprout-core` and follow the same pattern as the rest of the framework:
an `AbstractVectorStore` (marked `@VectorStore`) stores `Document`s by their vector and searches them,
an `EmbeddingModel` (marked `@Embedding`) turns text into vectors, and a `Retriever` ties the two
together for both **indexing** and **querying**. You populate the store ahead of queries — point the
agent at a managed `@VectorStore` bean so the same instance is shared with your indexing code:

```java
// At startup, index your documents into the same store the agent retrieves from.
Retriever retriever = new Retriever(embeddingModel, vectorStore, 4);
retriever.index(List.of(
        Document.of("intro", "Sprout is a dependency-injection framework for AI agents in Java."),
        Document.of("rag",   "An agent enables RAG by declaring a vector store and an embedding model.")));
```

Core ships usable defaults so RAG runs offline with no API key: `InMemoryVectorStore` (cosine
similarity) and `HashingEmbeddingModel` (a lexical embedding). For semantic retrieval in production,
swap in a provider-backed embedding model — `OpenaiEmbeddingModel` (`sprout-openai`) or
`VoyageEmbeddingModel` (`sprout-anthropic`, backed by Voyage AI, which Anthropic recommends for
embeddings) — by naming it in `@Agent(embeddingModel = ...)`. RAG stays opt-in: an agent that declares
no vector store does no retrieval. See the runnable [RAG example](sprout-examples/README.md).

### Events

Sprout has a lightweight **event bus** for observing what agents and models do, without coupling the
observer to the agent. Inject the `AbstractEventBus` (or reach it via `container.eventBus()`) and
subscribe to the events you care about; the agent loop publishes a prefab set as it runs:

- `AgentStartedEvent` / `AgentCompletedEvent` / `AgentFailedEvent` — the run's boundaries (the last
  carries the `AgentResult`, or the error).
- `ModelRequestEvent` / `ModelResponseEvent` — published by the model around a call made through
  `ModelExecutor.invoke(...)`. The agent loop uses `invoke`, so they fire during agent runs and from a
  **standalone `model.invoke(...)`** alike (a raw `chat(...)` call stays event-free).
- `ToolCalledEvent` — each tool the model invoked, paired with its result.

```java
SproutContainer container = SproutApplication.run(MyApp.class);
AbstractEventBus events = container.eventBus();

// Subscribe to one event type...
events.subscribe(ModelResponseEvent.class, e ->
        System.out.println(e.modelName() + " produced " + e.response().usage().outputTokens() + " tokens"));

// ...or to Event.class to observe everything that flows through the bus.
events.subscribe(Event.class, e -> log.debug("event: {}", e));
```

`Event` is just an interface, so an application or module can **define and publish its own events**
through the same bus — from inside an agent (subclasses get a `publish(Event)` helper) or from any
component that injects the bus:

```java
public record OrderPlaced(String orderId, Instant occurredAt) implements Event {
    public OrderPlaced(String orderId) { this(orderId, Instant.now()); }
}
eventBus.publish(new OrderPlaced("A-123"));
```

The default `InMemoryEventBus` delivers events synchronously and in-process. To fan events across
services, implement `AbstractEventBus` over Redis pub/sub, Kafka or a broker and mark it `@EventBus`;
that bean replaces the default everywhere it is injected, with no change to publishers or subscribers.

**Under Spring Boot, the bus is bridged to Spring's event system both ways** (see the
[starter README](sprout-spring-boot-starter/README.md)): a Sprout event published on the bus is
re-published through Spring so a `@EventListener` can handle it, and a Sprout `Event` published with
Spring's `ApplicationEventPublisher` is forwarded onto the bus to reach Sprout subscribers — so the
agent half and the Spring half of an app can react to each other's events without knowing which side
raised them.

### Monitoring

`sprout-monitoring` turns the event stream above into **usage, token and cost totals** — per model,
per agent and per tool — with no change to your agents. It adds a `@UsageStore` component, wired by its
own `@Processor` like `@Model` or `@ConversationStore`: the processor registers the store (under the name
`usageStore`) and subscribes a collector to the event bus, so every execution is folded in. The shipped
`InMemoryUsageStore` is the in-memory default — put its package on your component scan to use it (the same
way the [RAG example](sprout-examples/README.md) pulls in core's in-memory vector store):

```properties
sprout.scan.base-packages=com.example.app,io.github.ivannavas.sprout.monitoring.impl
```

To persist usage instead, implement `AbstractUsageStore`, mark it `@UsageStore` and let it be scanned in
your own package; that bean is the store, with nothing else changing (the same swap-the-component model as
`@EventBus` and `@ConversationStore`). Then read the totals anywhere:

```java
UsageSnapshot usage = container.<AbstractUsageStore>getSingleton("usageStore").snapshot();
System.out.println(usage.modelCalls() + " calls, " + usage.totalTokens() + " tokens, $" + usage.totalCost());
usage.byAgent().get("WeatherAgent");   // runs (completed/failed), iterations, tokens
usage.byTool().get("forecast");        // call count
```

Costs are derived from rates you configure per model — properties of the form
`sprout.monitoring.pricing.<modelName>.input` / `.output`, each a price per **one million** tokens. An
unpriced model still has its tokens tracked, at zero cost. Because the store is a managed singleton named
`usageStore`, under the Spring Boot starter it is exposed as a Spring bean automatically. See
[sprout-examples](sprout-examples/README.md) for a runnable report.

### MCP

With `sprout-mcp` on the classpath, an `@Mcp` bean's `@Tool` methods are published over the Model
Context Protocol, and an `@Agent` can connect to remote MCP servers with `@UseMcp` to use their tools
as if they were its own. See [sprout-examples](sprout-examples/README.md) for both sides.

### Spring Boot — fully compatible

Sprout runs **inside Spring with no glue code**: add `sprout-spring-boot-starter` and the container is
bootstrapped during context startup. Integration works in both directions —

- **Sprout → Spring:** every Sprout bean (agents' executors, models, services, ...) is registered in
  the `ApplicationContext`, so you can `@Autowired` it into any `@Controller`/`@Service`.
- **Spring → Sprout:** a Sprout component can depend on a Spring bean; it is resolved from the Spring
  `BeanFactory` (as a lazy proxy, preserving AOP/`@Transactional`).

So an agent can be backed by an existing Spring `@Service` and called straight from a controller:

```java
@Agent(model = AnthropicModelExecutor.class, systemPrompt = "You are a weather assistant.")
public class WeatherAgent extends AgentExecutor {

    private final WeatherService weather;          // an existing Spring @Service

    @Autowired
    public WeatherAgent(WeatherService weather) {  // Spring bean injected into the agent
        this.weather = weather;
    }

    @Tool(description = "Look up the forecast for a city")
    public String forecast(String city) {
        return weather.forecast(city);
    }
}

@RestController
class WeatherController {

    private final AgentExecutor agent;             // the WeatherAgent, exposed as a Spring bean

    WeatherController(@Qualifier("weatherAgentExecutor") AgentExecutor agent) {
        this.agent = agent;
    }

    @GetMapping("/ask")
    String ask(@RequestParam String q) {
        return agent.execute("web-session", q).response();
    }
}
```

Spring's `Environment` (`application.yml`, properties, env vars) also feeds Sprout's configuration, so
there is one source of truth. You keep using Spring exactly as you already do. See the
[starter README](sprout-spring-boot-starter/README.md), and the
[Spring example](sprout-examples/README.md) for a runnable version that also persists conversations.

### Extending the container — customizable to the core

The processing pipeline is fully open, and it is the *same* mechanism the built-in features use — no
privileged core. To teach Sprout a new annotation, subclass `ComponentProcessor` and register it with
`@Processor(MyAnnotation.class)`; it is discovered automatically and applied to every component
carrying that annotation. A processor can:

- register handlers for fields, methods or class-level annotations;
- override `instantiate()` to build a specialised bean, `validate()` to enforce constraints, or
  `beanNames()` to add aliases;
- replace another processor with `@Processor(value = ..., overrides = ...)`.

A whole custom stereotype is only a few lines — define an annotation, meta-annotate it with
`@Component` so it gets scanned, and register a processor for it:

```java
@Retention(RUNTIME) @Target(TYPE) @Component   // a custom stereotype, discovered by scanning
public @interface Plugin {}

@Processor(Plugin.class)                        // applied to every @Plugin component
public class PluginProcessor extends ComponentProcessor {

    public PluginProcessor(Class<?> component, SproutContainer container) {
        super(component, container);
    }

    @Override
    public Set<String> beanNames() {            // standard wiring still runs; here we add an alias
        Set<String> names = new HashSet<>(super.beanNames());
        names.add("plugin:" + component.getSimpleName());
        return names;
    }
}
```

This is exactly how `@Agent`, `@Model`, `@Configuration` and `@Mcp` are implemented — each lives in
its own module and plugs in without the core knowing about it. The same door is open to your code and
to third-party modules, so the framework can grow a module for anything: a new model provider, a
transport, a persistence-backed conversation store, custom stereotypes, and so on.

## Build

```bash
mvn package
```

## Examples

| Example | What it shows | Run |
|---|---|---|
| basic | A plain-Java agent on a live model, then several runs fanned out concurrently | `mvn -pl sprout-examples -am exec:exec` *(needs `ANTHROPIC_API_KEY`)* |
| mcp | Publish `@Tool` methods as an MCP server and consume them from a client agent | `mvn -pl sprout-examples -am -Pmcp exec:exec` |
| orchestration | Concurrent runs, supervisor **delegation** and conversation **hand-off** — one cast of agents | `mvn -pl sprout-examples -am -Porchestration exec:exec` |
| rag | **Retrieval-augmented generation** — an agent answers from a knowledge base indexed into the built-in vector store | `mvn -pl sprout-examples -am -Prag exec:exec` |
| events | **Event bus** — subscribe to an agent run's prefab lifecycle events as they happen | `mvn -pl sprout-examples -am -Pevents exec:exec` |
| **spring** | **Spring + orchestration together** — see below | `mvn -pl sprout-examples -am spring-boot:run` |

> **The headline — Spring + orchestration in one request.** The Spring example's `/weather/batch`
> endpoint is a plain `@RestController` that fans a forecast-per-city out **concurrently** with
> `AgentOrchestrator`, over a Spring-managed `@Agent` whose tool is a Spring `@Service` and whose
> conversations persist to a database. So a single HTTP call drives concurrent multi-agent work with
> Spring DI and JPA persistence — no separate Python service, no new programming model:
>
> ```bash
> mvn -pl sprout-examples -am spring-boot:run
> curl "http://localhost:8080/weather/batch?cities=Madrid,Paris,London"
> ```

Everything but `basic` runs offline with no API key. See
[sprout-examples/README.md](sprout-examples/README.md) for a full walkthrough.

## What's coming

This is the first release, so the surface is deliberately focused. The list below is a starting point
— priorities and details will change.

- **Nested object tool parameters.** Schemas already cover primitives, enums, collections,
  optional/required and per-parameter descriptions; arbitrary nested objects (POJOs) are still the
  gap.
- **Built-in conversation stores.** Ship JDBC and Redis `AbstractConversationStore` implementations
  in their own modules (the JPA store currently lives only in the example).
- **Agent limits.** Reinstate first-class token-budget and timeout limits per run, with clear errors.
- **HTTP retries & proxy.** The Anthropic/OpenAI executors already have connect/request timeouts and
  a configurable base URL; automatic retries, proxy support and friendlier error mapping are next.
- **Real provider streaming.** SSE token streaming in the Anthropic/OpenAI executors (the agent
  already streams via `executeStream`; these still return their text in one piece).
- **More providers.** Google Gemini, Azure OpenAI and local models (e.g. Ollama) as drop-in modules.
- **Constructor injection without `@Autowired`** when a class has a single constructor, and a
  `@PreDestroy` lifecycle callback.
- **Configuration coherence.** Make `@Configuration` classes discoverable on their own and document
  precedence rules end to end.
- **Richer multi-agent teams.** `sprout-orchestration` already does concurrent runs, supervisor
  *delegation* and conversation *hand-off* (each agent keeping its own system prompt); next is dynamic
  team membership and shared scratchpad state.
- **Richer RAG.** Core RAG has shipped — per-agent retrieval, an in-memory vector store and
  lexical/semantic embedding models; next are persistent vector-store modules (e.g. pgvector, Redis),
  document loaders and chunking, and conversational memory.
- **Observability.** An event bus publishes the agent/model/tool lifecycle (and takes custom events),
  and `sprout-monitoring` already accumulates usage, token and cost totals on top of it; next are
  tracing and richer metrics backends (e.g. a Micrometer/Prometheus `@UsageStore`), plus distributed
  event-bus modules (Redis pub/sub, Kafka).
- **Structured output.** Schema-constrained model output mapped to typed Java objects.
- **Automatic exposure layers.** Generate a REST/SSE (and possibly gRPC) endpoint per agent.
- **Human-in-the-loop.** Approval/guardrail hooks before tool calls execute.
- **Parallel & async tool calls** within a single turn.
- **Richer MCP.** HTTP/SSE transport, MCP resources and prompts (not just tools), and reconnection.
- **Durable, resumable agents.** Long-running and scheduled agents over a persistent store.
- **GraalVM native image.** Reflection metadata so Sprout apps compile to native binaries.

## License

Licensed under the [Apache License 2.0](LICENSE).
