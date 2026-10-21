package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;

/** Generates bounded HMAC-protected deletion-neighborhood variants for candidate retrieval. */
public final class ProtectedDeletionStrategy implements ProtectedIndexStrategy {
  public static final int DEFAULT_MAX_EDIT_RADIUS = 2;
  public static final int DEFAULT_MAX_INPUT_CODE_POINTS = 128;
  public static final int DEFAULT_MAX_TOKEN_COUNT = 4096;

  private final ExactBlindIndexer indexer;
  private final int maxEditRadius;
  private final int maxInputCodePoints;
  private final int maxTokenCount;

  /** Creates a radius-two strategy with explicit hard bounds on input and token expansion. */
  public ProtectedDeletionStrategy(ExactBlindIndexer indexer) {
    this(indexer, DEFAULT_MAX_EDIT_RADIUS, DEFAULT_MAX_INPUT_CODE_POINTS, DEFAULT_MAX_TOKEN_COUNT);
  }

  /** Creates a strategy with deployment-specific radius and expansion limits. */
  public ProtectedDeletionStrategy(
      ExactBlindIndexer indexer, int maxEditRadius, int maxInputCodePoints, int maxTokenCount) {
    this.indexer = Objects.requireNonNull(indexer, "indexer");
    if (maxEditRadius < 1 || maxEditRadius > 2) {
      throw new IllegalArgumentException("maxEditRadius must be 1 or 2");
    }
    if (maxInputCodePoints < 1) {
      throw new IllegalArgumentException("maxInputCodePoints must be positive");
    }
    if (maxTokenCount < 1) {
      throw new IllegalArgumentException("maxTokenCount must be positive");
    }
    this.maxEditRadius = maxEditRadius;
    this.maxInputCodePoints = maxInputCodePoints;
    this.maxTokenCount = maxTokenCount;
  }

  public int maxEditRadius() {
    return maxEditRadius;
  }

  public int maxInputCodePoints() {
    return maxInputCodePoints;
  }

  public int maxTokenCount() {
    return maxTokenCount;
  }

  @Override
  public String strategyId(String transformId) {
    String strategyId =
        transformId == null
            ? "efs-delete-r" + maxEditRadius
            : "efs-l10n-" + requireTransformId(transformId) + "-delete-r" + maxEditRadius;
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
    return Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP);
  }

  @Override
  public List<ProtectedToken> createTokens(
      SecretKey indexKey, String canonicalText, String transformId)
      throws GeneralSecurityException {
    Objects.requireNonNull(canonicalText, "canonicalText");
    int codePointCount = canonicalText.codePointCount(0, canonicalText.length());
    if (codePointCount > maxInputCodePoints) {
      throw new IllegalArgumentException(
          "input exceeds maximum of " + maxInputCodePoints + " code points");
    }

    List<String> variants = generateVariants(canonicalText);
    if (variants.size() > maxTokenCount) {
      throw new IllegalArgumentException(
          "deletion neighborhood exceeds maximum of " + maxTokenCount + " tokens");
    }

    String strategyId = strategyId(transformId);
    List<ProtectedToken> tokens = new ArrayList<>(variants.size());
    for (String variant : variants) {
      byte[] bytes = variant.getBytes(StandardCharsets.UTF_8);
      try {
        tokens.add(
            new ProtectedToken(strategyId, formatVersion(), indexer.createToken(indexKey, bytes)));
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    }
    return List.copyOf(tokens);
  }

  private List<String> generateVariants(String canonicalText) {
    LinkedHashSet<String> variants = new LinkedHashSet<>();
    variants.add(canonicalText);
    List<String> frontier = List.of(canonicalText);
    for (int depth = 0; depth < maxEditRadius; depth++) {
      LinkedHashSet<String> next = new LinkedHashSet<>();
      for (String source : frontier) {
        int[] codePoints = source.codePoints().toArray();
        for (int removeAt = 0; removeAt < codePoints.length; removeAt++) {
          String variant = removeCodePoint(codePoints, removeAt);
          if (variants.add(variant)) {
            next.add(variant);
            if (variants.size() > maxTokenCount) {
              throw new IllegalArgumentException(
                  "deletion neighborhood exceeds maximum of " + maxTokenCount + " tokens");
            }
          }
        }
      }
      if (next.isEmpty()) {
        break;
      }
      frontier = List.copyOf(next);
    }
    return List.copyOf(variants);
  }

  private static String removeCodePoint(int[] codePoints, int removeAt) {
    int[] remaining = new int[codePoints.length - 1];
    System.arraycopy(codePoints, 0, remaining, 0, removeAt);
    System.arraycopy(
        codePoints, removeAt + 1, remaining, removeAt, codePoints.length - removeAt - 1);
    return new String(remaining, 0, remaining.length);
  }

  private static String requireTransformId(String transformId) {
    if (transformId.isBlank() || transformId.length() > 64) {
      throw new IllegalArgumentException("transformId must contain 1 to 64 characters");
    }
    return transformId;
  }
}
