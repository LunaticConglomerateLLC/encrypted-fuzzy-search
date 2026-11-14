# API map

This page points to the public API areas by task. Names below refer to packages under `io.github.lunaticconglomeratellc.encryptedfuzzysearch`.

| Need | Package and main types |
| --- | --- |
| Encrypt and decrypt field bytes | `crypto`: `AuthenticatedFieldCipher`, `InvalidCiphertextException` |
| Supply roots and derive scoped keys | `crypto`: `MasterKeyProvider`, `ScopedKeyDeriver` |
| Create exact or n-gram protected tokens | `crypto`: `ExactBlindIndexer`, `NgramBlindIndexer` |
| Normalize text and create language variants | `language`: `CanonicalTextNormalizer`, `TransliterationProfile`, `LanguageVariantProvider`, `Icu4jTransliterationProvider`, `KuromojiJapaneseVariantProvider` |
| Build a service and index values | `search`: `EncryptedFuzzySearchService`, `ProtectedIndexStrategy`, `SearchPolicy` |
| Express an accuracy contract | `search`: `SearchRequest`, `SearchAccuracyContract`, `SearchMode`, `SearchOutcome`, `CandidateCompleteness` |
| Supply aliases or language selections | `search`: `SearchExpansionOptions`, `SearchAlias`, `LanguageProviderSelection` |
| Change final scoring | `search`: `FinalScorer`, scorer implementations, `IsotonicScoreCalibrator` |
| Implement a storage adapter | `search`: `TokenIndexRepository`; `postgres`: `PostgresTokenIndexRepository` |
| Configure Spring beans | `spring`: `EfsAutoConfiguration` in `spring-boot-starter` |

Most applications should start with the service facade and repository rather than calling cryptographic primitives directly. The primitives are available for adapter authors and carefully reviewed integrations. Never store raw normalized search values or raw n-grams as a shortcut.

For code examples see [First search](../getting-started/quickstart.md), [Spring Boot](../guides/spring-boot.md), [multilingual search](../guides/multilingual.md), and the [test examples](https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search/tree/main/postgres-adapter/src/test/java/io/github/lunaticconglomeratellc/encryptedfuzzysearch/postgres).
