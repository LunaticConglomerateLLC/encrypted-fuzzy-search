package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Optional English-ASCII Soundex candidate strategy; it does not transliterate unsupported text.
 */
public final class ProtectedSoundexStrategy implements ProtectedIndexStrategy {
  public static final int DEFAULT_MAX_INPUT_CODE_POINTS = 128;
  public static final int MAX_INPUT_CODE_POINTS = 4096;
  private static final String STRATEGY_ID = "efs-soundex-ascii-en-v1";
  private final ExactBlindIndexer indexer;
  private final int maxInputCodePoints;

  public ProtectedSoundexStrategy(ExactBlindIndexer indexer) {
    this(indexer, DEFAULT_MAX_INPUT_CODE_POINTS);
  }

  public ProtectedSoundexStrategy(ExactBlindIndexer indexer, int maxInputCodePoints) {
    this.indexer = Objects.requireNonNull(indexer, "indexer");
    if (maxInputCodePoints < 1 || maxInputCodePoints > MAX_INPUT_CODE_POINTS) {
      throw new IllegalArgumentException(
          "maxInputCodePoints must be between 1 and " + MAX_INPUT_CODE_POINTS);
    }
    this.maxInputCodePoints = maxInputCodePoints;
  }

  public int maxInputCodePoints() {
    return maxInputCodePoints;
  }

  @Override
  public String strategyId(String transformId) {
    String strategyId =
        transformId == null ? STRATEGY_ID : STRATEGY_ID + "-" + requireTransformId(transformId);
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
        IndexLeakage.STRATEGY_COLLISION);
  }

  @Override
  public List<ProtectedToken> createTokens(
      SecretKey indexKey, String canonicalText, String transformId)
      throws GeneralSecurityException {
    Objects.requireNonNull(canonicalText, "canonicalText");
    if (canonicalText.codePointCount(0, canonicalText.length()) > maxInputCodePoints) {
      throw new IllegalArgumentException(
          "input exceeds maximum of " + maxInputCodePoints + " code points");
    }
    if (canonicalText.isEmpty() || !isAsciiWord(canonicalText)) {
      return List.of();
    }

    byte[] codeBytes = soundex(canonicalText).getBytes(StandardCharsets.US_ASCII);
    try {
      return List.of(
          new ProtectedToken(
              strategyId(transformId), formatVersion(), indexer.createToken(indexKey, codeBytes)));
    } finally {
      Arrays.fill(codeBytes, (byte) 0);
    }
  }

  private static boolean isAsciiWord(String text) {
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z'))) {
        return false;
      }
    }
    return true;
  }

  private static String soundex(String text) {
    String uppercase = text.toUpperCase(java.util.Locale.ROOT);
    StringBuilder code = new StringBuilder(4).append(uppercase.charAt(0));
    int previous = soundexClass(uppercase.charAt(0));
    for (int index = 1; index < uppercase.length() && code.length() < 4; index++) {
      char value = uppercase.charAt(index);
      int current = soundexClass(value);
      if (current != 0 && current != previous) {
        code.append(current);
      }
      if (isResettingVowel(value)) {
        previous = 0;
      } else if (value != 'H' && value != 'W') {
        previous = current;
      }
    }
    while (code.length() < 4) {
      code.append('0');
    }
    return code.toString();
  }

  private static int soundexClass(char value) {
    return switch (value) {
      case 'B', 'F', 'P', 'V' -> 1;
      case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> 2;
      case 'D', 'T' -> 3;
      case 'L' -> 4;
      case 'M', 'N' -> 5;
      case 'R' -> 6;
      default -> 0;
    };
  }

  private static boolean isResettingVowel(char value) {
    return value == 'A'
        || value == 'E'
        || value == 'I'
        || value == 'O'
        || value == 'U'
        || value == 'Y';
  }

  private static String requireTransformId(String transformId) {
    if (transformId.isBlank() || transformId.length() > 64) {
      throw new IllegalArgumentException("transformId must contain 1 to 64 characters");
    }
    return transformId;
  }
}
