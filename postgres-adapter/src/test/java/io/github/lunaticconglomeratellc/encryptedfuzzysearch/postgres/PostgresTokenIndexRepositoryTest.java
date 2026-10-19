package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedSearchDocument;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedToken;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchCandidate;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class PostgresTokenIndexRepositoryTest {
  private final PostgresTestDataSource dataSource = new PostgresTestDataSource();
  private final PostgresTokenIndexRepository repository =
      new PostgresTokenIndexRepository(dataSource);

  @BeforeClass
  public void createSchema() throws Exception {
    repository.initializeSchema();
  }

  @AfterMethod
  public void clearRecords() throws Exception {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate("TRUNCATE TABLE efs_encrypted_records CASCADE");
    }
    dropFailureTrigger();
  }

  @Test
  public void schemaInitializationIsIdempotent() throws Exception {
    repository.initializeSchema();
    repository.initializeSchema();
  }

  @Test
  public void retrievesByTenantFieldAndKeyScopeAndRanksByTokenOverlap() throws Exception {
    ProtectedToken shared = token("shared");
    ProtectedToken second = token("second");
    repository.upsert(
        document("tenant-a", "name", "record-1", "key-v1", bytes(1), List.of(shared, second)));
    repository.upsert(
        document("tenant-a", "name", "record-2", "key-v1", bytes(2), List.of(shared)));
    repository.upsert(
        document("tenant-b", "name", "record-3", "key-v1", bytes(3), List.of(shared)));
    repository.upsert(
        document("tenant-a", "email", "record-4", "key-v1", bytes(4), List.of(shared)));
    repository.upsert(
        document("tenant-a", "name", "record-5", "key-v2", bytes(5), List.of(shared)));

    List<SearchCandidate> candidates =
        repository.findCandidates("tenant-a", "name", "key-v1", List.of(shared, second), 10);

    assertEquals(candidates.size(), 2);
    assertEquals(candidates.get(0).recordId(), "record-1");
    assertEquals(candidates.get(0).overlapCount(), 2);
    assertEquals(candidates.get(1).recordId(), "record-2");
    assertEquals(candidates.get(1).overlapCount(), 1);
    assertEquals(candidates.get(0).ciphertext(), bytes(1));
  }

  @Test
  public void replacingARecordAtomicallyReplacesItsTokensAndKeyVersion() throws Exception {
    ProtectedToken oldToken = token("old");
    ProtectedToken newToken = token("new");
    repository.upsert(
        document("tenant-a", "name", "record-1", "key-v1", bytes(1), List.of(oldToken)));

    repository.upsert(
        document("tenant-a", "name", "record-1", "key-v2", bytes(2), List.of(newToken)));

    assertTrue(
        repository.findCandidates("tenant-a", "name", "key-v1", List.of(oldToken), 10).isEmpty());
    List<SearchCandidate> updated =
        repository.findCandidates("tenant-a", "name", "key-v2", List.of(newToken), 10);
    assertEquals(updated.size(), 1);
    assertEquals(updated.get(0).keyId(), "key-v2");
    assertEquals(updated.get(0).ciphertext(), bytes(2));
  }

  @Test
  public void deleteCascadesToProtectedTokensAndIsTenantScoped() throws Exception {
    ProtectedToken token = token("shared");
    repository.upsert(document("tenant-a", "name", "record-1", "key-v1", bytes(1), List.of(token)));
    repository.upsert(document("tenant-b", "name", "record-1", "key-v1", bytes(2), List.of(token)));

    repository.delete("tenant-a", "name", "record-1");

    assertTrue(
        repository.findCandidates("tenant-a", "name", "key-v1", List.of(token), 10).isEmpty());
    assertEquals(
        repository.findCandidates("tenant-b", "name", "key-v1", List.of(token), 10).size(), 1);
  }

  @Test
  public void failedPostingWriteRollsBackRecordAndOldPostings() throws Exception {
    ProtectedToken oldToken = token("old");
    ProtectedToken newToken = token("new");
    repository.upsert(
        document("tenant-a", "name", "record-1", "key-v1", bytes(1), List.of(oldToken)));
    installFailureTrigger();

    assertThrows(
        SQLException.class,
        () ->
            repository.upsert(
                document("tenant-a", "name", "record-1", "key-v2", bytes(2), List.of(newToken))));

    List<SearchCandidate> original =
        repository.findCandidates("tenant-a", "name", "key-v1", List.of(oldToken), 10);
    assertEquals(original.size(), 1);
    assertEquals(original.get(0).keyId(), "key-v1");
    assertEquals(original.get(0).ciphertext(), bytes(1));
    assertTrue(
        repository.findCandidates("tenant-a", "name", "key-v2", List.of(newToken), 10).isEmpty());
  }

  @Test
  public void emptyQueriesReturnNoCandidatesAndInvalidBoundsAreRejected() throws Exception {
    assertTrue(repository.findCandidates("tenant-a", "name", "key-v1", List.of(), 1).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findCandidates("tenant-a", "name", "key-v1", List.of(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findCandidates("tenant-a", "name", "key-v1", List.of(), 10_001));
    assertThrows(
        NullPointerException.class,
        () -> repository.findCandidates(null, "name", "key-v1", List.of(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findCandidates("tenant-a", " ", "key-v1", List.of(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findCandidates("tenant-a", "name", "key-v1", tooManyDistinctTokens(), 1));
  }

  private void installFailureTrigger() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE OR REPLACE FUNCTION efs_test_fail_token_insert() RETURNS trigger "
              + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
      statement.execute(
          "CREATE TRIGGER efs_test_fail_token_insert BEFORE INSERT ON efs_protected_tokens "
              + "FOR EACH ROW EXECUTE FUNCTION efs_test_fail_token_insert()");
    }
  }

  private void dropFailureTrigger() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP TRIGGER IF EXISTS efs_test_fail_token_insert ON efs_protected_tokens");
      statement.execute("DROP FUNCTION IF EXISTS efs_test_fail_token_insert()");
    }
  }

  private static EncryptedSearchDocument document(
      String tenantId,
      String fieldId,
      String recordId,
      String keyId,
      byte[] ciphertext,
      List<ProtectedToken> tokens) {
    return new EncryptedSearchDocument(
        tenantId, fieldId, recordId, keyId, "efs-text-nfc-utf8-v1", ciphertext, tokens);
  }

  private static ProtectedToken token(String value) {
    return new ProtectedToken(
        "efs-test-token", 1, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static List<ProtectedToken> tooManyDistinctTokens() {
    List<ProtectedToken> tokens = new ArrayList<>(8193);
    for (int value = 0; value < 8193; value++) {
      tokens.add(
          new ProtectedToken(
              "efs-test-token", 1, ByteBuffer.allocate(Integer.BYTES).putInt(value).array()));
    }
    return tokens;
  }

  private static byte[] bytes(int value) {
    return new byte[] {(byte) value, 0x45, 0x46, 0x53};
  }
}
