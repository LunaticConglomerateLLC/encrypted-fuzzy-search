package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;

/** Optional protected prefix candidate strategy over canonical Unicode code points. */
public final class ProtectedPrefixStrategy implements ProtectedIndexStrategy {
  public static final int DEFAULT_MIN_PREFIX_CODE_POINTS = 2;
  public static final int DEFAULT_MAX_PREFIX_CODE_POINTS = 8;
  public static final int DEFAULT_MAX_INPUT_CODE_POINTS = 4096;
  public static final int MAX_PREFIX_CODE_POINTS = 256;

  private final ExactBlindIndexer indexer;
  private final int minPrefixCodePoints;
  private final int maxPrefixCodePoints;
  private final int maxInputCodePoints;

  public ProtectedPrefixStrategy(ExactBlindIndexer indexer) {
    this(
        indexer,
        DEFAULT_MIN_PREFIX_CODE_POINTS,
        DEFAULT_MAX_PREFIX_CODE_POINTS,
        DEFAULT_MAX_INPUT_CODE_POINTS);
  }

  public ProtectedPrefixStrategy(
      ExactBlindIndexer indexer,
      int minPrefixCodePoints,
      int maxPrefixCodePoints,
      int maxInputCodePoints) {
    this.indexer = Objects.requireNonNull(indexer, "indexer");
    if (minPrefixCodePoints < 1) {
      throw new IllegalArgumentException("minPrefixCodePoints must be positive");
    }
    if (maxPrefixCodePoints < minPrefixCodePoints) {
      throw new IllegalArgumentException("maxPrefixCodePoints must be at least the minimum");
    }
    if (maxPrefixCodePoints > MAX_PREFIX_CODE_POINTS) {
      throw new IllegalArgumentException(
          "maxPrefixCodePoints must not exceed " + MAX_PREFIX_CODE_POINTS);
    }
    if (maxInputCodePoints < maxPrefixCodePoints) {
      throw new IllegalArgumentException("maxInputCodePoints must be at least the maximum prefix");
    }
    this.minPrefixCodePoints = minPrefixCodePoints;
    this.maxPrefixCodePoints = maxPrefixCodePoints;
    this.maxInputCodePoints = maxInputCodePoints;
  }

  @Override
  public String strategyId(String transformId) {
    String prefix = "efs-prefix-" + minPrefixCodePoints + "-" + maxPrefixCodePoints;
    String strategyId =
        transformId == null ? prefix : "efs-l10n-" + requireTransformId(transformId) + "-" + prefix;
    if (strategyId.length() > 64) {
      throw new IllegalArgumentException(
          "transform profile produces an overlong token strategy id");
    }
    return strategyId;
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
  public Set<IndexLeakage> leakageMetadata() {
    return Set.of(
        IndexLeakage.EQUALITY,
        IndexLeakage.FREQUENCY,
        IndexLeakage.TOKEN_OVERLAP,
        IndexLeakage.PREFIX_RELATION);
  }

  @Override
  public List<ProtectedToken> createTokens(
      SecretKey indexKey, String canonicalText, String transformId)
      throws GeneralSecurityException {
    Objects.requireNonNull(canonicalText, "canonicalText");
    int[] codePoints = canonicalText.codePoints().toArray();
    if (codePoints.length > maxInputCodePoints) {
      throw new IllegalArgumentException(
          "input exceeds maximum of " + maxInputCodePoints + " code points");
    }
    int maximumPrefix = Math.min(codePoints.length, maxPrefixCodePoints);
    if (maximumPrefix < minPrefixCodePoints) {
      return List.of();
    }

    String strategyId = strategyId(transformId);
    List<ProtectedToken> tokens = new ArrayList<>(maximumPrefix - minPrefixCodePoints + 1);
    for (int length = minPrefixCodePoints; length <= maximumPrefix; length++) {
      byte[] prefixBytes = new String(codePoints, 0, length).getBytes(StandardCharsets.UTF_8);
      try {
        tokens.add(
            new ProtectedToken(
                strategyId, formatVersion(), indexer.createToken(indexKey, prefixBytes)));
      } finally {
        Arrays.fill(prefixBytes, (byte) 0);
      }
    }
    return List.copyOf(tokens);
  }

  private static String requireTransformId(String transformId) {
    if (transformId.isBlank() || transformId.length() > 64) {
      throw new IllegalArgumentException("transformId must contain 1 to 64 characters");
    }
    return transformId;
  }
}
