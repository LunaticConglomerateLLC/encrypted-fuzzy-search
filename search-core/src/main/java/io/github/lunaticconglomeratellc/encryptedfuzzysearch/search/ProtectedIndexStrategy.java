package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * SPI for generating versioned opaque tokens used to retrieve encrypted-search candidates.
 *
 * <p>The supplied key must already be derived for the tenant, field, and key version being indexed.
 * Implementations must identify their token namespace independently of token bytes and describe the
 * leakage and candidate matching behavior they introduce. A {@code null} transformId denotes the
 * canonical, non-profiled form; otherwise the ID names the immutable transform profile whose
 * variants are being indexed or queried.
 */
public interface ProtectedIndexStrategy {
  /** Stable namespace for this strategy and transform profile. */
  String strategyId(String transformId);

  /** Stable version of this strategy's token encoding. */
  int formatVersion();

  /** Scope for which the supplied key must have been derived. */
  IndexKeyScope keyScope();

  /** Candidate match semantics this strategy expects the repository query to use. */
  CandidateMatchMode candidateMatchMode();

  /** Known equality/overlap leakage introduced by this strategy. */
  Set<IndexLeakage> leakageMetadata();

  /** Creates versioned, namespaced protected tokens for canonical text. */
  List<ProtectedToken> createTokens(SecretKey indexKey, String canonicalText, String transformId)
      throws GeneralSecurityException;
}
