# Is EFS right for this application?

EFS is for Java or Kotlin services that need to keep searchable fields encrypted in their database and can accept some information leakage from a protected index. PostgreSQL is the first storage adapter. You can use the core search and cryptographic modules without Spring.

EFS may fit your application when:

- Your service controls the keys and is authorized to see the field values during writes and candidate verification.
- You can accept equality and frequency leakage from exact indexes and overlap leakage from n-gram indexes.
- Your query can tolerate a bounded candidate list and final verification in the application.
- You can measure recall, precision, candidate volume, and storage cost on a representative synthetic or approved dataset.

Choose a different design if a database observer must learn nothing about repeated values, searches, or which records are accessed. EFS does not hide access patterns or provide zero-leakage search. It also cannot protect plaintext from a compromised application process that has access to the keys.

## Current distribution status

The repository builds Gradle modules, but it does not publish them to Maven Central or another artifact registry. You cannot add EFS to an application as a released Maven dependency yet. For now, clone the repository and build it from source; see [Build from source](installation.md).

## Before a production evaluation

Read the [security boundaries](../security/index.md), choose an allowed strategy, and agree on a representative workload. The default production policy permits exact and n-gram indexes only. Optional strategies have different leakage and accuracy behavior. The benchmark numbers are synthetic measurements, not service guarantees.
