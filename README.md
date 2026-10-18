# Encrypted Fuzzy Search

Encrypted Fuzzy Search is an early-stage Java and Kotlin library project for searching encrypted application data. The goal is to support exact and approximate candidate retrieval without persisting plaintext search terms, while making the security and accuracy trade-offs visible to application teams.

The project is currently in its bootstrap milestone. The module layout and build are being established; there is no released artifact or stable API yet.

## What the library is intended to provide

- Authenticated encryption for application fields.
- Keyed indexes for exact and fuzzy candidate retrieval.
- Authorized final verification and ranking in the application.
- Unicode normalization and multilingual search variants.
- A framework-independent core with PostgreSQL as the first storage adapter.

This project does not promise zero-leakage search. A database containing protected indexes may still reveal equality, frequency, overlap, candidate sets, and access patterns. See [ARCHITECTURE.md](ARCHITECTURE.md) for the current design boundaries.

## Modules

| Module | Responsibility |
| --- | --- |
| `crypto-core` | Authenticated field encryption, key derivation, and protected index primitives |
| `language-core` | Canonical text normalization, script handling, and language transforms |
| `search-core` | Search strategies, candidate planning, scoring, and leakage policy |
| `postgres-adapter` | PostgreSQL persistence and retrieval for protected tokens |
| `spring-boot-starter` | Optional Spring Boot wiring; the core does not depend on Spring |

The bytecode target is Java 21 to keep the library usable by applications on the previous LTS baseline; CI will check it on JDK 21 and JDK 25. Gradle Kotlin DSL was selected for the multi-module build and its direct fit with Java/Kotlin projects, while the core API remains Java-first. Kotlin interoperability will be maintained without making the core depend on a framework.

## Build and test

Prerequisites: JDK 21 or newer. Use the Gradle wrapper so builds use the repository-pinned Gradle version.

```sh
./gradlew build
./gradlew test
./gradlew check
./gradlew cyclonedxBom
```

Tests are required for implementation changes. The project target is at least 90% line coverage per module containing production sources.
CI checks formatting and Java compiler warnings, runs tests on JDK 21 and 25, and enforces the coverage threshold for modules with production code. The dependency audit and release workflow are described in [CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages. This keeps the history readable and supports release-please versioning. Add tests with behavior changes, avoid plaintext or secret material in logs and fixtures, and document security or compatibility changes.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [DEPENDENCIES.md](DEPENDENCIES.md) for the current contribution and dependency policies.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
