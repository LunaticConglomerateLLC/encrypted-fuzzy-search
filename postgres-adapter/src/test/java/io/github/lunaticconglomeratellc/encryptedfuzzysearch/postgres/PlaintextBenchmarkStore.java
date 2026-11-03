package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Synthetic-only plaintext benchmark store. This class is confined to the PostgreSQL test source.
 */
final class PlaintextBenchmarkStore {
  static final String RECORD_TABLE = "efs_benchmark_plaintext_records";
  static final String TOKEN_TABLE = "efs_benchmark_plaintext_tokens";

  private final DataSource dataSource;

  PlaintextBenchmarkStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  void initializeSchema() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement createRecords =
            connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS "
                    + RECORD_TABLE
                    + " (tenant_id TEXT NOT NULL, field_id TEXT NOT NULL, record_id TEXT NOT NULL, "
                    + "key_id TEXT NOT NULL, plaintext_value TEXT NOT NULL, "
                    + "PRIMARY KEY (tenant_id, field_id, record_id))");
        PreparedStatement createTokens =
            connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS "
                    + TOKEN_TABLE
                    + " (tenant_id TEXT NOT NULL, field_id TEXT NOT NULL, record_id TEXT NOT NULL, "
                    + "key_id TEXT NOT NULL, token_strategy TEXT NOT NULL, token_version INTEGER NOT NULL, "
                    + "token_value TEXT NOT NULL, PRIMARY KEY (tenant_id, field_id, record_id, "
                    + "token_strategy, token_version, token_value), FOREIGN KEY (tenant_id, field_id, record_id) "
                    + "REFERENCES "
                    + RECORD_TABLE
                    + " (tenant_id, field_id, record_id) ON DELETE CASCADE)");
        PreparedStatement createLookupIndex =
            connection.prepareStatement(
                "CREATE INDEX IF NOT EXISTS efs_benchmark_plaintext_lookup_idx ON "
                    + TOKEN_TABLE
                    + " (tenant_id, field_id, key_id, token_strategy, token_version, token_value)")) {
      createRecords.execute();
      createTokens.execute();
      createLookupIndex.execute();
    }
  }

  void clear() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement truncate =
            connection.prepareStatement("TRUNCATE TABLE " + RECORD_TABLE + " CASCADE")) {
      truncate.executeUpdate();
    }
  }

  void upsert(String tenantId, String fieldId, String recordId, String keyId, String canonicalValue)
      throws SQLException {
    Objects.requireNonNull(canonicalValue, "canonicalValue");
    String upsertRecord =
        "INSERT INTO "
            + RECORD_TABLE
            + " (tenant_id, field_id, record_id, key_id, plaintext_value) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, field_id, record_id) DO UPDATE SET "
            + "key_id = EXCLUDED.key_id, plaintext_value = EXCLUDED.plaintext_value";
    String deleteTokens =
        "DELETE FROM " + TOKEN_TABLE + " WHERE tenant_id = ? AND field_id = ? AND record_id = ?";
    String insertToken =
        "INSERT INTO "
            + TOKEN_TABLE
            + " (tenant_id, field_id, record_id, key_id, token_strategy, token_version, token_value) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";
    try (Connection connection = dataSource.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement = connection.prepareStatement(upsertRecord)) {
          statement.setString(1, tenantId);
          statement.setString(2, fieldId);
          statement.setString(3, recordId);
          statement.setString(4, keyId);
          statement.setString(5, canonicalValue);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(deleteTokens)) {
          statement.setString(1, tenantId);
          statement.setString(2, fieldId);
          statement.setString(3, recordId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(insertToken)) {
          for (PlaintextToken token : createTokens(canonicalValue)) {
            statement.setString(1, tenantId);
            statement.setString(2, fieldId);
            statement.setString(3, recordId);
            statement.setString(4, keyId);
            statement.setString(5, token.strategyId());
            statement.setInt(6, token.version());
            statement.setString(7, token.value());
            statement.addBatch();
          }
          statement.executeBatch();
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          exception.addSuppressed(rollbackFailure);
        }
        throw exception;
      } finally {
        connection.setAutoCommit(originalAutoCommit);
      }
    }
  }

  List<PlaintextCandidate> findCandidates(
      String tenantId, String fieldId, String keyId, List<PlaintextToken> queryTokens, int limit)
      throws SQLException {
    Objects.requireNonNull(queryTokens, "queryTokens");
    if (queryTokens.isEmpty()) {
      return List.of();
    }
    StringBuilder sql =
        new StringBuilder(
            "SELECT r.record_id, r.plaintext_value, COUNT(*) AS overlap FROM "
                + RECORD_TABLE
                + " r JOIN "
                + TOKEN_TABLE
                + " t ON t.tenant_id = r.tenant_id AND t.field_id = r.field_id "
                + "AND t.record_id = r.record_id AND t.key_id = r.key_id "
                + "WHERE r.tenant_id = ? AND r.field_id = ? AND r.key_id = ? AND (");
    for (int index = 0; index < queryTokens.size(); index++) {
      if (index > 0) {
        sql.append(" OR ");
      }
      sql.append("(t.token_strategy = ? AND t.token_version = ? AND t.token_value = ?)");
    }
    sql.append(
        ") GROUP BY r.record_id, r.plaintext_value ORDER BY overlap DESC, r.record_id ASC LIMIT ?");

    List<PlaintextCandidate> candidates = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      statement.setString(1, tenantId);
      statement.setString(2, fieldId);
      statement.setString(3, keyId);
      int parameter = 4;
      for (PlaintextToken token : queryTokens) {
        statement.setString(parameter++, token.strategyId());
        statement.setInt(parameter++, token.version());
        statement.setString(parameter++, token.value());
      }
      statement.setInt(parameter, limit);
      try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          candidates.add(
              new PlaintextCandidate(
                  results.getString("record_id"),
                  results.getString("plaintext_value"),
                  results.getInt("overlap")));
        }
      }
    }
    return List.copyOf(candidates);
  }

  static List<PlaintextToken> createTokens(String canonicalText) {
    Objects.requireNonNull(canonicalText, "canonicalText");
    List<PlaintextToken> tokens = new ArrayList<>();
    tokens.add(new PlaintextToken("efs-exact", 1, canonicalText));
    addNgrams(tokens, canonicalText, 2);
    addNgrams(tokens, canonicalText, 3);
    return List.copyOf(tokens);
  }

  private static void addNgrams(List<PlaintextToken> tokens, String value, int gramSize) {
    int[] codePoints = value.codePoints().toArray();
    Set<String> uniqueGrams = new LinkedHashSet<>();
    if (codePoints.length > 0 && codePoints.length < gramSize) {
      uniqueGrams.add(new String(codePoints, 0, codePoints.length));
    } else {
      for (int start = 0; start <= codePoints.length - gramSize; start++) {
        uniqueGrams.add(new String(codePoints, start, gramSize));
      }
    }
    for (String gram : uniqueGrams) {
      tokens.add(new PlaintextToken("efs-ngram-" + gramSize, 1, gram));
    }
  }

  record PlaintextToken(String strategyId, int version, String value) {}

  record PlaintextCandidate(String recordId, String value, int overlapCount) {}
}
