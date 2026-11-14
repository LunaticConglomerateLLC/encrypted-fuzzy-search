# Configuration and limits

The API rejects invalid sizes instead of silently truncating input. The values below are enforced in the current code; they are not recommendations for every workload.

| Limit or default | Current behavior |
| --- | --- |
| Java bytecode | Java 21 target; tested on JDK 21 and 25. |
| Search candidate limit | 1 to 1,000 in `EncryptedFuzzySearchService`. The adapter supports larger internal query limits, but the public service applies its own bound. |
| Key versions in one migration search | At most 16 caller-selected key IDs. The candidate limit applies separately for each version. |
| Canonical text input | At most 4,096 Unicode code points in the normalizer and language providers. Oversized values are rejected. |
| Multilingual protected tokens | At most 4,096 distinct tokens across generated forms per indexed value or query. |
| ICU language forms | At most four distinct forms; the provider emits original, transliterated, optionally tone-folded, and compact forms when distinct. |
| Japanese provider forms | At most four distinct original, reading, Latin, and compact forms. |
| Score and similarity thresholds | Finite value from 0.0 to 1.0. Exact mode requires 1.0. |
| Root key | At least 256 bits with `HmacSHA256`; the current HKDF implementation requires extractable key bytes. |
| AES key | 256-bit AES for the current AES-GCM envelope. |

The default production strategy allowlist contains exact equality and bigram/trigram token strategies. It disables aliases and language providers. Experimental scorers, calibrated scoring, prefix, Soundex, deletion, and language expansions are not made safe by this table; configure them only after reviewing their specific policy and leakage.

The 96-bit random GCM nonce has a global per-key usage limit. The stateless cipher cannot count use across processes; the host must track its usage and rotate before the accepted bound. See the [architecture details](https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search/blob/main/ARCHITECTURE.md) and [key rotation guide](../guides/key-rotation.md).
