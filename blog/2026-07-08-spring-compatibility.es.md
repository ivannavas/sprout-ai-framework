---
title: "Sprout 1.5.0: compatibilidad con Spring, sin fricción"
date: 2026-07-08
lang: es
tags: [release, spring, java]
description: "1.5.0 funde Sprout y Spring en un mismo código — mezcla las anotaciones de inyección de ambos frameworks en la misma clase, en los dos sentidos, y añade proveedores de IA sin cablear nada."
---

# Sprout 1.5.0: compatibilidad con Spring, sin fricción

Sprout siempre ha corrido **dentro** de Spring Boot: añades `sprout-spring-boot-starter` y cada bean de
Sprout —agentes, modelos, servicios— queda registrado en el `ApplicationContext`, mientras que a su vez
los componentes de Sprout pueden depender de tus beans de Spring. Un solo proceso, una sola
configuración, sin código pegamento.

**1.5.0 lima las últimas asperezas de esa costura.** El objetivo de esta release era simple: una clase de
Sprout y una de Spring deben sentirse como el mismo tipo de objeto, para que dejes de pensar en qué
contenedor vive cada bean. Esto es lo que cambia.

## Mezcla las anotaciones de ambos frameworks — en la misma clase y en los dos sentidos

Sprout y Spring traen cada uno su `@Autowired`, su `@Value` y su `@Qualifier`. Hasta ahora, usar la "que
no tocaba" dentro de una clase gestionada por el otro contenedor hacía que se ignorara en silencio — un
pinchazo que solo notabas cuando un campo llegaba a `null`.

En 1.5.0 los dos juegos se tratan como **equivalentes**. La clase funciona uses la que uses, y mover una
clase de un contenedor al otro no requiere cambiar ninguna anotación.

> **Una red de seguridad, no un estilo.** Esto existe para que el código mezclado o en migración nunca se
> rompa en silencio — no para repartir ambos tipos por todas partes. La recomendación es simple: **usa las
> anotaciones de Sprout en componentes de Sprout, y las del contenedor DI correspondiente en sus propios
> beans.** Mantén cada clase coherente con el contenedor que la gestiona, y tira de la interoperabilidad
> solo mientras migras, o cuando una clase de verdad vive a caballo entre los dos mundos. Los ejemplos de
> abajo muestran lo que es *posible*; lo *recomendado* es la coherencia.

**Un componente de Sprout, cableado con anotaciones de Spring (y de JSR-330).** Dentro de un `@Service`,
`@Model` o `@Agent` de Sprout, el `@Autowired`/`@Value`/`@Qualifier` de Spring, `jakarta.inject.@Inject`
y `@Named` se respetan — junto a los propios de Sprout:

```java
@Service                                                          // estereotipo de Sprout
public class PricingService {

    @org.springframework.beans.factory.annotation.Autowired       // @Autowired de Spring
    private RateRepository rates;                                 // se resuelve del contexto de Spring

    @org.springframework.beans.factory.annotation.Value("${pricing.currency}")  // @Value de Spring
    private String currency;

    @io.github.ivannavas.sprout.annotation.Value("${pricing.margin:0.15}")      // @Value de Sprout
    private double margin;
}
```

**Un bean de Spring, cableado con anotaciones de Sprout.** La imagen espejo también funciona. Un
`@Component` de Spring puede usar el `@Autowired`/`@Value`/`@Qualifier`/`@PostConstruct` de Sprout — útil
cuando un bean de Spring necesita traerse un agente gestionado por Sprout o una property resuelta por
Sprout:

```java
@Component                                                        // estereotipo de Spring
public class ReportBuilder {

    @io.github.ivannavas.sprout.annotation.Autowired              // @Autowired de Sprout
    private SummaryAgentExecutor agent;                           // un bean @Agent de Sprout

    @io.github.ivannavas.sprout.annotation.Value("${report.title}")   // @Value de Sprout
    private String title;

    @io.github.ivannavas.sprout.annotation.PostConstruct
    void init() { /* se ejecuta tras cablear los campos de arriba */ }
}
```

Spring sigue gestionando sus propias anotaciones; Sprout solo rellena *las suyas* en los beans de Spring,
y nunca procesa un bean dos veces. Por esta vía se soportan la inyección por campo y `@PostConstruct` —
para inyección por **constructor** en un bean de Spring, usa el `@Autowired` de Spring (la inyección por
constructor de Sprout aplica a los componentes de Sprout).

Por dentro, nada de esto mete Spring en el núcleo de Sprout: el core reconoce solo sus propias
anotaciones, y es el starter quien aporta las equivalentes de Spring y JSR-330. Si nunca usas Spring, el
core sigue siendo totalmente agnóstico al framework.

## Añade un proveedor de IA — sin cablear nada

Añadir un proveedor de modelos a una app de Spring requería antes un paso extra: decirle a Sprout dónde
vivía el ejecutor, con `sprout.scan.base-packages`. En 1.5.0 cada módulo **aporta su propio paquete al
escaneo**, así que los ejecutores `@Model` de OpenAI y Anthropic se descubren en cuanto el jar está en el
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

Y ya está — sin configurar escaneo. Inyecta el ejecutor en cualquier bean de Spring por nombre:

```java
@RestController
class ChatController {

    private final ModelExecutor openai;

    ChatController(@Qualifier("openai") ModelExecutor openai) {  // un @Model de Sprout, inyectado por Spring
        this.openai = openai;
    }
}
```

`sprout-monitoring` se comporta igual: añade el módulo y el seguimiento de uso, tokens y coste se activa
solo, expuesto como el bean `usageStore` de Spring — declara tu propio `@UsageStore` y toma el relevo.

## La configuración ya era compartida

Merece la pena repetirlo, porque completa el cuadro: el `Environment` de Spring —
`application.yml`/`application.properties`, propiedades de sistema, variables de entorno— alimenta la
configuración de Sprout. Un `@Value("${pricing.currency}")` de Sprout lee la misma property que leería un
`@Value` de Spring, de la misma fuente de verdad. Nada que duplicar.

## Cómo conseguir 1.5.0

Sube las dependencias de Sprout de tu app de Spring Boot a `1.5.0` y mantén todos los artefactos en la
misma versión:

```xml
<dependency>
    <groupId>io.github.ivannavas</groupId>
    <artifactId>sprout-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

Después borra la línea `sprout.scan.base-packages` que mantenías solo por los módulos de proveedor — ya
no la necesitas. El [README del starter](../sprout-spring-boot-starter/README.md) tiene la referencia
completa, incluidas las reglas de mezcla de anotaciones.
