# Encrypted Fuzzy Search

Encrypted Fuzzy Search (EFS) is a Java-first library for applications that need to search fields stored as ciphertext. It creates keyed search tokens so a database can find candidate records without storing plaintext search values or raw character grams. The application decrypts and checks those candidates before returning matches.

EFS is an early-stage project, and its APIs may change. Its first cryptographic foundation release was v0.1.0; later work has added protected fuzzy search, PostgreSQL integration, multilingual experiments, and performance reporting. See [ARCHITECTURE.md](ARCHITECTURE.md) for supported profiles, limits, leakage, and reindexing requirements.

## What the library is intended to provide

- Authenticated encryption for application fields.
- Keyed indexes for exact and fuzzy candidate retrieval.
- Authorized final verification and ranking in the application.
- Explicit exact, bounded-fuzzy, and approximate search contracts with completeness metadata.
- Optional caller-supplied aliases and explicitly selected transliteration providers; these are outside the production allowlist.
- Experimental final-scoring profiles for edit, token, and candidate-local ranking comparisons.
- Explicitly allowlisted isotonic score calibration with held-out evaluation metrics.
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

See [SPRING_BOOT.md](SPRING_BOOT.md) for Spring Boot auto-configuration and Java/Kotlin consumer examples.

The project targets Java 21 bytecode and CI checks it on JDK 21 and 25. The public API is Java-first, with Kotlin interoperability checked in CI. The core does not depend on an application framework.

## Build and test

Prerequisites: JDK 21 or newer and Docker Compose v2. Use the Gradle wrapper so builds use the repository-pinned Gradle version. The full test/check suite uses a disposable local PostgreSQL service:

```sh
docker compose -f compose.test.yaml up --detach --wait
./gradlew --no-daemon clean check
docker compose -f compose.test.yaml down --volumes --remove-orphans
./gradlew cyclonedxBom
```

The Compose database binds only to loopback, uses test-only credentials, and stores data in the disposable container layer. For a failed test run, stop and remove it with the same `docker compose ... down` command.

For focused feedback, run `./gradlew --no-daemon :postgres-adapter:test` for the adapter's unit tests or `./gradlew --no-daemon :postgres-adapter:functionalTest` for its PostgreSQL-backed suite. The functional task requires the Compose database to be running; `check` runs both suites and includes both in the adapter's coverage report.

Tests are required for implementation changes. The project target is at least 90% line coverage per module containing production sources.
CI checks formatting and Java compiler warnings, runs tests on JDK 21 and 25, and enforces the coverage threshold for modules with production code. The dependency audit and release workflow are described in [CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages. This keeps the history readable and supports release-please versioning. Add tests with behavior changes, avoid plaintext or secret material in logs and fixtures, and document security or compatibility changes.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [DEPENDENCIES.md](DEPENDENCIES.md) for the current contribution and dependency policies.
See [BENCHMARKING.md](BENCHMARKING.md) for the reproducible corpus and measurement methodology; performance and accuracy thresholds remain open until agreed from empirical baselines.
The [project documentation](https://wearelunatic.com/encrypted-fuzzy-search/) covers installation, usage, architecture, security limits, PostgreSQL integration, and benchmarks. The site publishes the latest merged-revision performance reports.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
