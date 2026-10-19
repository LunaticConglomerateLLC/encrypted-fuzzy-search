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

## PostgreSQL persistence

`postgres-adapter` stores ciphertext and transform metadata in `efs_encrypted_records`; opaque token bytes and strategy/version identifiers are stored in `efs_protected_tokens`. Every query explicitly scopes tenant, field, and key version. Record replacement and posting replacement occur in one transaction; record deletion cascades to its postings. The schema contains no plaintext value, normalized form, or raw-gram column. PostgreSQL does reveal the accepted tenant/field/key identifiers, record IDs, token frequency/overlap, candidate limits, and access patterns.

## Canonical text bytes

`language-core` transform `efs-text-nfc-utf8-v1` first validates Unicode scalar values, normalizes text to Unicode NFC, and encodes the result as UTF-8. It preserves case, whitespace, punctuation, compatibility characters, and transliteration; these are separate search transforms and must be versioned independently. Malformed UTF-16 containing unpaired surrogates is rejected instead of replacement-encoded. Shared compatibility vectors are in `language-core/src/test/resources/canonical-text-v1.tsv`; Java and Kotlin implementations should consume this same table. NFC canonical normalization keeps canonically equivalent strings byte-identical; compatibility folding such as NFKC is intentionally not part of v1.
