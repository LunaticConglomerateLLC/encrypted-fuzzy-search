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

Keyed indexes are not oblivious. Depending on the selected index, observers may learn equality, frequency, overlap, candidate identifiers, access patterns, lengths, or version metadata. The threat model and accepted leakage remain subject to TASK-2 review. If zero leakage is required, stop before production search implementation.

Approximate retrieval reports empirical recall and does not claim exhaustive results. Only algorithms with a proved guarantee and stated limits may claim bounded completeness. Candidate limits and final similarity thresholds are separate controls.

Do not log plaintext values, transformed search forms, raw grams, protected tokens, or secrets.
