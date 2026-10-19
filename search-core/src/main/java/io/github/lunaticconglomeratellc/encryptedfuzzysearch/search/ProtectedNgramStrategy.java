package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;

/** Generates bounded, deduplicated protected bigram or trigram tokens. */
public final class ProtectedNgramStrategy {
  public static final int DEFAULT_MAX_INPUT_CODE_POINTS = 4096;
  private static final int TOKEN_FORMAT_VERSION = 1;

  private final int gramSize;
  private final int maxInputCodePoints;
  private final NgramBlindIndexer indexer;

  /** Creates a strategy with the default 4096-code-point input limit. */
  public ProtectedNgramStrategy(int gramSize, NgramBlindIndexer indexer) {
    this(gramSize, DEFAULT_MAX_INPUT_CODE_POINTS, indexer);
  }

  /** Creates a strategy with an explicit input limit, useful for deployment-specific bounds. */
  public ProtectedNgramStrategy(int gramSize, int maxInputCodePoints, NgramBlindIndexer indexer) {
    if (gramSize != 2 && gramSize != 3) {
      throw new IllegalArgumentException("gramSize must be 2 or 3");
    }
    if (maxInputCodePoints < gramSize) {
      throw new IllegalArgumentException("maxInputCodePoints must be at least gramSize");
    }
    this.gramSize = gramSize;
    this.maxInputCodePoints = maxInputCodePoints;
    this.indexer = Objects.requireNonNull(indexer, "indexer");
  }

  /** Returns the configured gram size. */
  public int gramSize() {
    return gramSize;
  }

  /** Returns the maximum accepted input length in Unicode code points. */
  public int maxInputCodePoints() {
    return maxInputCodePoints;
  }

  /** Returns a stable identifier suitable for storing alongside protected postings. */
  public String strategyId() {
    return "efs-ngram-" + gramSize;
  }

  /**
   * Returns stable, deduplicated EFSN tokens for canonical text. Empty input yields no tokens; a
   * non-empty value shorter than the configured size yields one partial-gram token. Text longer
   * than the configured maximum is rejected, never silently truncated.
   *
   * <p>Callers should first apply the versioned canonical text transform shared by indexing and
   * querying.
   *
   * @param indexKey key derived for the tenant and field being indexed
   * @param canonicalText canonical Unicode text
   * @return immutable list of opaque protected tokens in first-occurrence order
   * @throws GeneralSecurityException if HMAC-SHA-256 is unavailable
   */
  public List<byte[]> createTokens(SecretKey indexKey, String canonicalText)
      throws GeneralSecurityException {
    Objects.requireNonNull(canonicalText, "canonicalText");
    int codePointCount = canonicalText.codePointCount(0, canonicalText.length());
    if (codePointCount > maxInputCodePoints) {
      throw new IllegalArgumentException(
          "input exceeds maximum of " + maxInputCodePoints + " code points");
    }
    if (codePointCount == 0) {
      return List.of();
    }

    int[] codePoints = canonicalText.codePoints().toArray();
    LinkedHashMap<String, byte[]> uniqueTokens = new LinkedHashMap<>();
    if (codePointCount < gramSize) {
      addToken(indexKey, codePoints, 0, codePointCount, uniqueTokens);
    } else {
      for (int start = 0; start <= codePointCount - gramSize; start++) {
        addToken(indexKey, codePoints, start, gramSize, uniqueTokens);
      }
    }
    return List.copyOf(new ArrayList<>(uniqueTokens.values()));
  }

  /** Returns protected tokens annotated with this strategy's stable identifier and format. */
  public List<ProtectedToken> createProtectedTokens(SecretKey indexKey, String canonicalText)
      throws GeneralSecurityException {
    return createTokens(indexKey, canonicalText).stream()
        .map(token -> new ProtectedToken(strategyId(), TOKEN_FORMAT_VERSION, token))
        .toList();
  }

  private void addToken(
      SecretKey indexKey,
      int[] codePoints,
      int start,
      int length,
      LinkedHashMap<String, byte[]> uniqueTokens)
      throws GeneralSecurityException {
    String gram = new String(codePoints, start, length);
    byte[] token = indexer.createToken(indexKey, gramSize, gram);
    String tokenIdentity = Base64.getEncoder().encodeToString(token);
    if (uniqueTokens.putIfAbsent(tokenIdentity, token) != null) {
      java.util.Arrays.fill(token, (byte) 0);
    }
  }
}
