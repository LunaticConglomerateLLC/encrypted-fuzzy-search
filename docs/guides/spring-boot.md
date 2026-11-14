# Java, Kotlin, and Spring Boot

The search and cryptographic APIs are Java-first and do not require Spring. `spring-boot-starter` is an optional integration module. Spring Boot 4.1 auto-configuration creates a PostgreSQL repository when a `DataSource` exists, and creates the default search service only when both `MasterKeyProvider` and `TokenIndexRepository` beans are available.

The starter does not generate keys, initialize schemas, authorize tenants, or choose rotation state. Supply those from the host application.

## Provide keys from the application

```java
@Configuration
class EfsKeyConfiguration {
  @Bean
  MasterKeyProvider efsMasterKeyProvider(KeyService keyService) {
    return keyService::getHmacSha256RootKey;
  }
}
```

`KeyService` stands for the application's KMS or secret-manager client. The provider must return an extractable 256-bit `HmacSHA256` root key for the requested key ID. Do not put key material in `application.yml`, environment templates, fixtures, or logs.

## Inject the service

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

The Kotlin API uses the same Java service:

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

The starter has no Kotlin runtime dependency. The repository compiles a Kotlin consumer integration test to check Java/Kotlin interoperability. For the default Spring beans, override one or more with application-provided beans when using another repository or search policy.

## Database setup and authorization

Spring Boot configures the `DataSource`; EFS uses it but does not manage schema migrations. Use the application's normal migration tool. `TokenIndexRepository.initializeSchema()` is intended for local development or tests, not as an unreviewed production startup action.

Authenticate and authorize the caller before searching. EFS scopes keys and database rows by tenant and field, but the library does not decide whether a caller may access those values. See [PostgreSQL integration](postgresql.md) and [security boundaries](../security/index.md).
