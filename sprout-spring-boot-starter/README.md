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

## Usage

Add the dependency:

```xml
<dependency>
    <groupId>io.github.ivannavas</groupId>
    <artifactId>sprout-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Configure the packages Sprout should scan (defaults to the package of your `@SpringBootApplication`):

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
