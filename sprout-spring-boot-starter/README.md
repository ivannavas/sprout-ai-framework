# sprout-spring-boot-starter

Spring Boot starter that integrates the [Sprout AI Framework](../) into a Spring application.

It lets you add AI agents to an existing Spring Boot service and wire them with the beans,
configuration and tests you already have — agents can depend on your `@Service`s, and your
controllers can depend on agents, with no separate process and no second DI container to reason about.

When this starter is on the classpath, it auto-configures Sprout so that:

- The `SproutContainer` is bootstrapped during Spring context startup.
- Every Sprout-managed bean (`@Service`, `@Component`, `@Agent` executors, `@Model` executors, ...)
  is registered in the Spring `ApplicationContext` and can be injected with `@Autowired`, both by
  type and by name.
- Spring's `Environment` (`application.yml` / `application.properties`, system properties, env vars)
  is bridged into Sprout, so `@Value`, `@ConfigurationProperty` and `sprout.scan.base-packages`
  resolve from your Spring configuration. Spring values take precedence over `sprout.properties`.
- Sprout and Spring **annotations interchange in both directions** — a Sprout component can be wired
  with Spring's (or JSR-330's) `@Autowired`/`@Value`/`@Qualifier`, and a Spring bean with Sprout's.
  See [Mixing Sprout and Spring annotations](#mixing-sprout-and-spring-annotations).

## Usage

Add the dependency:

```xml
<dependency>
    <groupId>io.github.ivannavas</groupId>
    <artifactId>sprout-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

Sprout scans the package of your `@SpringBootApplication` by default, and each Sprout module on the
classpath contributes its own package automatically — so the `sprout-openai` / `sprout-anthropic`
`@Model` executors are discovered with no configuration (and `sprout-monitoring` activates on its own).
Set `sprout.scan.base-packages` only to add your own packages (or to point elsewhere than the main class):

```yaml
sprout:
  enabled: true            # set to false to disable the integration
  scan:
    base-packages: com.acme.agents
```

Then inject Sprout beans from regular Spring components:

```java
@RestController
class ChatController {

    private final SupportAgentExecutor agent; // a Sprout @Agent executor bean

    ChatController(SupportAgentExecutor agent) {
        this.agent = agent;
    }

    @PostMapping("/chat")
    String chat(@RequestBody String prompt) {
        return agent.execute("conversation-1", prompt).response();
    }
}
```

The raw `SproutContainer` is also available as a bean named `sproutContainer` if you need to look up
beans dynamically.

## Mixing Sprout and Spring annotations

Sprout and Spring each ship an `@Autowired`, a `@Value` and a `@Qualifier`. In a mixed codebase the two
sets are treated as equivalents, so a class works whichever one you reach for — you never have to
remember "which `@Value` is this?", and moving a class between the two containers needs no annotation
changes.

> **Recommendation.** Treat this as a compatibility net, not a style. It exists so mixed or migrating
> code never breaks silently — but for clarity, **use Sprout's annotations in Sprout components, and each
> DI container's own annotations in its own beans** (Spring's in Spring beans, JSR-330's where you use
> JSR-330). Keep a class's annotations consistent with the container that manages it; lean on the
> interchange only while migrating, or when a class legitimately straddles both worlds.

**A Sprout component wired with Spring (and JSR-330) annotations.** Inside a Sprout `@Service`,
`@Model`, `@Agent`, ... you can use Spring's `@Autowired`/`@Value`/`@Qualifier`, `jakarta.inject.@Inject`
or `@Named`, right alongside Sprout's own — each is honoured:

```java
@Service                                             // Sprout's stereotype
public class PricingService {

    @org.springframework.beans.factory.annotation.Autowired   // Spring's @Autowired
    private RateRepository rates;                     // resolved from the Spring context

    @org.springframework.beans.factory.annotation.Value("${pricing.currency}")  // Spring's @Value
    private String currency;

    @io.github.ivannavas.sprout.annotation.Value("${pricing.margin:0.15}")      // Sprout's @Value
    private double margin;
}
```

**A Spring bean wired with Sprout's annotations.** The mirror image also works: a plain Spring-managed
bean can use Sprout's `@Autowired`/`@Value`/`@Qualifier`/`@PostConstruct` — handy when a Spring `@Bean`
needs to pull in a Sprout-managed component or a Sprout-resolved property:

```java
@Component                                           // Spring's stereotype
public class ReportBuilder {

    @io.github.ivannavas.sprout.annotation.Autowired // Sprout's @Autowired
    private SummaryAgentExecutor agent;              // a Sprout @Agent bean

    @io.github.ivannavas.sprout.annotation.Value("${report.title}")  // Sprout's @Value
    private String title;

    @io.github.ivannavas.sprout.annotation.PostConstruct
    void init() { /* runs after the fields above are wired */ }
}
```

Spring keeps handling its own annotations; the starter only fills in Sprout's on Spring beans, and never
processes a bean twice. Field injection and `@PostConstruct` are supported this way; for **constructor**
injection on a Spring bean, use Spring's `@Autowired` (Sprout's constructor injection applies to Sprout
components).

## Events

Sprout's [event bus](../#events) is bridged to Spring's application-event system in **both directions**,
so the agent half and the Spring half of an app react to each other's events without knowing which side
raised them. The bus is exposed as the `eventBus` bean (type `AbstractEventBus`), and a
`SpringEventBridge` is auto-configured to relay events:

- **Sprout → Spring:** every event published on the bus — the prefab agent/model lifecycle events, or
  your own — is re-published through Spring's `ApplicationEventPublisher`, so a Spring `@EventListener`
  handles it like any other Spring event:

  ```java
  @Component
  class AgentAuditor {
      @EventListener
      void onCompleted(AgentCompletedEvent event) {
          log.info("{} finished in {} iterations", event.agentName(), event.result().iterations());
      }
  }
  ```

- **Spring → Sprout:** a Sprout `Event` published through Spring (an `ApplicationEventPublisher` or
  `@Autowired ApplicationContext`) is forwarded onto the bus, reaching every Sprout subscriber —
  including an agent or model that subscribed at startup.

A forwarded event is delivered exactly once on each side; the bridge suppresses the echo that would
otherwise loop it back. This relies on Spring's default synchronous event delivery. Provide your own
`SpringEventBridge` bean to override the default. Bridging requires no configuration — it is active
whenever the starter is on the classpath.

## Notes

- Bean names follow Sprout's conventions (e.g. an `@Agent SupportAgent` exposes a
  `supportAgentExecutor` executor bean, an `@Model("openai") ...` exposes an `openai` bean). If a
  Sprout bean name collides with an existing Spring bean, it is registered under `sprout_<name>`.
- Sprout keeps its own dependency injection; this starter exposes the resulting beans to Spring.
- Sprout components can also depend on Spring beans: when a dependency cannot be satisfied by a
  Sprout component, it is resolved from the Spring `BeanFactory` and injected as a **lazy proxy**
  (the real Spring bean is fetched on first use, once the context is fully initialised). The target
  type must be proxyable (interfaces, or non-`final` concrete classes — not records). Avoid calling
  such a dependency from a Sprout `@PostConstruct`, as that would force the Spring bean to be created
  prematurely during context startup.
