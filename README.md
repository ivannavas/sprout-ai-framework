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

> **Version `1.0.0` — first release.** Sprout is functional and tested, but it is young: some
> capabilities are still basic and parts of the API may change. Expect gaps and rough edges, and see
> [what's coming](#whats-coming) for what's planned next.

## Modules

| Module | What it provides |
|---|---|
| `sprout-core` | IoC container, component scanning, dependency injection, configuration, and the agent/model/tool abstractions. |
| `sprout-anthropic` | `ModelExecutor` for Anthropic's Messages API (`@Model("anthropic")`). |
| `sprout-openai` | `ModelExecutor` for OpenAI's Chat Completions API (`@Model("openai")`). |
| `sprout-mcp` | Model Context Protocol support: expose `@Tool` methods as an MCP server, and consume remote MCP servers from an agent. |
| `sprout-spring-boot-starter` | Runs Sprout inside Spring Boot, bridging beans and configuration both ways. See its [README](sprout-spring-boot-starter/README.md). |
| `sprout-examples` | Runnable examples (basic, MCP, Spring). See its [README](sprout-examples/README.md). |

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
`ModelExecutor` and implementing `chat(ModelRequest)`.

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

To stream a run instead of blocking, call `executeStream(conversationId, prompt, listener)`:
assistant tokens, tool calls and the final response are delivered through a `StreamListener` as they
happen. Token granularity depends on the model — a provider that overrides `chatStream` emits
incremental chunks, otherwise each turn's text arrives in one piece.

Since the agent is itself a managed bean, you don't have to look it up — you can `@Autowired` it by
type (`AgentExecutor`) or by its `<agentName>Executor` name, which is exactly what the Spring
example's controller does.

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

## Run the examples

```bash
# basic (needs ANTHROPIC_API_KEY)
mvn -pl sprout-examples -am exec:exec

# mcp (offline, no API key)
mvn -pl sprout-examples -am -Pmcp exec:exec

# spring
mvn -pl sprout-examples -am spring-boot:run
```

See [sprout-examples/README.md](sprout-examples/README.md) for what each one demonstrates.

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
- **Multi-agent orchestration.** Agents that delegate to or hand off between other agents.
- **Memory & RAG.** Vector-store integration and retrieval tools as a first-class module.
- **Observability.** Tracing, metrics and token/cost accounting hooks around the agent loop.
- **Structured output.** Schema-constrained model output mapped to typed Java objects.
- **Automatic exposure layers.** Generate a REST/SSE (and possibly gRPC) endpoint per agent.
- **Human-in-the-loop.** Approval/guardrail hooks before tool calls execute.
- **Parallel & async tool calls** within a single turn.
- **Richer MCP.** HTTP/SSE transport, MCP resources and prompts (not just tools), and reconnection.
- **Durable, resumable agents.** Long-running and scheduled agents over a persistent store.
- **GraalVM native image.** Reflection metadata so Sprout apps compile to native binaries.

## License

Licensed under the [Apache License 2.0](LICENSE).
