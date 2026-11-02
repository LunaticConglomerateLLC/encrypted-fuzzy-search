# Runnable benchmark example

From a clean checkout, run `./examples/benchmark/run.sh`. The script starts the disposable PostgreSQL Compose service, seeds generated records, runs the paired plaintext/EFS correctness and storage benchmark, runs a concurrent load profile, and stops Compose on exit (including failure). It uses the test-only key material and generated corpus in the benchmark source set. Console output contains aggregate measurements only.

Adjust the concurrent profile with these environment variables:

| Variable | Default | Meaning |
| --- | ---: | --- |
| `EFS_BENCHMARK_RECORDS` | 600 | Synthetic records to seed; two thirds are labeled positives |
| `EFS_BENCHMARK_CANDIDATE_LIMIT` | 100 | Maximum candidate set per query |
| `EFS_BENCHMARK_CONCURRENCY` | 4 | Fixed simultaneous Java workers |
| `EFS_BENCHMARK_DURATION_SECONDS` | 10 | Measured steady-state duration per mode |
| `EFS_BENCHMARK_WARMUP_SECONDS` | 2 | Warmup duration per mode, excluded from timed samples |
| `EFS_BENCHMARK_NO_MATCH_PERCENT` | 10 | Share of generated no-match searches |
| `EFS_BENCHMARK_REQUEST_TIMEOUT_MS` | 5000 | PostgreSQL statement timeout and reporting threshold |

Run concurrency 1, 4, 8, and 16 as separate invocations to inspect how the local database behaves as worker count grows. These local measurements are exploratory and do not establish an SLO or production capacity claim. Full parameter descriptions and interpretation limits are in [`../../BENCHMARKING.md`](../../BENCHMARKING.md).

## Service wiring examples

These snippets show how a Java or Kotlin application supplies root-key material and creates the service. They are illustrative; the runnable script uses a disposable test `DataSource` and generated keys. Replace the sample key provider with an application-managed secret provider. Do not use the generated example key for stored application data.

### Java

```java
byte[] testRootBytes = new byte[32];
new java.security.SecureRandom().nextBytes(testRootBytes);
javax.crypto.SecretKey testRootKey =
    new javax.crypto.spec.SecretKeySpec(testRootBytes, "HmacSHA256");
var keyDeriver = new ScopedKeyDeriver(keyId -> testRootKey);

var ngramIndexer = new NgramBlindIndexer();
var strategies = java.util.List.of(
    new ProtectedExactStrategy(new ExactBlindIndexer()),
    new ProtectedNgramStrategy(2, ngramIndexer),
    new ProtectedNgramStrategy(3, ngramIndexer));
var service = new EncryptedFuzzySearchService(
    keyDeriver,
    new AuthenticatedFieldCipher(),
    new CanonicalTextNormalizer(),
    strategies,
    new PostgresTokenIndexRepository(dataSource));

service.indexValue("tenant-a", "display-name", "record-1", "key-v1", generatedValue);
var outcome = service.search(SearchRequest.approximate(
    "tenant-a", "display-name", "key-v1", generatedTypo, 100, 0.80));
System.out.println("match_count=" + outcome.matches().size());
```

### Kotlin

```kotlin
val testRootBytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
val testRootKey = javax.crypto.spec.SecretKeySpec(testRootBytes, "HmacSHA256")
val keyDeriver = ScopedKeyDeriver { _ -> testRootKey }

val ngramIndexer = NgramBlindIndexer()
val strategies = listOf(
    ProtectedExactStrategy(ExactBlindIndexer()),
    ProtectedNgramStrategy(2, ngramIndexer),
    ProtectedNgramStrategy(3, ngramIndexer),
)
val service = EncryptedFuzzySearchService(
    keyDeriver,
    AuthenticatedFieldCipher(),
    CanonicalTextNormalizer(),
    strategies,
    PostgresTokenIndexRepository(dataSource),
)

service.indexValue("tenant-a", "display-name", "record-1", "key-v1", generatedValue)
val outcome = service.search(
    SearchRequest.approximate("tenant-a", "display-name", "key-v1", generatedTypo, 100, 0.80),
)
println("match_count=${outcome.matches().size}")
```

The production mode stores AES-GCM ciphertext and HMAC-protected postings. The benchmark-only `PLAINTEXT_CONTROL` stores canonical plaintext and raw n-grams in disposable local tables to isolate protection costs; it is synthetic-only and is not a production configuration. Neither example prints searched text, values, or tokens.
