# Encrypted Fuzzy Search

Encrypted Fuzzy Search (EFS) is a Java-first library for Java and Kotlin applications that need to search fields stored as ciphertext. It creates keyed search tokens so PostgreSQL can find candidate records without storing plaintext search values or raw character grams.

The application still sees plaintext while it encrypts a value and while it verifies search candidates. The database also sees structure in the token index. EFS reduces what is stored in the database; it does not make searchable data invisible.

## What EFS does

- Encrypts field values with authenticated AES-GCM.
- Derives separate encryption and search-index keys for each key version, tenant, and field.
- Stores encrypted records and keyed exact or character n-gram tokens in PostgreSQL.
- Retrieves candidates, decrypts them in the application, and checks the selected match threshold.
- Reports whether candidate retrieval is exact, bounded by a stated edit distance, or best-effort.

## What EFS does not do

- It does not hide equality, token frequency, token overlap, candidate records, or query patterns from someone who can observe the index and its use.
- It does not replace application authentication, authorization, key custody, database access controls, or backup policy.
- Its fuzzy n-gram retrieval does not promise to return every relevant record. Candidate limits can truncate results.
- It does not publish stable Maven artifacts today. The repository currently builds the library from source.
- It does not provide a production allowlist for optional deletion, phonetic, prefix, alias, or multilingual search. Those features require explicit opt-in and separate leakage review.

## Start here

1. Read [whether EFS fits your use case](getting-started/index.md).
2. [Build EFS from source](getting-started/installation.md), then follow the [first-search example](getting-started/quickstart.md).
3. Review [search modes and their accuracy contracts](guides/search-modes.md) before choosing a query type.
4. Read the [security boundaries](security/index.md) before storing real data.

The [architecture guide](reference/architecture.md) describes how the modules fit together. The [benchmark guide](benchmarks/index.md) explains what the measurements include, and the [latest reports](benchmarks/latest.md) show the most recently published run.
