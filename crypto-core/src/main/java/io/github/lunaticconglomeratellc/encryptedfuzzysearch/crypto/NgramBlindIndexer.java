package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Creates versioned, strategy-separated HMAC tokens for canonical text n-grams. */
public final class NgramBlindIndexer {
  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final byte[] MAGIC = "EFSN".getBytes(StandardCharsets.US_ASCII);
  private static final byte FORMAT_VERSION = 1;
  private static final int HEADER_LENGTH_BYTES = MAGIC.length + (3 * Byte.BYTES);
  private static final int TOKEN_LENGTH_BYTES = HEADER_LENGTH_BYTES + 32;

  /** Returns the n-gram token envelope version. */
  public int formatVersion() {
    return FORMAT_VERSION;
  }

  /**
   * Creates a token for one gram. The configured strategy size is part of the authenticated header,
   * so bigram and trigram strategies have separate token domains.
   *
   * @param indexKey tenant/field-scoped HMAC key
   * @param strategySize configured n-gram size (2 for bigrams or 3 for trigrams)
   * @param gram non-empty canonical text gram containing no more code points than strategySize
   * @return opaque EFSN v1 token
   * @throws GeneralSecurityException if HMAC-SHA-256 is unavailable
   */
  public byte[] createToken(SecretKey indexKey, int strategySize, String gram)
      throws GeneralSecurityException {
    validateKey(indexKey);
    Objects.requireNonNull(gram, "gram");
    if (strategySize != 2 && strategySize != 3) {
      throw new IllegalArgumentException("strategySize must be 2 or 3");
    }
    validateUnicodeScalars(gram);
    int codePointCount = gram.codePointCount(0, gram.length());
    if (codePointCount == 0 || codePointCount > strategySize) {
      throw new IllegalArgumentException(
          "gram size must be between 1 and strategySize code points");
    }

    byte[] gramBytes = gram.getBytes(StandardCharsets.UTF_8);
    byte[] header = createHeader(strategySize, codePointCount);
    byte[] tag = null;
    try {
      Mac mac = Mac.getInstance(HMAC_SHA_256);
      mac.init(indexKey);
      mac.update(header);
      tag = mac.doFinal(gramBytes);
      return ByteBuffer.allocate(TOKEN_LENGTH_BYTES).put(header).put(tag).array();
    } finally {
      Arrays.fill(gramBytes, (byte) 0);
      if (tag != null) {
        Arrays.fill(tag, (byte) 0);
      }
    }
  }

  /** Returns whether a token has the EFSN v1 shape and a supported strategy-size header. */
  public boolean isSupportedToken(byte[] token) {
    Objects.requireNonNull(token, "token");
    if (token.length != TOKEN_LENGTH_BYTES) {
      return false;
    }
    for (int index = 0; index < MAGIC.length; index++) {
      if (token[index] != MAGIC[index]) {
        return false;
      }
    }
    return token[MAGIC.length] == FORMAT_VERSION
        && (token[MAGIC.length + 1] == 2 || token[MAGIC.length + 1] == 3)
        && token[MAGIC.length + 2] >= 1
        && token[MAGIC.length + 2] <= token[MAGIC.length + 1];
  }

  private static byte[] createHeader(int strategySize, int codePointCount) {
    return ByteBuffer.allocate(HEADER_LENGTH_BYTES)
        .put(MAGIC)
        .put(FORMAT_VERSION)
        .put((byte) strategySize)
        .put((byte) codePointCount)
        .array();
  }

  private static void validateKey(SecretKey key) {
    Objects.requireNonNull(key, "indexKey");
    if (!HMAC_SHA_256.equalsIgnoreCase(key.getAlgorithm())) {
      throw new IllegalArgumentException("index key must use HMAC-SHA-256");
    }
    byte[] encodedKey = key.getEncoded();
    if (encodedKey != null && encodedKey.length != 32) {
      throw new IllegalArgumentException("index key must be 256 bits");
    }
  }

  private static void validateUnicodeScalars(String text) {
    for (int offset = 0; offset < text.length(); ) {
      char value = text.charAt(offset);
      if (Character.isHighSurrogate(value)) {
        if (offset + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(offset + 1))) {
          throw new IllegalArgumentException("gram contains an unpaired surrogate");
        }
        offset += 2;
      } else if (Character.isLowSurrogate(value)) {
        throw new IllegalArgumentException("gram contains an unpaired surrogate");
      } else {
        offset++;
      }
    }
  }
}
