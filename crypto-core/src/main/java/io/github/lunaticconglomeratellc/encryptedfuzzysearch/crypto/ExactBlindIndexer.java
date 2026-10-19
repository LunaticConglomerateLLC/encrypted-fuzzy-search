package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Creates deterministic, versioned equality tokens from caller-normalized bytes. */
public final class ExactBlindIndexer {
  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final byte[] MAGIC = "EFSI".getBytes(StandardCharsets.US_ASCII);
  private static final byte FORMAT_VERSION = 1;
  private static final int HEADER_LENGTH_BYTES = MAGIC.length + Byte.BYTES;
  private static final int TOKEN_LENGTH_BYTES = HEADER_LENGTH_BYTES + 32;

  /** Returns the supported exact-token format version. */
  public int formatVersion() {
    return FORMAT_VERSION;
  }

  /**
   * Creates an opaque equality token for bytes already normalized by the caller.
   *
   * <p>The supplied key should be derived for its tenant and field scope by {@link
   * ScopedKeyDeriver#deriveIndexKey(String, String, String)}. Equal input bytes under the same key
   * produce the same token. The token exposes equality and frequency and must not be treated as
   * encrypted data.
   *
   * @param indexKey scoped HMAC-SHA-256 key
   * @param normalizedValue canonical bytes to tokenize
   * @return a new {@code EFSI} v1 token containing the authenticated header and HMAC-SHA-256 tag
   * @throws GeneralSecurityException if the platform HMAC implementation or key cannot be used
   */
  public byte[] createToken(SecretKey indexKey, byte[] normalizedValue)
      throws GeneralSecurityException {
    validateKey(indexKey);
    byte[] valueCopy = Objects.requireNonNull(normalizedValue, "normalizedValue").clone();
    byte[] header = createHeader();
    byte[] tag = null;
    try {
      Mac mac = Mac.getInstance(HMAC_SHA_256);
      mac.init(indexKey);
      mac.update(header);
      tag = mac.doFinal(valueCopy);
      return ByteBuffer.allocate(TOKEN_LENGTH_BYTES).put(header).put(tag).array();
    } finally {
      Arrays.fill(valueCopy, (byte) 0);
      if (tag != null) {
        Arrays.fill(tag, (byte) 0);
      }
    }
  }

  /** Returns whether the bytes have the exact-token v1 envelope shape and header. */
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
    return token[MAGIC.length] == FORMAT_VERSION;
  }

  private static byte[] createHeader() {
    return ByteBuffer.allocate(HEADER_LENGTH_BYTES).put(MAGIC).put(FORMAT_VERSION).array();
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
}
