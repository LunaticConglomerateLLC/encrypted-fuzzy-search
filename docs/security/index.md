# Security boundaries

EFS protects stored field values with authenticated encryption and uses keyed tokens for candidate search. The design leaves observable structure in the searchable index. It is not oblivious search and has no zero-leakage claim.

## What the database can observe

An observer with access to database contents or query execution may learn:

| Strategy | Persisted leakage |
| --- | --- |
| Exact keyed tokens | Equality of repeated values within the same key, tenant, and field scope; token frequency and posting-list sizes. |
| Bigram/trigram tokens | Token equality and frequency, which grams co-occur, overlap patterns, and posting-list sizes. |
| Any strategy | Record counts and identifiers, tenant/field partitions, key and transform versions, token volume, and potentially query, access, timing, and result-size patterns. |

Fuzzy search creates several correlated tokens per value, so an observer may infer more than with an exact-only index. Auxiliary information can make those patterns easier to interpret. Keyed tokens are not ciphertext and do not hide equality or overlap.

## What stays in application memory

Plaintext is available to the application while it normalizes and indexes a value. During search, the application decrypts shortlisted candidates and runs final verification. A compromised application process with access to keys or plaintext can read or misuse them. EFS does not protect data from a compromised authorized service.

## Responsibilities of the host application

- Authenticate and authorize each caller before reading or searching a tenant and field.
- Keep root keys in a managed key service and limit which processes and operators can access them.
- Use stable, unambiguous tenant, field, and record identifiers in the authenticated encryption context.
- Protect PostgreSQL, backups, replicas, logs, and telemetry as sensitive data stores.
- Avoid logging plaintext, queries, normalized forms, transliterations, raw grams, protected tokens, or keys.
- Plan rotation, reindexing, backups, retention, and recovery outside the library.

The default production policy permits exact and n-gram strategies. Deletion-neighborhood, Soundex, prefix, alias, and multilingual expansion are opt-in and outside the accepted production allowlist. Do not enable them in production without an owner-approved leakage review and workload evaluation.

EFS does not provide forward privacy, backward privacy, access-pattern hiding, ORAM, padding, secure deletion from every copy, or a security certification. For threat scenarios and per-strategy leakage detail, read the [source threat model](https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search/blob/main/THREAT_MODEL.md).
