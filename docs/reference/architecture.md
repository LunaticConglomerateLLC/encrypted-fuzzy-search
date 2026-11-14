# Architecture and modules

EFS is a Java 21 multi-module Gradle project. The core modules have separate responsibilities; the PostgreSQL and Spring integrations sit on top of them.

```text
crypto-core ─┐
             ├── search-core ── postgres-adapter ── spring-boot-starter (optional)
language-core┘
```

| Module | Responsibility | External application dependency? |
| --- | --- | --- |
| `crypto-core` | AES-GCM field encryption, HKDF key derivation, and exact or n-gram token primitives. | Yes, through the library. |
| `language-core` | Unicode normalization and explicit, versioned language transforms. | Used by search; language provider dependencies apply when selected. |
| `search-core` | Protected strategies, planning, candidate contracts, final scoring, and service API. | Yes, through the library. |
| `postgres-adapter` | PostgreSQL repository for encrypted records and protected tokens. | Needed for PostgreSQL persistence. |
| `spring-boot-starter` | Optional Spring Boot bean configuration. | Only for Spring Boot applications. |
| `jmh-benchmarks` | Isolated JVM microbenchmarks. | No; benchmark-only. |

## Write and search path

1. The caller authorizes a write and supplies plaintext and a key ID.
2. EFS normalizes the text, generates protected tokens, encrypts the field, and stores ciphertext plus tokens.
3. A query repeats the configured transform and token generation under the same tenant, field, and key scope.
4. PostgreSQL returns a bounded candidate list from token matches.
5. EFS decrypts authorized candidates and scores them in application memory.
6. The caller receives matched results and, for the accuracy-contract API, metadata about candidate completeness and limit saturation.

The database never needs the root key to match tokens. The application must still protect the keys, plaintext, and query path.

## Compatibility

The build targets Java 21 bytecode and is checked on JDK 21 and 25. The API is Java-first and is compiled with a Kotlin consumer test. Transform IDs and token/ciphertext versions are persisted metadata; changing a transform or format can require reindexing or migration. The repository currently builds source but does not publish Maven artifacts. See [Build from source](../getting-started/installation.md).
