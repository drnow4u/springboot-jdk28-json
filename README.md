# springboot-jdk28-json
Spring Boot with JDK-28 JSON

## Why drop Jackson?

This project excludes `spring-boot-starter-jackson` and uses the JDK 28 incubating JSON API (`jdk.incubator.json`) instead.
One motivation is the vulnerability history of Jackson 2.x (`com.fasterxml.jackson.*`).

### Jackson 2.x vulnerabilities by year

Vulnerabilities by year of publication, from the [OSV](https://osv.dev/) database (GitHub advisories), checked on 2026-10-30.
Libraries with no vulnerabilities are not listed.

| Artifact                  | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 | Total |
|---------------------------|------|------|------|------|------|------|------|------|------|-------|
| `jackson-databind`        | 4    | 19   | 23   | 18   | 4    | 1    | 0    | 0    | 17   | 86    |
| `jackson-core`            | 0    | 0    | 0    | 0    | 0    | 0    | 0    | 2    | 4    | 6     |
| `jackson-datatype-jsr310` | 1    | 0    | 0    | 0    | 0    | 0    | 0    | 0    | 0    | 1     |

The 2021 `jackson-databind` count includes 16 CVEs from 2020 that GitHub published as advisories in December 2021.

## What the JDK handles and what this project handles

Dropping Jackson removes a library, not the mapping work.
The JDK 28 JSON API turns text into a tree of `JsonValue`s and back; mapping that tree to Java types is left to the application.

| Concern                                                  | Handled by                                                                                                                       |
|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| Parsing JSON text into `JsonValue`                       | JDK (`Json.parse`)                                                                                                               |
| Writing `JsonValue` back to text                         | JDK (`JsonValue.toString`)                                                                                                       |
| Mapping JSON objects to and from records                 | This project: [`JsonRecordHttpMessageConverter`](src/main/java/com/github/drnow4u/jdk28json/JsonRecordHttpMessageConverter.java) |
| Plugging into Spring MVC (`@RequestBody`, return values) | This project: the same converter, registered as a `@Component`                                                                   |

The converter's mapping rules:
- Supported record component types: `String`, `int`/`Integer`, `long`/`Long`, `double`/`Double`, `boolean`/`Boolean` and nested records.
  Lists, maps, enums and date/time types are not supported yet.
- Missing or `null` fields become `null`; a missing primitive field is an error.
- Unknown fields are ignored, as with Spring Boot's default Jackson configuration.
- Malformed JSON or a value of the wrong type results in `400 Bad Request`.
- Request bodies larger than `jdk-json.max-body-size` (default `1MB`) result in `413 Payload Too Large`, because the JDK parser itself has no size limits.
- Error messages are shortened to 200 characters, because the JDK parser repeats the offending input in them.

These rules are code this project maintains, so they are covered by unit tests in
[`JsonRecordHttpMessageConverterTests`](src/test/java/com/github/drnow4u/jdk28json/JsonRecordHttpMessageConverterTests.java).

### Incubator status

`jdk.incubator.json` is an incubator module: it needs `--add-modules jdk.incubator.json` at compile, test and run time (configured in `pom.xml`), and its API may change or be removed in a future JDK.
To limit the impact, only `JsonRecordHttpMessageConverter` imports it; controllers work with plain records.
