# Threat model and leakage review

**Status:** the project owner accepts the exact and character n-gram baseline leakage profile and explicitly keeps the opt-in deletion, Soundex, prefix, and multilingual strategies outside the production allowlist. This is not a security certification.

## Scope and security objective

The design stores application fields as randomized authenticated ciphertext and supports candidate retrieval using deterministic keyed tokens derived from normalized search forms. The objective is to keep field plaintext, raw grams, and transformed search forms out of the database and its backups, while allowing an authorized application to retrieve and verify likely matches.

This is a leakage-bearing searchable index, not oblivious computation. It does not promise to hide equality, token frequency, token overlap, result sets, or query/access patterns from an observer that can inspect the index and its use. Fuzzy search exposes more structure than an exact-only blind index because a value can map to multiple correlated tokens.

In scope: field encryption and search-token generation, index persistence, query execution, database and backup exposure, application/key compromise, logs, operational metadata, and privileged insiders. Exact and character n-gram strategies are the accepted baseline. Deletion-neighborhood, phonetic, prefix, and multilingual strategies are implemented as explicit opt-ins; their leakage is documented here but they do not inherit the baseline's owner acceptance. LSH and other strategies remain exploratory.

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
2. **Application to key provider:** key material is obtained through a protected provider boundary, not stored alongside database data. `MasterKeyProvider`, tenant/field/purpose-separated derivation, and application-managed key rotation are implemented. Custody, deployment controls, and provider recovery remain host responsibilities.
3. **Application to database:** the application sends ciphertext and keyed tokens. The database is not trusted with plaintext or keys. Database operators can observe persisted records, token equality/frequency/overlap, identifiers, and query/access behavior available to their role.
4. **Application process:** authorized application code must see plaintext during ingest and final verification. A fully compromised process while keys or plaintext are available can therefore read or misuse them; field encryption does not protect against that compromise.
5. **Backups and replicas:** they are treated as database copies with equivalent leakage and retention risk. Encryption of backup media is useful defense in depth, not a remedy for leakage in an exposed keyed index.
6. **Logs and telemetry:** plaintext, raw/transformed search forms, grams, tokens, and secrets must not be logged. Identifiers, timings, request counts, result counts, and error patterns can still reveal operational information and should be access-controlled and minimized.

Assumptions: the AES-GCM envelope, HKDF-SHA-256 derivation, and HMAC-SHA-256 token formats described in `ARCHITECTURE.md` are implemented correctly; TLS and deployment access controls are configured by the host application; and a database-only attacker does not also possess the index key.

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

The following describes what a database/search observer may learn from the current schema, query plan, strategy implementation, and caller-visible behavior. Adapter or workload changes can alter these observations and should trigger review.

| Strategy | Persisted leakage | Query-time / repeated-query leakage | Notes |
| --- | --- | --- | --- |
| Exact keyed blind index | Equality of the same scoped normalized value across records; token frequency and posting-list size; record/tenant/field scope and token/key/transform version metadata if stored | Repeated query-token equality (search pattern); matching record identifiers and result count (access pattern); timing and response size | A keyed token prevents a database-only observer without the key from directly computing tokens for arbitrary guesses, but equality and frequency remain visible. Scope separation prevents cross-domain equality only if correctly implemented. |
| Character n-gram keyed index (bigram/trigram) | Equality and frequency of each gram token; number/distribution of tokens per value; co-occurrence/overlap of tokens within a record; posting-list sizes; identifiers and version metadata | Query token set and repeated token equality; intersections/overlap, candidate identifiers, candidate/result counts and ordering if exposed; latency and response sizes | A collection of individually keyed tokens still leaks structure through which tokens co-occur and overlap. Short values, common names, and skewed language distributions may be especially distinguishable. |
| Bounded deletion-neighborhood (implemented, opt-in) | Equality and frequency of original/deletion variants; correlated variants co-occurring in a record; token count/volume and posting-list sizes | Query-variant equality/overlap, candidate identifiers/counts, and repeated patterns | Radius 1 or 2, input length and token-count bounds are enforced. Expansion can be quadratic at radius 2. False candidates still require decrypt-and-score verification. Explicitly excluded from the production allowlist by the project owner. |
| Transliteration and language variants (implemented for explicit `zh-Hans` profile) | Equality/frequency and co-occurrence across original, Han-Latin, tone-folded, and compact forms; profile/version metadata can reveal the configured transformation | Expanded query-token equality and overlap, candidate/match sets, and repeated variant patterns; output can reveal which variants matched if returned | Tone folding and compact forms merge distinct inputs (for example, `吗` and `妈` both produce `ma`). This is transliteration, not translation; ICU returns one Han reading, not every reading. Use only with caller-supplied language metadata, preserve original text, enforce documented bounds, and review reindexing when transform ID changes. Explicitly excluded from the production allowlist by the project owner. |
| English ASCII Soundex (implemented, opt-in) | Equality/frequency of phonetic codes, code collisions, and token co-occurrence | Candidate IDs/counts and repeated phonetic query patterns | Distinct names intentionally collide (for example, `Robert` and `Rupert`); only one ASCII A-Z word is encoded, revealing the Soundex class for supported input. Explicitly excluded from the production allowlist by the project owner. |
| Protected prefixes (implemented, opt-in) | Equality/frequency and relations between prefixes at configured lengths; token count can reveal lengths in that range | Candidate IDs/counts, prefix-query repetition, and prefix overlap | Default prefix lengths are 2 through 8 code points; shared prefixes intentionally collide. Explicitly excluded from the production allowlist by the project owner. |
| LSH / ANN / other approximate strategy (future) | Bucket/signature equality, distribution, collision and volume patterns, plus index metadata | Bucket probes, candidate/access patterns, result volumes, and timing | No privacy or leakage claim is made; assess separately before selection. |

All strategies may additionally leak stable row identifiers, tenant/field partitioning, ciphertext and token lengths, record counts, insert/delete/update cadence, key/algorithm/normalization versions, and observable query frequency. Even if direct query tokens are not persisted, deterministic equality and result correlations across observations can expose a search pattern. Candidate IDs and result sizes can support inference when combined with auxiliary data. Padding, batching, or access-pattern defenses are not currently part of the design and must not be implied.

The literature on searchable encryption treats leakage as an explicit part of the security definition and documents inference/leakage-abuse attacks that combine observed patterns with auxiliary information. This is why “encrypted token” must not be presented as “no information leaked.” See [Cash, Grubbs, Perry, and Ristenpart, *Leakage-Abuse Attacks Against Searchable Encryption* (IACR ePrint 2016/718)](https://eprint.iacr.org/2016/718) and [Oya and Kerschbaum, *Hiding the Access Pattern is Not Enough: Exploiting Search Pattern Leakage in Searchable Encryption* (USENIX Security 2021)](https://www.usenix.org/conference/usenixsecurity21/presentation/oya).

## Mitigations and residual risks

Controls implemented in the library or required from the host application:

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

## Security decision and acceptance

**Accepted direction:** authenticated field encryption and keyed exact and character n-gram indexes may be used with the equality, frequency, overlap, candidate/access-pattern, and operational-metadata leakage described above. The project owner has explicitly decided that optional deletion, Soundex, prefix, and multilingual strategies remain outside the production allowlist. This does not imply a security certification.

**Decision status: ACCEPTED by the project owner.** Implementations may proceed within this bounded leakage profile. If a deployment requires zero leakage to the database/search service, revisit the architecture (including the performance and operational trade-offs of oblivious/private-search approaches). This document makes no production security guarantee.

| Review item | Current state |
| --- | --- |
| Threat model covers DB dump, query oracle, service compromise, and backups | Drafted above |
| Leakage of exact and n-gram strategies documented | Drafted above and owner-accepted |
| Leakage of implemented optional deletion, Soundex, prefix, and multilingual strategies documented | Drafted above; owner explicitly excludes them from the production allowlist |
| Mitigations and residual risks distinguished by attacker capability | Drafted above |
| Owner acceptance of exact/n-gram baseline leakage | **Accepted** |
| Owner decision on optional strategies | **Accepted exclusion from production allowlist** |
| Production cryptographic implementation gate | **Open for implementations within the accepted scope** |

Security behavior introduced by implementation must include automated tests and receive review appropriate to its risk.
