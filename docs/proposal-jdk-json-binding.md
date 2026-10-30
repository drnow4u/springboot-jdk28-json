# Proposal: JSON binding for Spring on the JDK's built-in JSON API

**Status:** draft for discussion
**Prototype:** https://github.com/drnow4u/springboot-jdk28-json

## Summary

JDK 28 ships an incubating JSON API (`jdk.incubator.json`) that parses JSON text into a tree of `JsonValue`s and writes it back.
Spring applications could use it to read `@RequestBody` arguments and write return values without a third-party JSON library.

The JDK API does not map JSON to Java types: that part has to be provided by Spring or a library.
This proposal asks how that mapping should be implemented, in particular **runtime reflection vs. compile-time generated code (annotation processor)**, and where it should live.

## Motivation

- **Fewer dependencies.** A JSON endpoint currently needs Jackson, Gson or JSON-B. A JDK-only option removes a large dependency tree from applications that only need simple JSON.
- **Smaller attack surface.** Jackson 2.x `jackson-databind` has 86 advisories in the OSV database, 17 of them published in 2026 (see the prototype's README for the per-year breakdown).
- **GraalVM native image.** A binding that works without reflection metadata would make native images simpler to build.

## What the JDK provides and what is missing

| Concern                                                                            | JDK 28 `jdk.incubator.json`                                                                        | Needed from Spring or a library |
|------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|---------------------------------|
| Parse text into a tree                                                             | `Json.parse`                                                                                       | —                               |
| Write a tree back to text                                                          | `JsonValue.toString`                                                                               | —                               |
| Build trees                                                                        | `JsonObject.of`, `JsonArray.of`, `JsonString.of`, `JsonNumber.of`, `JsonBoolean.of`, `JsonNull.of` | —                               |
| Map JSON to and from Java types (records, lists, enums, dates)                     | —                                                                                                  | ✔                              |
| `HttpMessageConverter` for Spring MVC, `RestClient`, `RestTemplate`                | —                                                                                                  | ✔                              |
| `MessageConverter` for `spring-messaging` (`@KafkaListener`, JMS, RabbitMQ, STOMP) | —                                                                                                  | ✔                              |
| Kafka `Serializer` / `Deserializer` (Spring for Apache Kafka)                      | —                                                                                                  | ✔                              |
| Error mapping (malformed JSON or wrong type → `400 Bad Request`)                   | throws `JsonParseException` and runtime exceptions                                                 | ✔                              |

## Beyond HTTP: Kafka and messaging

The binding should not be tied to HTTP. The JSON ↔ record mapping should be a core component without Spring web dependencies, with thin adapters for each integration point:

| Adapter                                                                | Interface                                                           | Project                               |
|------------------------------------------------------------------------|---------------------------------------------------------------------|---------------------------------------|
| HTTP                                                                   | `HttpMessageConverter`                                              | Spring Framework (`spring-web`)       |
| Messaging (`@KafkaListener`, `@JmsListener`, `@RabbitListener`, STOMP) | `MessageConverter`                                                  | Spring Framework (`spring-messaging`) |
| Kafka producer and consumer                                            | `org.apache.kafka.common.serialization.Serializer` / `Deserializer` | Spring for Apache Kafka               |

Kafka adds two concerns:
- **Type from headers.** Spring Kafka's Jackson `JsonDeserializer` can choose the target class from a `__TypeId__` record header, restricted by a trusted-packages list. The rule "never let the input choose the type" applies to headers as well: the target type should come from configuration or the listener method signature.
- **Size limits.** Kafka limits message size on the broker (`message.max.bytes`), but consumers still parse whatever arrives, so the same body-size and error-message limits apply.

## Options

### A. Reflection-based converter

A single `HttpMessageConverter` reads record components, constructors and accessors at runtime.
**Implemented in the prototype:** `JsonRecordHttpMessageConverter`, with 13 unit tests.

### B. Compile-time binding (annotation processor)

Records are annotated (for example `@JsonBinding`); a processor generates a binding class per record with plain constructor and accessor calls, plus a registry the converter uses.
The same approach is used by Kotlin Serialization (supported by Spring through a compiler plugin), Micronaut Serialization and avaje-jsonb.

### C. Hybrid

Use generated bindings when present and fall back to reflection otherwise; for native images, register the reflection fallback through Spring AOT runtime hints (`@RegisterReflectionForBinding`).

### D. Tree types only

Spring adds a converter for the JDK tree types (`JsonValue`, `JsonObject`, `JsonArray`) and nothing else.
Controllers work with trees; mapping to records is left to the application or a third-party library.

## Comparison

|                                                | A. Reflection              | B. Annotation processor                  | C. Hybrid          | D. Tree types only |
|------------------------------------------------|----------------------------|------------------------------------------|--------------------|--------------------|
| Works in a native image without extra metadata | ✘ (needs a hint per type) | ✔                                       | Partly             | ✔                 |
| Setup for application developers               | None                       | Annotation + processor on the build path | Optional processor | None               |
| Unsupported field type detected                | At runtime                 | At compile time                          | Depends on path    | Not applicable     |
| Runtime cost                                   | Reflection per call        | Direct calls                             | Mixed              | Lowest             |
| Code to maintain in Spring or the library      | Small                      | Larger (processor, templates)            | Largest            | Smallest           |
| Developer experience                           | Same as Jackson            | Extra annotation and generated sources   | Same as Jackson    | Manual mapping     |

## Security

Most of Jackson's advisories come from two areas: polymorphic deserialization (the JSON choosing which class to instantiate) and missing limits on untrusted input.

Behaviour of the JDK 28 parser on hostile input (measured on JDK 28-ea+21):

| Input                                  | JDK parser                                                  | Note                                           |
|----------------------------------------|-------------------------------------------------------------|------------------------------------------------|
| 100,000 levels of nesting              | Parsed (20 ms), no `StackOverflowError`                     | Jackson needed a fix for this (CVE-2020-36518) |
| 1,000,000-digit number                 | Parsed lazily; `asInt`/`asDouble` fail cleanly              | No silent truncation                           |
| `3000000000` or `1.9` read as `int`    | Rejected                                                    | No silent overflow or rounding                 |
| Duplicate keys `{"a":1,"a":2}`         | Rejected                                                    | No ambiguity about which value wins            |
| 50 MB string, object with 200,000 keys | Accepted (83 ms, 50 ms)                                     | **No size limits**                             |
| Error messages                         | Repeat the offending value in full (1 MB for a 1 MB number) | **Log flooding** if logged as-is               |

Design principles proposed for the binding:
- **Never let JSON choose the type.** Only the declared target type, and types reachable from it, are instantiated. No polymorphic type handling by default.
- **Limit the input size** before parsing. The prototype rejects bodies over a configurable limit (`jdk-json.max-body-size`, default 1 MB) with `413 Payload Too Large`, checking `Content-Length` first.
- **Abbreviate error messages** that include input; the prototype cuts them to 200 characters.

## Performance

- **Tree only.** The JDK API always builds the full tree; there is no streaming parser. Memory use grows with the document, so size limits matter for performance as well as security.
- **Copies.** The prototype holds the request as bytes, a `String`, a `JsonValue` tree and the final record.
- **Mapping cost.** Reflection (option A) adds a lookup and an invocation per component; generated code (option B) uses direct constructor and accessor calls. Parsing and tree building are the same in both.
- Benchmarks (JMH) against Jackson for typical request sizes are still needed before drawing conclusions.

## GraalVM native image

- **Reflection.** Spring AOT already registers reflection hints for types used in controller method signatures. In the prototype, `HelloRequest` and `HelloResponse` get `allDeclaredConstructors`, `allDeclaredFields` and their accessor methods in `reachability-metadata.json`. Types not visible in a signature, such as records nested in generic types or used only with `RestClient`, may need `@RegisterReflectionForBinding`; a forgotten hint fails only at runtime. Generated code (option B) needs no hints.
- **Incubator module.** A native image would also need `--add-modules jdk.incubator.json` at build time, and a GraalVM release based on JDK 28, expected in about 6–12 months. Until then no native image of the prototype can be built, so native-image support is designed for but not verified.

## Open questions

1. **Reflection, annotation processor, hybrid or tree types only?** Which trade-off fits Spring users best?
2. **Where should it live?** Spring Framework (next to the Jackson, Gson and JSON-B converters), Spring Boot auto-configuration, or a separate community library?
3. **Timing.** Should anything land in Spring while the API is still incubating, given that it may change and requires `--add-modules jdk.incubator.json`?
4. **Type coverage.** Which types are required for a first version: lists, maps, enums, `Optional`, `java.time`, `BigDecimal`?
5. **Customisation.** Is an annotation for renaming fields (like Jackson's `@JsonProperty`) needed, or a naming strategy (`snake_case`)?
6. **Unknown and missing fields.** Ignore unknown fields (as Spring Boot's Jackson default does) or reject them? `null` for missing fields?
7. **Integration points.** Besides MVC: `RestClient`/`RestTemplate`, WebFlux codecs, `spring-messaging` `MessageConverter`, Kafka `Serializer`/`Deserializer` in Spring for Apache Kafka, Spring Boot's `JsonParser`, test support (`@JsonTest`).
8. **Default limits.** Which defaults for body size, and should there also be limits on nesting depth, string length and number of fields, given that the JDK parser has none?
9. **Polymorphism.** Should polymorphic types (sealed interfaces) be supported at all, and if so only through an explicit, closed list of subtypes?
10. **Native image.** Is relying on Spring AOT hints acceptable for option A, or should native-image support require option B?

## Proposed direction (author's view)

- Short term: a community library with option **D** plus option **B** as the main binding, because native-image support is a core goal and reflection-based binding works against it.
- The reflection converter (option **A**) stays only as a reference implementation and as the test specification the generated code must pass.
- Propose upstream integration in Spring Framework once the JDK API leaves incubation, starting with option **D** as the smallest change.

Feedback on all of the above is welcome, especially on question 1.
