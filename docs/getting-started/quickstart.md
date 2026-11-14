# First search

This quickstart shows the service calls used by the PostgreSQL integration tests. It assumes the database is running and the application has already configured a JDBC `DataSource`.

## Create the service

The service needs a root-key provider, a PostgreSQL repository, and the standard exact, bigram, and trigram index strategies. Use a secret manager or KMS-backed provider in an application. The fixed key below is for a disposable local example only.

```java
KeyGenerator localKeyGenerator = KeyGenerator.getInstance("HmacSHA256");
localKeyGenerator.init(256);
SecretKey localOnlyRootKey = localKeyGenerator.generateKey();
MasterKeyProvider masterKeys = keyId -> localOnlyRootKey;

ScopedKeyDeriver keyDeriver = new ScopedKeyDeriver(masterKeys);
PostgresTokenIndexRepository repository =
    new PostgresTokenIndexRepository(dataSource);
repository.initializeSchema(); // Development/test convenience; use migrations in production.

EncryptedFuzzySearchService search = new EncryptedFuzzySearchService(
    keyDeriver,
    new AuthenticatedFieldCipher(),
    new ExactBlindIndexer(),
    new CanonicalTextNormalizer(),
    new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
    new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
    repository);
```

The provider must return a 256-bit `HmacSHA256` root key for the supplied key ID. EFS derives different AES and HMAC keys for each tenant and field. Production keys must come from the host application's managed key service, not a constant or application property.

## Index and query

```java
search.indexValue("tenant-17", "customer-name", "record-42", "key-v1", "Alice Smith");

List<FuzzySearchResult> matches = search.search(
    "tenant-17", "customer-name", "key-v1", "Alic Smith", 100, 0.75);
```

The five scope values must agree between indexing and searching. The final argument is the minimum normalized similarity, between `0.0` and `1.0`; the preceding value limits the number of candidates to verify. The convenience `search` call uses approximate candidate retrieval. Inspect [search modes](../guides/search-modes.md) before relying on it for a completeness guarantee.

For canonical equality, use the explicit exact contract. It requires a threshold of `1.0` and still depends on the candidate limit including the full posting list:

```java
SearchOutcome exact = search.search(
    SearchRequest.exact("tenant-17", "customer-name", "key-v1", "Alice Smith", 100));
```

For the accuracy-contract API, create a `SearchRequest` and inspect the returned `SearchOutcome`:

```java
SearchOutcome outcome = search.search(
    SearchRequest.approximate(
        "tenant-17", "customer-name", "key-v1", "Alic Smith", 100, 0.75));

if (outcome.candidateLimitExceeded()) {
  // The configured candidate limit cut off retrieval; treat recall as uncertain.
}
for (FuzzySearchResult match : outcome.matches()) {
  System.out.println(match.recordId());
}
```

Authorize the caller for the tenant and field before constructing the request. Avoid logging the query or returned plaintext. `SearchRequest.toString()` redacts the query and expansion details, but applications should still treat all request data as sensitive.

Search calls can fail because of database errors, cryptographic errors, invalid text, or a policy that does not allow the requested strategy or expansion. Handle these at the application boundary and avoid logging request values or exception data that may contain sensitive details:

```java
try {
  SearchOutcome outcome = search.search(request);
  // Return only results the already-authorized caller may see.
} catch (SQLException | GeneralSecurityException | CharacterCodingException e) {
  // Map to the application's error handling and record a redacted diagnostic.
} catch (IllegalArgumentException e) {
  // Reject invalid input or a strategy disallowed by the configured policy.
}
```

Known aliases can be supplied for a single search, but they are disabled by the production default policy and remain outside the accepted production allowlist. Enabling them requires an explicit policy decision and a leakage review. Alias text is transient query input; it is not stored as a separate alias index:

```java
SearchAlias knownAlias = new SearchAlias("Robert", "approved-source", "v1");
SearchRequest aliasRequest = SearchRequest.exact(
    "tenant-17", "customer-name", "key-v1", "Bob", 100)
    .withExpansions(SearchExpansionOptions.aliases(List.of(knownAlias)));
// This request is rejected unless the service's SearchPolicy explicitly allows aliases.
```

These calls match the service and PostgreSQL integration tests in the repository. The functional suite starts from a clean schema, writes encrypted records and tokens, and checks positive, no-match, and cross-scope behavior.
