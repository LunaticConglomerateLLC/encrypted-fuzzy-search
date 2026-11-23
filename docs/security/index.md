# Security boundaries

EFS encrypts stored field values and uses keyed tokens to find search candidates. Those tokens still reveal patterns about the data and queries. EFS does not hide access patterns or promise zero leakage.

## What the database can observe

Someone who can inspect the database or observe its queries may learn:

| Strategy | Persisted leakage |
| --- | --- |
| Exact keyed tokens | Equality of repeated values within the same key, tenant, and field scope; token frequency and posting-list sizes. |
| Bigram/trigram tokens | Token equality and frequency, which grams co-occur, overlap patterns, and posting-list sizes. |
| Any strategy | Record counts and identifiers, tenant/field partitions, key and transform versions, token volume, and potentially query, access, timing, and result-size patterns. |

Fuzzy search creates several related tokens for each value. This can reveal more patterns than an exact-only index, especially when the observer has other information about the data. Keyed tokens are not ciphertext: they still reveal equality and overlap.

## What stays in application memory

The application handles plaintext while it normalizes and indexes a value. During a search, it decrypts the candidate records and checks which ones match. Anyone who compromises an application process that has the keys or plaintext may read or misuse that data. EFS does not protect data from a compromised service that is authorized to use it.

## Responsibilities of the host application

- Authenticate and authorize each caller before reading or searching a tenant and field.
- Keep root keys in a managed key service and limit which processes and operators can access them.
- Use stable, unambiguous tenant, field, and record identifiers in the authenticated encryption context.
- Protect PostgreSQL, backups, replicas, logs, and telemetry as sensitive data stores.
- Avoid logging plaintext, queries, normalized forms, transliterations, raw grams, protected tokens, or keys.
- Plan rotation, reindexing, backups, retention, and recovery outside the library.

The default production policy permits exact and n-gram strategies. Deletion-neighborhood, Soundex, prefix, alias, and multilingual expansion are opt-in and outside the accepted production allowlist. Do not enable them in production without an owner-approved leakage review and workload evaluation.

EFS does not provide forward privacy, backward privacy, access-pattern hiding, ORAM, padding, secure deletion from every copy, or a security certification. For threat scenarios and per-strategy leakage detail, read the [source threat model](https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search/blob/main/THREAT_MODEL.md).
