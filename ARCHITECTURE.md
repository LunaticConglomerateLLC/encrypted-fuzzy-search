# Architecture

## Module boundaries

```text
crypto-core ─┐
             ├──> search-core ───> postgres-adapter ───> spring-boot-starter (optional)
language-core┘
```

- `crypto-core` owns authenticated encryption, key separation, and protected-token primitives. It does not own text normalization or persistence.
- `language-core` owns deterministic, versioned normalization and language transforms. It does not own keys or storage.
- `search-core` composes protected index strategies with language transforms, candidate retrieval contracts, and authorized final scoring.
- `postgres-adapter` implements persistence and retrieval for the core contracts. It must preserve tenant and field scope.
- `spring-boot-starter` is optional integration and must not become a dependency of the framework-independent modules.

## Data flow

1. An authorized application obtains plaintext transiently and generates versioned search forms.
2. The application encrypts the field and derives keyed index tokens using isolated key scopes.
3. Persistence stores ciphertext and protected tokens, never plaintext grams or search forms.
4. A query follows the same versioned transform and uses protected tokens to retrieve candidates.
5. The authorized application decrypts only shortlisted records and performs final similarity scoring.

## Security and correctness boundaries

Keyed indexes are not oblivious. Depending on the selected index, observers may learn equality, frequency, overlap, candidate identifiers, access patterns, lengths, or version metadata. The accepted threat model applies only to the exact and n-gram strategies documented in [THREAT_MODEL.md](THREAT_MODEL.md); future strategies require their own leakage review. If zero leakage is required, stop before production search implementation.

Approximate retrieval reports empirical recall and does not claim exhaustive results. Only algorithms with a proved guarantee and stated limits may claim bounded completeness. Candidate limits and final similarity thresholds are separate controls.

Do not log plaintext values, transformed search forms, raw grams, protected tokens, or secrets.

## Field ciphertext format

`crypto-core` uses AES-256-GCM (`AES/GCM/NoPadding`) with a 96-bit nonce and a 128-bit authentication tag. The cipher checks the key size when JCA exposes encoded key material; non-exportable keys must be provisioned as 256-bit AES keys by the key provider. The binary v1 envelope is:

| Offset | Length | Contents |
| --- | --- | --- |
| 0 | 3 bytes | ASCII magic `EFS` |
| 3 | 1 byte | Format version `0x01` |
| 4 | 12 bytes | Fresh nonce |
| 16 | Remaining bytes | Ciphertext followed by the 16-byte GCM tag |

The four-byte magic/version header is authenticated as GCM associated data, along with caller-provided context. Callers should encode stable tenant, field, and record identity into that context using an unambiguous canonical encoding. Associated data is authenticated but not encrypted; the envelope does not store it. Text must be encoded by the caller (UTF-8 is the recommended default); the cipher API accepts bytes and supports arbitrary binary values.

Each encryption uses a fresh random nonce. GCM requires nonce uniqueness for every encryption under a key. With random 96-bit IVs, NIST SP 800-38D limits the total number of encryptions to at most 2^32 for a key across all instances. Applications/key providers must track this global per-key limit and rotate before reaching it; the stateless cipher API cannot enforce a count shared across processes. [NIST SP 800-38D, sections 8.2–8.3](https://csrc.nist.gov/pubs/sp/800/38/d/final). The Java API uses the standard AES/GCM transformation and supplies the IV and tag length through `GCMParameterSpec`; see [Java `Cipher`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/Cipher.html) and [GCMParameterSpec](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html).

Decryption rejects malformed, unsupported-version, or unauthenticated envelopes with the same `InvalidCiphertextException`. Applications must not expose distinguishable authentication errors to untrusted callers.

## Key derivation

`MasterKeyProvider` resolves an application-managed, case-sensitive rotation identifier to a root key. Version 1 requires an extractable `HmacSHA256` key containing at least 256 bits of secret entropy; non-exportable HSM keys need a provider that performs HKDF inside the HSM and are not supported by this SPI version. Implementations must not log root or derived key bytes.

`ScopedKeyDeriver` applies RFC 5869 HKDF-SHA-256 using a 32-byte all-zero salt (the RFC-defined default for absent salt). Its `info` is the ASCII label `encrypted-fuzzy-search/key-derivation`, context version byte `0x01`, purpose byte (`0x01` encryption or `0x02` blind index), then the key ID, tenant ID, and field ID as UTF-8 byte strings, each preceded by a 4-byte unsigned big-endian length. Identifiers are non-empty, opaque, case-sensitive, and are not normalized. Each call derives 32 bytes; encryption uses AES-256 and blind-index operations use HMAC-SHA-256. Purpose and scope are cryptographically separated; key IDs select rotation material and are also included in derivation context. [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869).

## Exact blind-index token

`ExactBlindIndexer` accepts already-normalized bytes and creates a deterministic token with the derived tenant/field-scoped index key. The token is `EFSI` magic (4 bytes), format version `0x01` (1 byte), and a 32-byte HMAC-SHA-256 over the header followed by the normalized bytes. The transform is case/normalization agnostic by design; callers must use the same versioned canonicalization contract for ingest and query. Equal byte strings under the same derived key match; cross-tenant, cross-field, or key-version tokens differ. Tokens leak equality and frequency and are not ciphertext. Store only the token, never the normalized source bytes.

## Protected n-gram token and fuzzy retrieval

The first fuzzy strategy uses NFC-normalized Unicode code points and separate bigram and trigram token namespaces. Each EFSN v1 token contains `EFSN` magic, a format-version byte, the configured strategy size, the actual partial-gram size, and a 32-byte HMAC-SHA-256 tag. This domain separation prevents exact-equality, bigram, and trigram tokens from being conflated. Repeated grams are deduplicated in first-occurrence order. A non-empty input shorter than the configured gram size emits one partial-gram token; empty input emits no n-gram tokens (the exact token still exists); inputs above 4,096 code points are rejected, never truncated.

N-gram indexes leak token equality/frequency and token overlap. Retrieval matches any shared protected token, counts overlap, ranks by overlap, and applies an operational candidate limit. The candidate limit may omit relevant records and is not a recall guarantee. Final verification decrypts only the shortlisted records under the caller-authorized tenant/field/key scope and computes case-sensitive normalized Levenshtein similarity over code points. The caller supplies both the candidate limit (maximum 1,000 in the core API) and minimum similarity; the library does not choose a universal product threshold. The caller remains responsible for authorizing the tenant and field before calling the service.

## Protected-index strategy SPI

`ProtectedIndexStrategy` defines the strategy namespace, token format version, required key scope, candidate match mode, known leakage metadata, and token generation contract. The current exact and n-gram strategies require an index key derived for the tenant, field, and key version. A strategy's stable ID and format version are persisted with every token; changing token generation or its meaning requires a new version or namespace and an explicit migration/reindex plan. Transform-profile IDs are part of profile-specific namespaces, so tokens generated for different transform versions do not collide in storage.

The current repository query plan uses `ANY_SHARED_TOKEN`: records matching at least one protected query token are returned, then overlap ranking and decrypted final scoring apply. A strategy that needs different candidate semantics cannot be plugged in as if it were equivalent; it requires an explicit query/repository contract change. Leakage metadata is a review aid, not a proof that a strategy is safe or complete. Any new strategy needs a separate leakage review and must document its false-match behavior, bounds, and operational limits before production use.

`ProtectedDeletionStrategy` is an opt-in candidate generator with radius one or two. It HMAC-protects the original canonical value and all unique code-point strings formed by deleting up to the configured radius. The default accepts at most 128 code points and 4,096 distinct variants; expansion beyond a configured bound fails instead of truncating. Radius is part of the namespace (for example, `efs-delete-r2`). The variant set is bounded and measurable, but can grow quadratically at radius two; use it only where the configured token budget is acceptable. Shared deletion variants may produce false candidates, so decrypted Levenshtein scoring remains the final verifier. This strategy leaks equality, frequency, and token overlap and does not change the candidate-limit recall caveat.

`ProtectedSoundexStrategy` is opt-in and intentionally limited to one ASCII A-Z word using the English Soundex mapping. Inputs with non-ASCII characters, spaces, punctuation, or digits safely produce no phonetic token; the strategy does not guess a language or transliterate. Distinct values can share a Soundex code (for example, `Robert` and `Rupert`), so these are only candidates and are filtered by the normal decrypted similarity check. The strategy leaks equality, frequency, and token overlap, plus these deliberate phonetic collisions.

`ProtectedPrefixStrategy` is opt-in and emits HMAC-protected prefixes by Unicode code-point length. Its default emits lengths 2 through 8, accepts at most 4,096 code points, and caps configured prefix length at 256. Values shorter than the minimum emit no prefix tokens. Shared prefixes intentionally collide and reveal prefix equality/relations, lengths in the configured range, token frequency, and overlap. Prefix candidates do not prove identity or semantic equivalence and must pass final similarity scoring.

## PostgreSQL persistence

`postgres-adapter` stores ciphertext and transform metadata in `efs_encrypted_records`; opaque token bytes and strategy/version identifiers are stored in `efs_protected_tokens`. Every query explicitly scopes tenant, field, and key version. Record replacement and posting replacement occur in one transaction; record deletion cascades to its postings. The schema contains no plaintext value, normalized form, or raw-gram column. PostgreSQL does reveal the accepted tenant/field/key identifiers, record IDs, token frequency/overlap, candidate limits, and access patterns.

## Canonical text bytes

`language-core` transform `efs-text-nfc-utf8-v1` first validates Unicode scalar values, normalizes text to Unicode NFC, and encodes the result as UTF-8. It preserves case, whitespace, punctuation, compatibility characters, and transliteration; these are separate search transforms and must be versioned independently. Malformed UTF-16 containing unpaired surrogates is rejected instead of replacement-encoded. Shared compatibility vectors are in `language-core/src/test/resources/canonical-text-v1.tsv`; Java and Kotlin implementations should consume this same table. NFC canonical normalization keeps canonically equivalent strings byte-identical; compatibility folding such as NFKC is intentionally not part of v1.

### Multilingual search variants

Multilingual indexing is opt-in per field: the caller supplies a supported language profile to both indexing and querying. The initial `zh-Hans` profile uses ICU4J 78.3 and retains the original text alongside the Han-to-Latin form and its tone-folded Latin form. It does not detect languages or translate meaning. Cyrillic, Greek, Japanese (`ja-JP`) and Arabic profiles are not supported yet and fail closed; do not apply the Chinese profile to text that is known to be Japanese merely because it contains Han characters.

Each form is bounded to 4,096 Unicode code points, at most four distinct forms are generated (original, transliterated, tone-folded, and whitespace-compacted), and the combined protected-token set is bounded to 4,096 tokens. This leaves room below the PostgreSQL adapter's 8,192-token query ceiling. Indexing and querying fail clearly on overflow; forms are never silently truncated. Each profile has an immutable transform ID, stored with the encrypted record and embedded in its token strategy namespace. Changing the provider data or transform behavior requires a new ID and reindexing the field; a query using another profile version is rejected when it encounters a candidate with that version.

Variant expansion intentionally increases searchable equivalences and leakage. In particular, tone-folded pinyin can merge different Chinese words: `吗` and `妈` both produce `ma`, so a romanized query can retrieve both. Transliteration is not translation (`你好` does not produce the English translation `hello`), and ICU's Han-to-Latin transform returns one reading rather than every possible reading. Decrypt-and-score verification removes candidates that only share n-grams, but cannot distinguish distinct words collapsed by transliteration; do not expose token matches or variant-level matches as proof of identity or semantic equivalence. `language-core/src/test/resources/multilingual-regression-corpus.tsv` records supported examples and current unsupported-language boundaries.
