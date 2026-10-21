package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.FuzzySearchResult;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedNgramStrategy;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** End-to-end database tests with deterministic synthetic fixtures and test-only key material. */
public class PostgresEncryptedFuzzySearchFunctionalTest {
  private final PostgresTestDataSource dataSource = new PostgresTestDataSource();
  private final PostgresTokenIndexRepository repository =
      new PostgresTokenIndexRepository(dataSource);
  private final EncryptedFuzzySearchService service = createService(repository);

  @BeforeClass
  public void initializeSchema() throws Exception {
    repository.initializeSchema();
  }

  @BeforeMethod
  public void clearPreviousFixture() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("TRUNCATE TABLE efs_encrypted_records CASCADE");
    }
  }

  @Test
  public void seedsWritesSearchesAndVerifiesFuzzyMatchesAgainstPostgres() throws Exception {
    service.indexValue("tenant-a", "name", "entity-001", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "entity-002", "key-v1", "Alicia");
    service.indexValue("tenant-a", "name", "entity-003", "key-v1", "Maria");

    List<FuzzySearchResult> matches =
        service.search("tenant-a", "name", "key-v1", "Alica", 25, 0.65);

    assertEquals(matches.size(), 2);
    assertEquals(matches.get(0).recordId(), "entity-002");
    assertEquals(matches.get(0).value(), "Alicia");
    assertEquals(matches.get(1).recordId(), "entity-001");
    assertEquals(matches.get(1).value(), "Alice");
    assertTrue(matches.get(0).overlapCount() > 0);
  }

  @Test
  public void readsBackExactMatchesAndReturnsNoResultsForNoMatchQueries() throws Exception {
    service.indexValue("tenant-a", "name", "entity-001", "key-v1", "Caf\u00e9");

    List<FuzzySearchResult> exact =
        service.search("tenant-a", "name", "key-v1", "Cafe\u0301", 25, 1.0);
    List<FuzzySearchResult> none = service.search("tenant-a", "name", "key-v1", "zzzz", 25, 0.5);

    assertEquals(exact.size(), 1);
    assertEquals(exact.get(0).value(), "Caf\u00e9");
    assertTrue(none.isEmpty());
  }

  @Test
  public void isolatesTenantsAndFieldsAndKeepsUpdatesAndDeletesConsistent() throws Exception {
    service.indexValue("tenant-a", "name", "entity-001", "key-v1", "Victoria");
    service.indexValue("tenant-b", "name", "entity-002", "key-v1", "Victoria");
    service.indexValue("tenant-a", "email", "entity-003", "key-v1", "Victoria");

    assertEquals(service.search("tenant-a", "name", "key-v1", "Victoria", 25, 1.0).size(), 1);
    assertEquals(
        service.search("tenant-b", "name", "key-v1", "Victoria", 25, 1.0).get(0).recordId(),
        "entity-002");

    service.indexValue("tenant-a", "name", "entity-001", "key-v2", "Victor");
    assertTrue(service.search("tenant-a", "name", "key-v1", "Victoria", 25, 0.5).isEmpty());
    assertEquals(
        service.search("tenant-a", "name", "key-v2", "Victor", 25, 1.0).get(0).value(), "Victor");
    service.delete("tenant-a", "name", "entity-001");
    assertTrue(service.search("tenant-a", "name", "key-v2", "Victor", 25, 0.5).isEmpty());
  }

  @Test
  public void schemaContainsNoPlaintextOrRawGramColumns() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      ResultSetMetaData recordColumns;
      try (var result = statement.executeQuery("SELECT * FROM efs_encrypted_records LIMIT 0")) {
        recordColumns = result.getMetaData();
      }
      for (int index = 1; index <= recordColumns.getColumnCount(); index++) {
        String name = recordColumns.getColumnName(index).toLowerCase(java.util.Locale.ROOT);
        assertFalse(name.contains("plaintext"));
        assertFalse(name.contains("normalized"));
        assertFalse(name.contains("value"));
      }
      try (var result = statement.executeQuery("SELECT * FROM efs_protected_tokens LIMIT 0")) {
        ResultSetMetaData tokenColumns = result.getMetaData();
        for (int index = 1; index <= tokenColumns.getColumnCount(); index++) {
          String name = tokenColumns.getColumnName(index).toLowerCase(java.util.Locale.ROOT);
          assertFalse(name.contains("gram"));
          assertFalse(name.contains("plaintext"));
        }
      }
    }
  }

  private static EncryptedFuzzySearchService createService(
      PostgresTokenIndexRepository repository) {
    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x5a);
    SecretKey rootKey = new SecretKeySpec(rootBytes, "HmacSHA256");
    ScopedKeyDeriver keyDeriver = new ScopedKeyDeriver(ignored -> rootKey);
    NgramBlindIndexer ngramIndexer = new NgramBlindIndexer();
    return new EncryptedFuzzySearchService(
        keyDeriver,
        new AuthenticatedFieldCipher(),
        new ExactBlindIndexer(),
        new CanonicalTextNormalizer(),
        new ProtectedNgramStrategy(2, ngramIndexer),
        new ProtectedNgramStrategy(3, ngramIndexer),
        repository);
  }
}
