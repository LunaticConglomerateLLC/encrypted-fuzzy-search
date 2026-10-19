package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.sql.SQLException;
import java.util.List;

/** Persistence boundary for encrypted fields and tenant/field-scoped protected token postings. */
public interface TokenIndexRepository {
  /** Creates or upgrades the adapter's supported schema. */
  void initializeSchema() throws SQLException;

  /** Atomically replaces a record and all its protected token postings. */
  void upsert(EncryptedSearchDocument document) throws SQLException;

  /** Retrieves records matching any supplied token, ranked by overlap then stable record ID. */
  List<SearchCandidate> findCandidates(
      String tenantId, String fieldId, String keyId, List<ProtectedToken> queryTokens, int limit)
      throws SQLException;

  /** Deletes a field record and cascades to all associated protected tokens. */
  void delete(String tenantId, String fieldId, String recordId) throws SQLException;
}
