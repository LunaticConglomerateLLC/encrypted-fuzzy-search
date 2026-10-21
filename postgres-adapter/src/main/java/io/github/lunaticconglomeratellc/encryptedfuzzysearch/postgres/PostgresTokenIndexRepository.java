package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedSearchDocument;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedToken;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchCandidate;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.TokenIndexRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** PostgreSQL implementation storing ciphertext and protected token postings only. */
public final class PostgresTokenIndexRepository implements TokenIndexRepository {
  private static final String SCHEMA_RESOURCE = "/db/schema.sql";
  private static final int MAX_QUERY_TOKENS = 8192;
  private static final int MAX_CANDIDATE_LIMIT = 10_000;

  private final DataSource dataSource;

  /** Creates the repository over an application-configured JDBC data source. */
  public PostgresTokenIndexRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public void initializeSchema() throws SQLException {
    List<String> statements = loadSchemaStatements();
    try (Connection connection = dataSource.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        for (String sql : statements) {
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
          }
        }
        connection.commit();
      } catch (SQLException exception) {
        rollback(connection, exception);
        throw exception;
      } finally {
        connection.setAutoCommit(originalAutoCommit);
      }
    }
  }

  @Override
  public void upsert(EncryptedSearchDocument document) throws SQLException {
    Objects.requireNonNull(document, "document");
    String deleteTokensSql =
        "DELETE FROM efs_protected_tokens WHERE tenant_id = ? AND field_id = ? AND record_id = ?";
    String upsertRecordSql =
        "INSERT INTO efs_encrypted_records "
            + "(tenant_id, field_id, record_id, key_id, transform_version, ciphertext) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, field_id, record_id) DO UPDATE SET "
            + "key_id = EXCLUDED.key_id, transform_version = EXCLUDED.transform_version, "
            + "ciphertext = EXCLUDED.ciphertext";
    String insertTokenSql =
        "INSERT INTO efs_protected_tokens "
            + "(tenant_id, field_id, record_id, key_id, token_strategy, token_version, token) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    try (Connection connection = dataSource.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement = connection.prepareStatement(deleteTokensSql)) {
          statement.setString(1, document.tenantId());
          statement.setString(2, document.fieldId());
          statement.setString(3, document.recordId());
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(upsertRecordSql)) {
          statement.setString(1, document.tenantId());
          statement.setString(2, document.fieldId());
          statement.setString(3, document.recordId());
          statement.setString(4, document.keyId());
          statement.setString(5, document.transformVersion());
          statement.setBytes(6, document.ciphertext());
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(insertTokenSql)) {
          for (ProtectedToken token : document.protectedTokens()) {
            statement.setString(1, document.tenantId());
            statement.setString(2, document.fieldId());
            statement.setString(3, document.recordId());
            statement.setString(4, document.keyId());
            statement.setString(5, token.strategyId());
            statement.setInt(6, token.formatVersion());
            statement.setBytes(7, token.value());
            statement.addBatch();
          }
          statement.executeBatch();
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      } finally {
        connection.setAutoCommit(originalAutoCommit);
      }
    }
  }

  @Override
  public List<SearchCandidate> findCandidates(
      String tenantId, String fieldId, String keyId, List<ProtectedToken> queryTokens, int limit)
      throws SQLException {
    requireScope(tenantId, "tenantId");
    requireScope(fieldId, "fieldId");
    requireScope(keyId, "keyId");
    Objects.requireNonNull(queryTokens, "queryTokens");
    if (limit < 1 || limit > MAX_CANDIDATE_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_CANDIDATE_LIMIT);
    }
    if (queryTokens.size() > MAX_QUERY_TOKENS) {
      throw new IllegalArgumentException("query exceeds maximum protected-token count");
    }
    if (queryTokens.isEmpty()) {
      return List.of();
    }
    queryTokens.forEach(token -> Objects.requireNonNull(token, "query token"));

    StringBuilder sql =
        new StringBuilder(
            "SELECT r.record_id, r.key_id, r.transform_version, r.ciphertext, COUNT(*) AS overlap "
                + "FROM efs_encrypted_records r JOIN efs_protected_tokens t "
                + "ON t.tenant_id = r.tenant_id AND t.field_id = r.field_id "
                + "AND t.record_id = r.record_id AND t.key_id = r.key_id "
                + "WHERE r.tenant_id = ? AND r.field_id = ? AND r.key_id = ? AND (");
    for (int index = 0; index < queryTokens.size(); index++) {
      if (index > 0) {
        sql.append(" OR ");
      }
      sql.append("(t.token_strategy = ? AND t.token_version = ? AND t.token = ?)");
    }
    sql.append(
        ") GROUP BY r.record_id, r.key_id, r.transform_version, r.ciphertext "
            + "ORDER BY overlap DESC, r.record_id ASC LIMIT ?");

    List<SearchCandidate> candidates = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      statement.setString(1, tenantId);
      statement.setString(2, fieldId);
      statement.setString(3, keyId);
      int parameter = 4;
      for (ProtectedToken token : queryTokens) {
        statement.setString(parameter++, token.strategyId());
        statement.setInt(parameter++, token.formatVersion());
        statement.setBytes(parameter++, token.value());
      }
      statement.setInt(parameter, limit);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          candidates.add(
              new SearchCandidate(
                  result.getString("record_id"),
                  result.getString("key_id"),
                  result.getString("transform_version"),
                  result.getBytes("ciphertext"),
                  result.getInt("overlap")));
        }
      }
    }
    return List.copyOf(candidates);
  }

  @Override
  public void delete(String tenantId, String fieldId, String recordId) throws SQLException {
    requireScope(tenantId, "tenantId");
    requireScope(fieldId, "fieldId");
    requireScope(recordId, "recordId");
    String sql =
        "DELETE FROM efs_encrypted_records WHERE tenant_id = ? AND field_id = ? AND record_id = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenantId);
      statement.setString(2, fieldId);
      statement.setString(3, recordId);
      statement.executeUpdate();
    }
  }

  private static List<String> loadSchemaStatements() throws SQLException {
    InputStream resource = PostgresTokenIndexRepository.class.getResourceAsStream(SCHEMA_RESOURCE);
    if (resource == null) {
      throw new SQLException("PostgreSQL schema resource is missing");
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
      String source = reader.lines().reduce("", (left, right) -> left + right + "\n");
      List<String> statements = new ArrayList<>();
      for (String statement : source.split(";")) {
        if (!statement.isBlank()) {
          statements.add(statement.trim());
        }
      }
      return List.copyOf(statements);
    } catch (IOException exception) {
      throw new SQLException("Unable to read PostgreSQL schema resource", exception);
    }
  }

  private static void requireScope(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must contain 1 to 256 non-blank characters");
    }
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }
}
