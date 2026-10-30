package io.github.lunaticconglomeratellc.encryptedfuzzysearch.spring;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.MasterKeyProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.PostgresTokenIndexRepository;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedSearchDocument;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.FuzzySearchResult;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedExactStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedToken;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchCandidate;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.TokenIndexRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testng.annotations.Test;

public class EfsAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EfsAutoConfiguration.class));

  @Test
  public void createsPostgresAdapterOnlyWhenADataSourceExistsAndNeverInitializesSchema() {
    contextRunner
        .withBean(DataSource.class, EfsAutoConfigurationTest::unusedDataSource)
        .run(
            context -> {
              assertNotNull(context.getBean(PostgresTokenIndexRepository.class));
              assertFalse(context.containsBean("encryptedFuzzySearchService"));
            });
  }

  @Test
  public void createsSearchServiceWhenHostSuppliesKeyProviderAndRepository() {
    byte[] rootKeyBytes = new byte[32];
    Arrays.fill(rootKeyBytes, (byte) 0x2a);
    SecretKey rootKey = new SecretKeySpec(rootKeyBytes, "HmacSHA256");

    contextRunner
        .withBean(MasterKeyProvider.class, () -> ignored -> rootKey)
        .withBean(TokenIndexRepository.class, TestTokenIndexRepository::new)
        .run(
            context -> {
              EncryptedFuzzySearchService service =
                  context.getBean(EncryptedFuzzySearchService.class);
              try {
                service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice");
                List<FuzzySearchResult> results =
                    service.search("tenant-a", "name", "key-v1", "Alice", 10, 1.0);
                assertEquals(results.size(), 1);
                assertEquals(results.getFirst().recordId(), "alice");
              } catch (Exception exception) {
                throw new AssertionError(
                    "Java consumer could not use the configured service", exception);
              }
              assertTrue(context.containsBean("efsScopedKeyDeriver"));
              assertTrue(context.containsBean("efsCanonicalTextNormalizer"));
            });
  }

  @Test
  public void acceptsHostProvidedSearchServiceWithoutReplacingIt() {
    byte[] rootKeyBytes = new byte[32];
    SecretKey rootKey = new SecretKeySpec(rootKeyBytes, "HmacSHA256");
    MasterKeyProvider provider = ignored -> rootKey;
    TokenIndexRepository repository = new TestTokenIndexRepository();
    EncryptedFuzzySearchService hostService =
        new EncryptedFuzzySearchService(
            new ScopedKeyDeriver(provider),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            List.of(new ProtectedExactStrategy(new ExactBlindIndexer())),
            repository);

    contextRunner
        .withBean(MasterKeyProvider.class, () -> provider)
        .withBean(TokenIndexRepository.class, () -> repository)
        .withBean(EncryptedFuzzySearchService.class, () -> hostService)
        .run(
            context ->
                assertEquals(context.getBean(EncryptedFuzzySearchService.class), hostService));
  }

  private static DataSource unusedDataSource() {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              throw new UnsupportedOperationException("test DataSource must not be used");
            });
  }

  private static final class TestTokenIndexRepository implements TokenIndexRepository {
    private final List<EncryptedSearchDocument> documents = new ArrayList<>();

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
      return documents.stream()
          .filter(
              document ->
                  document.tenantId().equals(tenantId)
                      && document.fieldId().equals(fieldId)
                      && document.keyId().equals(keyId)
                      && queryTokens.stream().anyMatch(document.protectedTokens()::contains))
          .map(
              document ->
                  new SearchCandidate(
                      document.recordId(),
                      document.keyId(),
                      document.transformVersion(),
                      document.ciphertext(),
                      (int)
                          queryTokens.stream()
                              .filter(document.protectedTokens()::contains)
                              .count()))
          .sorted(Comparator.comparingInt(SearchCandidate::overlapCount).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public void delete(String tenantId, String fieldId, String recordId) {
      documents.removeIf(
          document ->
              document.tenantId().equals(tenantId)
                  && document.fieldId().equals(fieldId)
                  && document.recordId().equals(recordId));
    }
  }
}
