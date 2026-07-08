---
title: "Sprout 1.5.0: Spring compatibility, without the friction"
date: 2026-07-08
lang: en
tags: [release, spring, java]
description: "1.5.0 makes Sprout and Spring blend into one codebase — mix each framework's DI annotations in the same class, both ways, and drop in AI providers with zero wiring."
---

# Sprout 1.5.0: Spring compatibility, without the friction

Sprout has always run **inside** Spring Boot: add `sprout-spring-boot-starter`, and every Sprout bean —
agents, models, services — is registered in the `ApplicationContext`, while Sprout components can depend
on your Spring beans in return. One process, one configuration, no glue code.

**1.5.0 takes the last rough edges off that seam.** The goal for this release was simple: a Sprout class
and a Spring class should feel like the same kind of object, so you stop thinking about which container a
bean lives in. Here's what changed.

## Mix each framework's annotations — in the same class, both ways

Sprout and Spring each ship an `@Autowired`, a `@Value` and a `@Qualifier`. Until now, reaching for the
"wrong" one inside a class managed by the other container meant it was silently ignored — a papercut you
only noticed when a field came back `null`.

In 1.5.0 the two sets are treated as **equivalents**. A class works whichever one you reach for, and
moving a class between the two containers needs no annotation changes.

> **A safety net, not a style.** This exists so mixed or migrating code never breaks silently — not as an
> invitation to scatter both flavours everywhere. The recommendation stays simple: **use Sprout's
> annotations in Sprout components, and each DI container's own annotations in its own beans.** Keep a
> class consistent with the container that manages it, and reach for the interchange only while migrating,
> or when a class genuinely straddles both worlds. The examples below show what's *possible*; consistency
> is what's *advised*.

**A Sprout component, wired with Spring's (and JSR-330's) annotations.** Inside a Sprout `@Service`,
`@Model` or `@Agent`, Spring's `@Autowired`/`@Value`/`@Qualifier`, `jakarta.inject.@Inject` and `@Named`
are all honoured — right next to Sprout's own:

```java
@Service                                                          // Sprout's stereotype
public class PricingService {

    @org.springframework.beans.factory.annotation.Autowired       // Spring's @Autowired
    private RateRepository rates;                                 // resolved from the Spring context

    @org.springframework.beans.factory.annotation.Value("${pricing.currency}")  // Spring's @Value
    private String currency;

    @io.github.ivannavas.sprout.annotation.Value("${pricing.margin:0.15}")      // Sprout's @Value
    private double margin;
}
```

**A Spring bean, wired with Sprout's annotations.** The mirror image works too. A plain
Spring-`@Component` can use Sprout's `@Autowired`/`@Value`/`@Qualifier`/`@PostConstruct` — handy when a
Spring bean needs to pull in a Sprout-managed agent or a Sprout-resolved property:

```java
@Component                                                        // Spring's stereotype
public class ReportBuilder {

    @io.github.ivannavas.sprout.annotation.Autowired              // Sprout's @Autowired
    private SummaryAgentExecutor agent;                           // a Sprout @Agent bean

    @io.github.ivannavas.sprout.annotation.Value("${report.title}")   // Sprout's @Value
    private String title;

    @io.github.ivannavas.sprout.annotation.PostConstruct
    void init() { /* runs after the fields above are wired */ }
}
```

Spring keeps handling its own annotations; Sprout only fills in *its* annotations on Spring beans, and
never processes a bean twice. Field injection and `@PostConstruct` are supported this way — for
**constructor** injection on a Spring bean, use Spring's `@Autowired` (Sprout's own constructor injection
applies to Sprout components).

Under the hood, none of this leaks Spring into Sprout's core: the core recognises only its own
annotations, and the starter contributes the Spring and JSR-330 equivalents. If you never use Spring, the
core stays completely framework-agnostic.

## Drop in an AI provider — zero wiring

Adding a model provider to a Spring app used to need one extra step: telling Sprout where the executor
lived, via `sprout.scan.base-packages`. In 1.5.0, each module **contributes its own package to the
scan**, so the OpenAI and Anthropic `@Model` executors are discovered the moment the jar is on the
classpath.

```xml
<dependency>
    <groupId>io.github.ivannavas</groupId>
    <artifactId>sprout-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
<dependency>
    <groupId>io.github.ivannavas</groupId>
    <artifactId>sprout-openai</artifactId>
    <version>1.5.0</version>
</dependency>
```

That's it — no scan configuration. Inject the executor into any Spring bean by name:

```java
@RestController
class ChatController {

    private final ModelExecutor openai;

    ChatController(@Qualifier("openai") ModelExecutor openai) {  // a Sprout @Model, injected by Spring
        this.openai = openai;
    }
}
```

`sprout-monitoring` behaves the same way: add the module and usage, token and cost tracking activates on
its own, exposed as the Spring `usageStore` bean — declare your own `@UsageStore` and it takes over.

## Configuration was already shared

Worth repeating, because it completes the picture: Spring's `Environment` —
`application.yml`/`application.properties`, system properties, env vars — feeds Sprout's configuration.
A Sprout `@Value("${pricing.currency}")` reads the same property a Spring `@Value` would, from the same
source of truth. Nothing to duplicate.

## Getting 1.5.0

Bump the Sprout dependencies in your Spring Boot app to `1.5.0` and keep every artifact on the same
version:

```xml
<dependency>
    <groupId>io.github.ivannavas</groupId>
    <artifactId>sprout-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

Then delete the `sprout.scan.base-packages` line you were keeping just for the provider modules — you
won't need it anymore. The [starter README](../sprout-spring-boot-starter/README.md) has the full
reference, including the annotation-mixing rules.
