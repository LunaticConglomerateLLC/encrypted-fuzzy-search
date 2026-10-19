package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.InvalidCiphertextException;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class EncryptedFuzzySearchServiceTest {
  @Test
  public void indexesEncryptedValuesAndReturnsVerifiedTypoMatchesInStableOrder() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "alicia", "key-v1", "Alicia");
    service.indexValue("tenant-a", "name", "maria", "key-v1", "Maria");

    List<FuzzySearchResult> results =
        service.search("tenant-a", "name", "key-v1", "Alicia", 10, 0.5);

    assertEquals(results.size(), 2);
    assertEquals(results.get(0).recordId(), "alicia");
    assertEquals(results.get(0).value(), "Alicia");
    assertEquals(results.get(0).similarity(), 1.0);
    assertEquals(results.get(1).recordId(), "alice");
    assertTrue(results.get(1).similarity() >= 0.5);
    assertTrue(results.get(0).overlapCount() > 0);
  }

  @Test
  public void exactAndNoMatchQueriesHaveExplicitResults() throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    service.indexValue("tenant-a", "name", "one", "key-v1", "alice");

    List<FuzzySearchResult> exact = service.search("tenant-a", "name", "key-v1", "alice", 10, 1.0);
    List<FuzzySearchResult> noMatch =
        service.search("tenant-a", "name", "key-v1", "not-present", 10, 0.5);

    assertEquals(exact.size(), 1);
    assertEquals(exact.get(0).similarity(), 1.0);
    assertTrue(noMatch.isEmpty());
  }

  @Test
  public void normalizesCanonicalUnicodeBeforeEncryptionAndSearch() throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    service.indexValue("tenant-a", "name", "accented", "key-v1", "Cafe\u0301");

    List<FuzzySearchResult> results =
        service.search("tenant-a", "name", "key-v1", "Caf\u00e9", 10, 1.0);

    assertEquals(results.size(), 1);
    assertEquals(results.get(0).value(), "Caf\u00e9");
  }

  @Test
  public void appliesTenantAndFieldIsolationBeforeDecryptingCandidates() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "same-name", "key-v1", "alexander");
    service.indexValue("tenant-b", "name", "other-tenant", "key-v1", "alexander");
    service.indexValue("tenant-a", "email", "other-field", "key-v1", "alexander");

    List<FuzzySearchResult> results =
        service.search("tenant-a", "name", "key-v1", "alexander", 10, 1.0);

    assertEquals(results.size(), 1);
    assertEquals(results.get(0).recordId(), "same-name");
  }

  @Test
  public void updatesAndDeletesReplaceCiphertextAndTokens() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "record", "key-v1", "alice");
    service.indexValue("tenant-a", "name", "record", "key-v1", "bob");

    assertTrue(service.search("tenant-a", "name", "key-v1", "alice", 10, 0.5).isEmpty());
    assertEquals(
        service.search("tenant-a", "name", "key-v1", "bob", 10, 1.0).get(0).value(), "bob");

    service.delete("tenant-a", "name", "record");
    assertTrue(service.search("tenant-a", "name", "key-v1", "bob", 10, 0.5).isEmpty());
  }

  @Test
  public void decryptsOnlyShortlistedCandidates() throws Exception {
    AtomicInteger keyLookups = new AtomicInteger();
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(keyLookups, repository);
    service.indexValue("tenant-a", "name", "one", "key-v1", "alex");
    service.indexValue("tenant-a", "name", "two", "key-v1", "alexa");
    service.indexValue("tenant-a", "name", "three", "key-v1", "alexander");
    keyLookups.set(0);

    List<FuzzySearchResult> results = service.search("tenant-a", "name", "key-v1", "alex", 1, 0.0);

    assertEquals(results.size(), 1);
    assertEquals(keyLookups.get(), 2);
  }

  @Test
  public void rejectsUnsupportedTransformAndInvalidRequestBounds() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    repository.insertWithTransform("unsupported-transform");

    assertThrows(
        GeneralSecurityException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 1001, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, 1.1));
    assertThrows(
        NullPointerException.class,
        () -> service.search("tenant-a", "name", "key-v1", null, 10, 0.0));
  }

  @Test
  public void rejectsCiphertextReplayedUnderAnotherRecordIdentity() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "record-1", "key-v1", "alpha");
    repository.overrideCandidateIdentity("record-2");

    assertThrows(
        InvalidCiphertextException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, 0.0));
  }

  @Test
  public void serviceRequiresCorrectStrategiesAndNonNullDependencies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptedFuzzySearchService(
                keyDeriver(new AtomicInteger()),
                new AuthenticatedFieldCipher(),
                new ExactBlindIndexer(),
                new CanonicalTextNormalizer(),
                new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
                new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
                new InMemoryRepository()));
    assertThrows(
        NullPointerException.class,
        () ->
            new EncryptedFuzzySearchService(
                null,
                new AuthenticatedFieldCipher(),
                new ExactBlindIndexer(),
                new CanonicalTextNormalizer(),
                new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
                new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
                new InMemoryRepository()));
  }

  private static EncryptedFuzzySearchService service(
      AtomicInteger keyLookups, InMemoryRepository repository) {
    return new EncryptedFuzzySearchService(
        keyDeriver(keyLookups),
        new AuthenticatedFieldCipher(),
        new ExactBlindIndexer(),
        new CanonicalTextNormalizer(),
        new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
        new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
        repository);
  }

  private static ScopedKeyDeriver keyDeriver(AtomicInteger keyLookups) {
    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x37);
    SecretKey rootKey = new SecretKeySpec(rootBytes, "HmacSHA256");
    return new ScopedKeyDeriver(
        ignored -> {
          keyLookups.incrementAndGet();
          return rootKey;
        });
  }

  private static final class InMemoryRepository implements TokenIndexRepository {
    private final List<EncryptedSearchDocument> documents = new ArrayList<>();
    private String transformOverride;
    private String recordIdOverride;

    @Override
    public void initializeSchema() {}

    @Override
    public void upsert(EncryptedSearchDocument document) {
      delete(document.tenantId(), document.fieldId(), document.recordId());
      documents.add(document);
    }

    @Override
    public List<SearchCandidate> findCandidates(
        String tenantId,
        String fieldId,
        String keyId,
        List<ProtectedToken> queryTokens,
        int limit) {
      if (recordIdOverride != null && !documents.isEmpty()) {
        EncryptedSearchDocument document = documents.getFirst();
        return List.of(
            new SearchCandidate(
                recordIdOverride,
                document.keyId(),
                document.transformVersion(),
                document.ciphertext(),
                1));
      }
      List<SearchCandidate> matches = new ArrayList<>();
      for (EncryptedSearchDocument document : documents) {
        if (!document.tenantId().equals(tenantId)
            || !document.fieldId().equals(fieldId)
            || !document.keyId().equals(keyId)) {
          continue;
        }
        long overlap = queryTokens.stream().filter(document.protectedTokens()::contains).count();
        if (overlap > 0) {
          matches.add(
              new SearchCandidate(
                  document.recordId(),
                  document.keyId(),
                  transformOverride == null ? document.transformVersion() : transformOverride,
                  document.ciphertext(),
                  (int) overlap));
        }
      }
      matches.sort(
          Comparator.comparingInt(SearchCandidate::overlapCount)
              .reversed()
              .thenComparing(SearchCandidate::recordId));
      return matches.stream().limit(limit).toList();
    }

    @Override
    public void delete(String tenantId, String fieldId, String recordId) {
      documents.removeIf(
          document ->
              document.tenantId().equals(tenantId)
                  && document.fieldId().equals(fieldId)
                  && document.recordId().equals(recordId));
    }

    private void insertWithTransform(String transformVersion) throws Exception {
      EncryptedFuzzySearchService service =
          EncryptedFuzzySearchServiceTest.service(new AtomicInteger(), this);
      service.indexValue("tenant-a", "name", "bad-transform", "key-v1", "alpha");
      transformOverride = transformVersion;
    }

    private void overrideCandidateIdentity(String recordId) {
      recordIdOverride = recordId;
    }
  }
}
