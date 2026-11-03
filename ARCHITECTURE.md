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

Rotation lifecycle and the active write-key pointer are owned by the host application; the library and PostgreSQL adapter persist no rotation state. Records store one key version at a time. An atomic record upsert replaces the prior ciphertext and postings together, so migration does not dual-write two key versions for the same record. During staged migration, records not yet backfilled may remain under the old key while new or reindexed records use the new key. `searchAcrossKeyVersions` explicitly searches a caller-selected list of up to 16 key IDs, applies the candidate limit independently per version, decrypts and verifies matches, and deduplicates returned record IDs. Keep the old key available until backfill and rollback windows have closed. See [KEY_ROTATION.md](KEY_ROTATION.md) for the host-managed rollout and recovery procedure.

`ScopedKeyDeriver` applies RFC 5869 HKDF-SHA-256 using a 32-byte all-zero salt (the RFC-defined default for absent salt). Its `info` is the ASCII label `encrypted-fuzzy-search/key-derivation`, context version byte `0x01`, purpose byte (`0x01` encryption or `0x02` blind index), then the key ID, tenant ID, and field ID as UTF-8 byte strings, each preceded by a 4-byte unsigned big-endian length. Identifiers are non-empty, opaque, case-sensitive, and are not normalized. Each call derives 32 bytes; encryption uses AES-256 and blind-index operations use HMAC-SHA-256. Purpose and scope are cryptographically separated; key IDs select rotation material and are also included in derivation context. [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869).

## Exact blind-index token

`ExactBlindIndexer` accepts already-normalized bytes and creates a deterministic token with the derived tenant/field-scoped index key. The token is `EFSI` magic (4 bytes), format version `0x01` (1 byte), and a 32-byte HMAC-SHA-256 over the header followed by the normalized bytes. The transform is case/normalization agnostic by design; callers must use the same versioned canonicalization contract for ingest and query. Equal byte strings under the same derived key match; cross-tenant, cross-field, or key-version tokens differ. Tokens leak equality and frequency and are not ciphertext. Store only the token, never the normalized source bytes.

## Protected n-gram token and fuzzy retrieval

The first fuzzy strategy uses NFC-normalized Unicode code points and separate bigram and trigram token namespaces. Each EFSN v1 token contains `EFSN` magic, a format-version byte, the configured strategy size, the actual partial-gram size, and a 32-byte HMAC-SHA-256 tag. This domain separation prevents exact-equality, bigram, and trigram tokens from being conflated. Repeated grams are deduplicated in first-occurrence order. A non-empty input shorter than the configured gram size emits one partial-gram token; empty input emits no n-gram tokens (the exact token still exists); inputs above 4,096 code points are rejected, never truncated.

N-gram indexes leak token equality/frequency and token overlap. Retrieval matches any shared protected token, counts overlap, ranks by overlap, and applies an operational candidate limit. The candidate limit may omit relevant records and is not a recall guarantee. Final verification decrypts only the shortlisted records under the caller-authorized tenant/field/key scope and computes case-sensitive normalized Levenshtein similarity over code points. The caller supplies both the candidate limit (maximum 1,000 in the core API) and minimum similarity; the library does not choose a universal product threshold. The caller remains responsible for authorizing the tenant and field before calling the service.

## Protected-index strategy SPI

`ProtectedIndexStrategy` defines the strategy namespace, token format version, required key scope, candidate match mode, known leakage metadata, and token generation contract. The current exact and n-gram strategies require an index key derived for the tenant, field, and key version. A strategy's stable ID and format version are persisted with every token; changing token generation or its meaning requires a new version or namespace and an explicit migration/reindex plan. Transform-profile IDs are part of profile-specific namespaces, so tokens generated for different transform versions do not collide in storage.

The current repository query plan uses `ANY_SHARED_TOKEN`: records matching at least one protected query token are returned, then overlap ranking and decrypted final scoring apply. A strategy that needs different candidate semantics cannot be plugged in as if it were equivalent; it requires an explicit query/repository contract change. Leakage metadata is a review aid, not a proof that a strategy is safe or complete. Any new strategy needs a separate leakage review and must document its false-match behavior, bounds, and operational limits before production use.

### Accuracy contracts and strategy policy

`search(SearchRequest)` accepts an explicit `SearchAccuracyContract` and returns a `SearchOutcome`. `EXACT` queries only exact protected tokens and has an exact candidate guarantee unless the candidate limit is exceeded. `BOUNDED_FUZZY` selects the least-expansive configured strategy that proves the requested code-point Levenshtein bound; the deletion-neighborhood strategy provides this guarantee only when both indexed and query neighborhoods fit its configured input and token-count bounds. The final similarity threshold is an additional verifier, not the source of the candidate guarantee. `APPROXIMATE` uses the configured, policy-allowed strategies and always reports best-effort recall.

The service requests one extra candidate to detect candidate-limit overflow. `SearchOutcome.candidateLimitExceeded()` reports when additional candidates existed beyond the requested cap. `candidateRetrievalComplete()` is true only for an exact or bounded strategy when that cap did not exclude candidates; approximate mode never reports complete retrieval. Completeness describes candidate generation before the similarity verifier. The legacy overloads returning only a list remain available but do not carry this metadata; their candidate limit must not be interpreted as exhaustive.

Final scoring runs after the candidate set has been retrieved and decrypted for caller-authorized verification. The production policy allows only `levenshtein-codepoint-v1`; experimental Damerau-Levenshtein, directed weighted substitutions, pair-local weighted character n-grams, token-set Jaccard, candidate-local BM25, and Soft TF-IDF profiles require explicit scorer allowlisting. `SearchOutcome.scoringProfileId()` identifies the score used. Thresholds and scores are profile-specific and should not be compared across profiles as calibrated probabilities. Exact mode always uses Levenshtein equality semantics. Candidate-local ranking statistics are calculated only from the bounded, decrypted candidate set for that query; no plaintext corpus frequencies are persisted or logged. The candidate cap and retrieved-set composition can change those statistics and rankings.

Weighted substitution maps are explicit caller configuration with directed two-code-point keys and costs in `[0, 1]`; unconfigured substitutions cost `1`. Weighted n-gram uses pair-local frequency weights, not learned corpus IDF. Token-set scoring splits on non-letter/digit code points and folds case using the ROOT locale. The Damerau profile implements optimal-string-alignment distance and only models adjacent transpositions. Edit-distance experimental profiles cap each input at 512 code points. BM25/Soft TF-IDF cap the candidate set at 100,000 tokens; Soft TF-IDF also caps token length at 128 code points and fuzzy token comparisons at one million per query. These options are experimental ranking/verifier profiles; they do not improve protected-token candidate recall by themselves and do not change the production default.

Score calibration is a separate, explicit post-scorer transform. `IsotonicScoreCalibrator` requires at least 20 labeled scorer outputs, with at least five relevant and five non-relevant samples; fit it on a representative calibration split and evaluate on a distinct held-out split with `ScoreCalibrationEvaluator` (Brier score and equal-width expected calibration error). The fitted model contains only score thresholds and observed relevance rates, not plaintext or corpus term statistics. It is applied only when its model ID is explicitly allowlisted; production defaults disable calibration. When enabled, `minimumSimilarity` is applied to calibrated output and `FuzzySearchResult.similarity` holds that empirical estimate. Do not treat it as a probability guarantee without representative held-out evidence; calibration cannot correct candidate omissions caused by retrieval or candidate caps.

`SearchPolicy.productionDefault()` permits only `efs-exact`, `efs-ngram-2`, and `efs-ngram-3`, with the exact/n-gram leakage already accepted by the project owner. A service configured with custom strategies must supply a policy that explicitly allowlists each stable strategy ID and its leakage metadata. Strategies outside the production baseline—including deletion, Soundex, prefix, and multilingual profiles—remain disabled under the default policy. Policy checks happen before index/query strategy use; rejected strategies do not silently fall back to another mode.

Known aliases are transient, caller-supplied query expansions. Each `SearchAlias` has a bounded value and stable, non-sensitive origin/version identifiers. Alias values are normalized and converted to protected query tokens, never persisted; `SearchOutcome.expansions()` reports only provenance and does not contain alias text. Alias expansion requires `aliasesAllowed` in the service policy and is disabled by `productionDefault()`. Alias provenance (`KNOWN_ALIAS`) remains separate from generated language-provider provenance; callers may explicitly request both, in which case the selected language provider transforms each supplied query form. Aliases assert equivalence supplied by the caller, not by the library. Common aliases and mixed-script confusables can retrieve distinct entities, so aliases need entity-disjoint hard-negative evaluation and must not be treated as identity proof.

Language transforms are selected explicitly with a `LanguageProviderSelection` containing a configured provider ID and caller-supplied language tag. No automatic language detection occurs. `LanguageVariantProvider` must return deterministic, bounded variants, retain the original text, and declare an immutable transform ID; a transform change requires reindexing. Persisted transform namespaces combine provider ID and transform version so independent providers cannot collide; strategy-specific namespace limits are validated before indexing. The ICU4J provider emits transliteration, not translation. Provider ID, provider transform version, and language tag are exposed as non-sensitive expansion provenance. The default production policy allows no language provider for the new accuracy-contract API; opt-in requires explicit policy configuration and a separate leakage review.

The deletion-neighborhood guarantee follows from generating every code-point subsequence formed by deleting up to the configured radius: values at Levenshtein distance at most that radius share a subsequence token. This claim is limited to the same strategy namespace and transform, complete variant generation within configured bounds, and an unsaturated candidate cap. It does not imply that every returned candidate is within the bound; decrypted Levenshtein verification enforces the requested distance.

`ProtectedDeletionStrategy` is an opt-in candidate generator with radius one or two. It HMAC-protects the original canonical value and all unique code-point strings formed by deleting up to the configured radius. The default accepts at most 128 code points and 4,096 distinct variants; expansion beyond a configured bound fails instead of truncating. Radius is part of the namespace (for example, `efs-delete-r2`). The variant set is bounded and measurable, but can grow quadratically at radius two; use it only where the configured token budget is acceptable. Shared deletion variants may produce false candidates, so decrypted Levenshtein scoring remains the final verifier. This strategy leaks equality, frequency, and token overlap and does not change the candidate-limit recall caveat.

`ProtectedSoundexStrategy` is opt-in and intentionally limited to one ASCII A-Z word using the English Soundex mapping. Inputs with non-ASCII characters, spaces, punctuation, or digits safely produce no phonetic token; the strategy does not guess a language or transliterate. Distinct values can share a Soundex code (for example, `Robert` and `Rupert`), so these are only candidates and are filtered by the normal decrypted similarity check. The strategy leaks equality, frequency, and token overlap, plus these deliberate phonetic collisions.

`ProtectedPrefixStrategy` is opt-in and emits HMAC-protected prefixes by Unicode code-point length. Its default emits lengths 2 through 8, accepts at most 4,096 code points, and caps configured prefix length at 256. Values shorter than the minimum emit no prefix tokens. Shared prefixes intentionally collide and reveal prefix equality/relations, lengths in the configured range, token frequency, and overlap. Prefix candidates do not prove identity or semantic equivalence and must pass final similarity scoring.

## PostgreSQL persistence

`postgres-adapter` stores ciphertext and transform metadata in `efs_encrypted_records`; opaque token bytes and strategy/version identifiers are stored in `efs_protected_tokens`. Every query explicitly scopes tenant, field, and key version. Record replacement and posting replacement occur in one transaction; record deletion cascades to its postings. The schema contains no plaintext value, normalized form, or raw-gram column. PostgreSQL does reveal the accepted tenant/field/key identifiers, record IDs, token frequency/overlap, candidate limits, and access patterns.

## Canonical text bytes

`language-core` transform `efs-text-nfc-utf8-v1` first validates Unicode scalar values, normalizes text to Unicode NFC, and encodes the result as UTF-8. It preserves case, whitespace, punctuation, compatibility characters, and transliteration; these are separate search transforms and must be versioned independently. Malformed UTF-16 containing unpaired surrogates is rejected instead of replacement-encoded. Shared compatibility vectors are in `language-core/src/test/resources/canonical-text-v1.tsv`; Java and Kotlin implementations should consume this same table. NFC canonical normalization keeps canonically equivalent strings byte-identical; compatibility folding such as NFKC is intentionally not part of v1.

### Multilingual search variants

Multilingual indexing is opt-in per field: the caller supplies the same supported language profile to indexing and querying. Language is never detected automatically, and transliteration does not translate meaning. Current selections are:

| Provider | Explicit language tags | Behavior and limits |
| --- | --- | --- |
| `icu4j` (ICU4J 78.3) | `zh-Hans` | Han-to-Latin emits one reading; tone-folded and whitespace-compacted forms are added. It does not resolve Chinese polyphony from phrase context. |
| `icu4j` (ICU4J 78.3) | `ar-Arab` | Arabic script to Latin; this is script romanization, not dialect-aware Arabic morphology or pronunciation recovery. |
| `icu4j` (ICU4J 78.3) | `hi-Deva`, `bn-Beng`, `gu-Gujr`, `pa-Guru`, `ta-Taml`, `te-Telu`, `kn-Knda`, `ml-Mlym`, `or-Orya` | ICU script transforms to Latin. Indic output follows ICU's transliteration conventions and can retain diacritics; language/script tags must remain explicit. |
| `lucene-kuromoji` (Lucene 10.5.2) | `ja-JP` | Dictionary-based Japanese morphology yields one reading per analyzed token, then ICU4J 78.3 maps Katakana to Latin. Dictionary coverage and segmentation affect proper names; alternate readings are not exhaustively enumerated. |

The provider choice is intentionally narrow: use ICU for bounded, deterministic script transliteration; use Kuromoji where Japanese dictionary readings are needed. The [Pinyin4j API](https://pinyin4j.sourceforge.net/pinyin4j-doc/net/sourceforge/pinyin4j/PinyinHelper.html) exposes multiple readings for a single character, but does not itself resolve phrase context. Generating all character combinations would multiply forms and candidate volume, so it is not selected as a Chinese polyphone solution. Chinese context-sensitive readings remain an open evaluation item for a phrase-aware provider and held-out names corpus. For Arabic, CAMeL Tools provides morphology, but its [documented API is Python-based](https://camel-tools.readthedocs.io/en/stable/api/morphology/analyzer.html); it is not pulled into this Java library through a new service/runtime boundary. For Indic, the [Indic NLP Library](https://indic-nlp-library.readthedocs.io/en/latest/) also provides processing beyond transliteration but is Python-based; ICU already supplies the script conversion required for this milestone. Generic ICU transliteration is not a substitute for Arabic morphology or dialect-specific pronunciation. Cyrillic and Greek remain unsupported and fail closed. Script-specific profiles such as `pa-Guru` prevent language-only tags from silently selecting a potentially different script. See the [ICU transform guide](https://unicode-org.github.io/icu/userguide/transforms/general/) and [Lucene Kuromoji tokenizer API](https://lucene.apache.org/core/10_5_2/analysis/kuromoji/org/apache/lucene/analysis/ja/JapaneseTokenizer.html) for provider behavior and output attributes.

Each input and generated form is bounded to 4,096 Unicode code points, at most four distinct forms are generated, and the combined protected-token set is bounded to 4,096 tokens. This leaves room below the PostgreSQL adapter's 8,192-token query ceiling. Indexing and querying fail clearly on overflow; forms are never silently truncated. Each profile has an immutable transform ID, stored with the encrypted record and embedded in its token strategy namespace. Changing provider data, dictionaries, or transform behavior requires a new ID and reindexing the field; a query using another profile version is rejected when it encounters a candidate with that version.

Variant expansion intentionally increases searchable equivalences and leakage. Tone-folded pinyin can merge different Chinese words: `吗` and `妈` both produce `ma`. Arabic and Indic script romanization can also collapse spellings or differ from user-entered conventions. Japanese dictionary readings can be wrong for an out-of-vocabulary name. Decrypt-and-score verification removes candidates that only share n-grams, but cannot distinguish distinct source strings collapsed to the same generated form; never present a variant-level match as proof of identity or semantic equivalence. Keep all language providers outside the production default allowlist until leakage and accuracy are explicitly accepted. `language-core/src/test/resources/multilingual-regression-corpus.tsv` records synthetic examples and unsupported-language boundaries.
