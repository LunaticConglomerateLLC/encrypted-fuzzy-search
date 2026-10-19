package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 5869 HKDF using HMAC-SHA-256. */
final class HkdfSha256 {
  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int MAX_OUTPUT_LENGTH_BYTES = 255 * HASH_LENGTH_BYTES;

  private HkdfSha256() {}

  static byte[] derive(byte[] inputKeyMaterial, byte[] salt, byte[] info, int outputLength)
      throws GeneralSecurityException {
    Objects.requireNonNull(inputKeyMaterial, "inputKeyMaterial");
    Objects.requireNonNull(salt, "salt");
    Objects.requireNonNull(info, "info");
    if (outputLength < 1 || outputLength > MAX_OUTPUT_LENGTH_BYTES) {
      throw new IllegalArgumentException("outputLength is outside the HKDF-SHA-256 range");
    }

    byte[] effectiveSalt = salt.length == 0 ? new byte[HASH_LENGTH_BYTES] : salt.clone();
    byte[] pseudorandomKey = null;
    byte[] output = new byte[outputLength];
    byte[] previousBlock = new byte[0];
    try {
      Mac mac = Mac.getInstance(HMAC_SHA_256);
      mac.init(new SecretKeySpec(effectiveSalt, HMAC_SHA_256));
      pseudorandomKey = mac.doFinal(inputKeyMaterial);

      mac.init(new SecretKeySpec(pseudorandomKey, HMAC_SHA_256));
      int outputOffset = 0;
      for (int blockIndex = 1; outputOffset < outputLength; blockIndex++) {
        mac.update(previousBlock);
        mac.update(info);
        mac.update((byte) blockIndex);
        byte[] nextBlock = mac.doFinal();
        Arrays.fill(previousBlock, (byte) 0);
        previousBlock = nextBlock;
        int bytesToCopy = Math.min(previousBlock.length, outputLength - outputOffset);
        System.arraycopy(previousBlock, 0, output, outputOffset, bytesToCopy);
        outputOffset += bytesToCopy;
      }
      return output;
    } finally {
      Arrays.fill(effectiveSalt, (byte) 0);
      if (pseudorandomKey != null) {
        Arrays.fill(pseudorandomKey, (byte) 0);
      }
      Arrays.fill(previousBlock, (byte) 0);
    }
  }
}
