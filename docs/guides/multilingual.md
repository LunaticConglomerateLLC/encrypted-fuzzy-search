# Multilingual search

Language variants are explicit transforms, not automatic language detection or translation. The caller supplies a language tag and chooses a supported provider. Transform identifiers are versioned because provider data can change; changing a transform may require reindexing values before queries use the new transform.

The code currently includes ICU4J profiles for Simplified Chinese (`zh-Hans`), Arabic (`ar-Arab`), and ten Indic script tags, plus a Kuromoji Japanese reading provider (`ja-JP`). These providers are experimental and are outside the owner-accepted production allowlist. The default production policy disables language providers and aliases.

## Example: Simplified Chinese transform

```java
TransliterationProfile profile =
    TransliterationProfile.forLanguageTag("zh-Hans");

service.indexValue(
    "tenant-17", "name", "record-42", "key-v1", "重庆银行", profile);

List<FuzzySearchResult> matches = service.search(
    "tenant-17", "name", "key-v1", "chong qing yin hang", 100, 1.0, profile);
```

This uses the selected ICU profile for both indexing and querying. ICU transliterates to one reading; it does not enumerate every possible Chinese pronunciation. Tone folding and compact forms can merge distinct source values. Preserve original-script data and authorize any deployment-specific use after reviewing its measured false positives and leakage.

Japanese uses the `lucene-kuromoji` provider and an explicit `LanguageProviderSelection`. The PostgreSQL functional suite exercises all 12 configured language tags with synthetic examples. See the [multilingual benchmark report](../benchmarks/latest.md) for transform-only throughput and latency. Those component measurements exclude database retrieval, decryption, and final scoring.
