package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;

/** Protected-index strategy that emits one deterministic equality token. */
public final class ProtectedExactStrategy implements ProtectedIndexStrategy {
  private static final String STRATEGY_ID = "efs-exact";
  private final ExactBlindIndexer indexer;

  public ProtectedExactStrategy(ExactBlindIndexer indexer) {
    this.indexer = Objects.requireNonNull(indexer, "indexer");
  }

  @Override
  public String strategyId(String transformId) {
    String identifier =
        transformId == null ? STRATEGY_ID : "efs-xexact-" + requireTransformId(transformId);
    requireStrategyId(identifier);
    return identifier;
  }

  @Override
  public int formatVersion() {
    return indexer.formatVersion();
  }

  @Override
  public IndexKeyScope keyScope() {
    return IndexKeyScope.TENANT_FIELD_KEY_VERSION;
  }

  @Override
  public CandidateMatchMode candidateMatchMode() {
    return CandidateMatchMode.ANY_SHARED_TOKEN;
  }

  @Override
  public CandidateCompleteness candidateCompleteness() {
    return CandidateCompleteness.exact();
  }

  @Override
  public Set<IndexLeakage> leakageMetadata() {
    return Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY);
  }

  @Override
  public List<ProtectedToken> createTokens(
      SecretKey indexKey, String canonicalText, String transformId)
      throws GeneralSecurityException {
    Objects.requireNonNull(canonicalText, "canonicalText");
    byte[] canonicalBytes = canonicalText.getBytes(StandardCharsets.UTF_8);
    try {
      return List.of(
          new ProtectedToken(
              strategyId(transformId),
              formatVersion(),
              indexer.createToken(indexKey, canonicalBytes)));
    } finally {
      Arrays.fill(canonicalBytes, (byte) 0);
    }
  }

  private static String requireTransformId(String transformId) {
    if (transformId.isBlank() || transformId.length() > 64) {
      throw new IllegalArgumentException("transformId must contain 1 to 64 characters");
    }
    return transformId;
  }

  private static void requireStrategyId(String strategyId) {
    if (strategyId.length() > 64) {
      throw new IllegalArgumentException("strategy ID must not exceed 64 characters");
    }
  }
}
