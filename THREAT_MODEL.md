# Threat model and leakage review

**Status:** review draft. This is not a security certification or a final acceptance of leakage. The product direction remains a working assumption until the owner reviews the decision at the end of this document.

## Scope and security objective

The design stores application fields as randomized authenticated ciphertext and supports candidate retrieval using deterministic keyed tokens derived from normalized search forms. The objective is to keep field plaintext, raw grams, and transformed search forms out of the database and its backups, while allowing an authorized application to retrieve and verify likely matches.

This is a leakage-bearing searchable index, not oblivious computation. It does not promise to hide equality, token frequency, token overlap, result sets, or query/access patterns from an observer that can inspect the index and its use. Fuzzy search exposes more structure than an exact-only blind index because a value can map to multiple correlated tokens.

In scope: field encryption and search-token generation, index persistence, query execution, database and backup exposure, application/key compromise, logs, operational metadata, and privileged insiders. This draft addresses the proposed exact and character n-gram strategies. Deletion-neighborhood, phonetic, prefix, LSH, and other strategies remain exploratory; each requires its own leakage review before adoption.

## Assets

| Asset | Security need |
| --- | --- |
| Original field values and candidate plaintext | Confidentiality and integrity; transient access only by authorized application code |
| Field ciphertext and authentication metadata | Confidentiality and tamper detection; metadata minimization |
| Encryption and index keys, master-key material | Confidentiality, separation of duties, rotation and recoverability |
| Normalized forms, transliterations, raw n-grams and query text | Keep out of persistence, telemetry, and logs |
| Keyed tokens, posting lists, record/tenant/field identifiers | Access control and tenant isolation; assume observable leakage as detailed below |
| Query/result timings, sizes, errors, and audit records | Minimize and restrict; may aid inference when combined with index observations |
| Backups, snapshots, replicas, exports, crash dumps | Same confidentiality and retention requirements as their source data |

## Trust boundaries and assumptions

1. **Caller to application:** callers are authenticated and authorized before query or write operations; input is treated as untrusted. The library cannot establish caller authorization by itself.
2. **Application to key provider:** key material is obtained through a protected provider boundary, not stored alongside database data. Exact SPI, custody, and rotation mechanisms are still design decisions. Per-tenant, per-field, and per-purpose derivation is intended but must be verified in implementation and tests.
3. **Application to database:** the application sends ciphertext and keyed tokens. The database is not trusted with plaintext or keys. Database operators can observe persisted records, token equality/frequency/overlap, identifiers, and query/access behavior available to their role.
4. **Application process:** authorized application code must see plaintext during ingest and final verification. A fully compromised process while keys or plaintext are available can therefore read or misuse them; field encryption does not protect against that compromise.
5. **Backups and replicas:** they are treated as database copies with equivalent leakage and retention risk. Encryption of backup media is useful defense in depth, not a remedy for leakage in an exposed keyed index.
6. **Logs and telemetry:** plaintext, raw/transformed search forms, grams, tokens, and secrets must not be logged. Identifiers, timings, request counts, result counts, and error patterns can still reveal operational information and should be access-controlled and minimized.

Assumptions: authenticated encryption is used with correct nonce handling; index tokens are keyed with a secure MAC/PRF; TLS and deployment access controls are configured by the host application; and a database-only attacker does not also possess the index key. Algorithm selection and formats are intentionally not fixed by this threat-model draft.

## Adversaries and scenarios

| Scenario | Attacker capability | Expected protection and limits |
| --- | --- | --- |
| Stolen database dump or read-only DB account | Read ciphertext, token values, schema, identifiers, versions, and stored metadata; perform offline analysis and compare records | Without keys, randomized authenticated ciphertext should not reveal field plaintext. The attacker still sees the persisted token leakage below and can use auxiliary knowledge/statistics to infer likely values. |
| Backup/snapshot/export theft | Same as a database dump, potentially with older data and retired token/key versions | Same leakage as live storage; old copies can preserve tokens after rotation or deletion. Retention and destruction must include replicas and backups. |
| Query oracle / chosen-query user | Submit selected queries through an authorized interface and observe results, errors, counts, or timing; possibly repeat or adapt queries | Authorization, rate limits, scoped access, and response minimization can limit collection. Repeated or chosen queries can correlate query tokens and result sets; the database/search operator may additionally see token-level query patterns. |
| Compromised application/service process | Read process memory, requests, plaintext, active keys, tokens, and candidate results; possibly issue arbitrary queries or modify records | No confidentiality guarantee for data currently handled by the compromised process. Contain blast radius with least privilege, short-lived/scoped key access, monitoring, isolation, and recovery/rotation. |
| Malicious or over-privileged insider | Depending on role: query logs, backups, database contents, application access, or key-provider access | Separation of duties, audited access, approvals, and least privilege reduce exposure. An insider with both application/key access and target data can recover plaintext; policy controls cannot cryptographically prevent an authorized decryptor from doing so. |
| Network observer | Observe traffic not protected by transport security, or metadata visible at endpoints | TLS is required in deployment. TLS does not hide request patterns, sizes, or timing from an endpoint or compromised network terminator. |

Out of scope for the library alone: host compromise, malicious runtime/JVM, physical side channels, denial of service, secure deletion from all media, and the correctness of an application’s authentication, authorization, backup, key-management, or deployment configuration. Integrators must assess these in their deployment threat model.

## Leakage profile by index strategy

The following describes what a database/search observer may learn from storage and query behavior. Exact leakage depends on eventual schema, query plan, batching, and adapter; implementation must refine this table before release.

| Strategy | Persisted leakage | Query-time / repeated-query leakage | Notes |
| --- | --- | --- | --- |
| Exact keyed blind index | Equality of the same scoped normalized value across records; token frequency and posting-list size; record/tenant/field scope and token/key/transform version metadata if stored | Repeated query-token equality (search pattern); matching record identifiers and result count (access pattern); timing and response size | A keyed token prevents a database-only observer without the key from directly computing tokens for arbitrary guesses, but equality and frequency remain visible. Scope separation prevents cross-domain equality only if correctly implemented. |
| Character n-gram keyed index (bigram/trigram) | Equality and frequency of each gram token; number/distribution of tokens per value; co-occurrence/overlap of tokens within a record; posting-list sizes; identifiers and version metadata | Query token set and repeated token equality; intersections/overlap, candidate identifiers, candidate/result counts and ordering if exposed; latency and response sizes | A collection of individually keyed tokens still leaks structure through which tokens co-occur and overlap. Short values, common names, and skewed language distributions may be especially distinguishable. |
| Bounded deletion-neighborhood (future) | Potentially many correlated variant tokens per value, their frequencies/co-occurrence, volume, and posting-list sizes | Query variants and their overlap, candidate IDs/counts, and repeated patterns | Not approved by this document; review token expansion, candidate limits, and leakage before implementation. |
| Phonetic, prefix, aliases, transliteration, or language variants (future) | Equality/frequency and co-occurrence of normalized variants; version/language/provider metadata may identify transformation choices | Variant-level query repetition and match/candidate sets; output can reveal which variants matched if returned | These transformations intentionally merge or expand values and may make identity or language attributes easier to infer. Require strategy-specific review. |
| LSH / ANN / other approximate strategy (future) | Bucket/signature equality, distribution, collision and volume patterns, plus index metadata | Bucket probes, candidate/access patterns, result volumes, and timing | No privacy or leakage claim is made; assess separately before selection. |

All strategies may additionally leak stable row identifiers, tenant/field partitioning, ciphertext and token lengths, record counts, insert/delete/update cadence, key/algorithm/normalization versions, and observable query frequency. Even if direct query tokens are not persisted, deterministic equality and result correlations across observations can expose a search pattern. Candidate IDs and result sizes can support inference when combined with auxiliary data. Padding, batching, or access-pattern defenses are not currently part of the design and must not be implied.

The literature on searchable encryption treats leakage as an explicit part of the security definition and documents inference/leakage-abuse attacks that combine observed patterns with auxiliary information. This is why “encrypted token” must not be presented as “no information leaked.” See [Cash, Grubbs, Perry, and Ristenpart, *Leakage-Abuse Attacks Against Searchable Encryption* (IACR ePrint 2016/718)](https://eprint.iacr.org/2016/718) and [Oya and Kerschbaum, *Hiding the Access Pattern is Not Enough: Exploiting Search Pattern Leakage in Searchable Encryption* (USENIX Security 2021)](https://www.usenix.org/conference/usenixsecurity21/presentation/oya).

## Mitigations and residual risks

Planned controls (to be verified as implementation work proceeds):

- Use randomized authenticated encryption for stored field values; reject tampered ciphertext during decryption.
- Derive independent keys/tokens by purpose and scope (at least tenant, field, and version); never use raw plaintext hashes as tokens. Key separation does not hide within-scope equality.
- Keep keys outside the database and separate key access from database administration. Limit key-provider access and support planned rotation/reindexing without silently accepting mixed scope.
- Bind encryption context to stable tenant/field/record context where the format permits, to prevent ciphertext substitution across contexts.
- Enforce tenant and field scope in every adapter query and in final authorization checks; test cross-tenant isolation.
- Bound query variants, posting-list expansion, candidate counts, and work; rate-limit query-oracle access and avoid detailed errors that disclose match behavior.
- Perform final similarity verification only after authorization and candidate retrieval; do not return tokens, normalized forms, or unnecessary candidate data to callers.
- Do not emit plaintext, normalized forms, raw grams, tokens, or secrets to logs, traces, metrics, test reports, or exception messages. Minimize/aggregate query metadata and restrict operational access.
- Protect transport, database roles, backup media, and operational access; apply retention/deletion and rotation policies to replicas and backups too.
- Document residual leakage per selected strategy and evaluate recall/latency/storage together with privacy before changing or adding an index strategy.

Residual risk remains significant for an observer of the index or repeated searches. Keyed token equality/overlap and frequency can enable statistical inference; query patterns, result identifiers/counts, lengths, and timing can add evidence. A compromised application with active keys can expose plaintext. This design does not provide forward privacy, backward privacy, access-pattern hiding, padding, oblivious RAM, or zero leakage unless separately designed, implemented, and reviewed.

## Security decision — owner review required

**Working direction:** continue exploring authenticated field encryption with keyed exact and n-gram indexes, accepting that equality, frequency, overlap, candidate/access patterns, and operational metadata may be exposed as described above. This restates the project’s current vision; it is not a formal risk acceptance.

**Decision status: PENDING explicit owner acceptance.** This draft alone does not constitute acceptance. The owner must confirm that this bounded leakage is acceptable for the intended data and deployment, or identify additional constraints. If zero leakage to the database/search service is required, block production cryptographic/search implementation and revisit the architecture (including the performance and operational trade-offs of oblivious/private-search approaches). No production security guarantee is claimed either way by this draft.

| Review item | Current state |
| --- | --- |
| Threat model covers DB dump, query oracle, service compromise, and backups | Drafted above |
| Leakage of planned exact and n-gram strategies documented | Drafted above; future strategies explicitly require separate review |
| Mitigations and residual risks distinguished by attacker capability | Drafted above |
| Owner acceptance of this leakage profile | **Pending** |
| Production cryptographic implementation gate | **Blocked until explicit acceptance is recorded** |

Security behavior introduced by implementation must include automated tests and receive review appropriate to its risk.
