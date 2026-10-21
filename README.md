# Encrypted Fuzzy Search

Encrypted Fuzzy Search is an early-stage Java and Kotlin library project for searching encrypted application data. The goal is to support exact and approximate candidate retrieval without persisting plaintext search terms, while making the security and accuracy trade-offs visible to application teams.

The project has released v0.1.0, its cryptographic foundation. Current development adds protected fuzzy search and opt-in multilingual variants, initially for Simplified Chinese (`zh-Hans`) using transliteration rather than translation. APIs remain early-stage and may change; see [ARCHITECTURE.md](ARCHITECTURE.md) for supported profiles, limits, leakage, and reindexing requirements.

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

Prerequisites: JDK 21 or newer and Docker Compose v2. Use the Gradle wrapper so builds use the repository-pinned Gradle version. The full test/check suite uses a disposable local PostgreSQL service:

```sh
docker compose -f compose.test.yaml up --detach --wait
./gradlew --no-daemon clean check
docker compose -f compose.test.yaml down --volumes --remove-orphans
./gradlew cyclonedxBom
```

The Compose database binds only to loopback, uses test-only credentials, and stores data in the disposable container layer. For a failed test run, stop and remove it with the same `docker compose ... down` command.

Tests are required for implementation changes. The project target is at least 90% line coverage per module containing production sources.
CI checks formatting and Java compiler warnings, runs tests on JDK 21 and 25, and enforces the coverage threshold for modules with production code. The dependency audit and release workflow are described in [CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages. This keeps the history readable and supports release-please versioning. Add tests with behavior changes, avoid plaintext or secret material in logs and fixtures, and document security or compatibility changes.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [DEPENDENCIES.md](DEPENDENCIES.md) for the current contribution and dependency policies.
See [BENCHMARKING.md](BENCHMARKING.md) for the reproducible corpus and measurement methodology; performance and accuracy thresholds remain open until agreed from empirical baselines.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
