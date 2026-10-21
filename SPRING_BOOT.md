# Spring Boot integration

`spring-boot-starter` is an optional adapter. `search-core`, `crypto-core`, and `language-core` remain framework-independent. Spring Boot 4.1 auto-configuration creates a `PostgresTokenIndexRepository` when the application has a `DataSource`; it creates `EncryptedFuzzySearchService` only when a `MasterKeyProvider` and `TokenIndexRepository` are available. The application can replace any default bean by providing its own bean of that type.

The starter never generates or configures root key material. Load keys from the application's secret manager, KMS, or HSM-compatible provider and return them by opaque key ID. Do not put key bytes in application properties, source files, or logs.

## Java configuration

Add the starter to the application module and provide its key provider. The application should already configure its PostgreSQL `DataSource` using its normal Spring Boot configuration.

```java
@Configuration
class EfsKeyConfiguration {
  @Bean
  MasterKeyProvider efsMasterKeyProvider(KeyService keyService) {
    return keyService::getHmacSha256RootKey;
  }
}
```

Then inject `EncryptedFuzzySearchService` into the component that has already authorized the tenant and field:

```java
@Service
class CustomerLookup {
  private final EncryptedFuzzySearchService search;

  CustomerLookup(EncryptedFuzzySearchService search) {
    this.search = search;
  }

  List<FuzzySearchResult> find(String tenantId, String name) throws Exception {
    return search.search(tenantId, "name", "active-key-id", name, 100, 0.75);
  }
}
```

The starter does not initialize or upgrade database schema. Use the application's controlled schema-migration process, or explicitly call `TokenIndexRepository.initializeSchema()` for development/test setups.

## Kotlin consumer

Java APIs are callable directly from Kotlin. A Kotlin application provides the same key provider and can use the service without a wrapper API:

```kotlin
@Configuration
class EfsKeyConfiguration {
    @Bean
    fun efsMasterKeyProvider(keyService: KeyService): MasterKeyProvider =
        MasterKeyProvider { keyId -> keyService.getHmacSha256RootKey(keyId) }
}

@Service
class CustomerLookup(private val search: EncryptedFuzzySearchService) {
    fun find(tenantId: String, name: String): List<FuzzySearchResult> =
        search.search(tenantId, "name", "active-key-id", name, 100, 0.75)
}
```

The starter has no Kotlin runtime dependency; Kotlin support is exercised by a compiled Kotlin consumer integration test in the repository.

## Configuration boundaries

- A host `TokenIndexRepository` bean can replace the PostgreSQL adapter, which is useful for tests or another storage implementation.
- A host `EncryptedFuzzySearchService` bean can replace all default search composition, including protected-index strategies.
- Without a host `MasterKeyProvider`, no `ScopedKeyDeriver` or default search service is created. There is no insecure development key fallback.
- Database schema creation, tenant/field authorization, active key selection, and secret rotation remain host responsibilities.
