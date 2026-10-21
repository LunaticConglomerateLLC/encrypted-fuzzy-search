package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class NgramBlindIndexerTest {
  private final NgramBlindIndexer indexer = new NgramBlindIndexer();
  private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");

  @Test
  public void createsStableOpaqueVersionedToken() throws Exception {
    byte[] first = indexer.createToken(key, 2, "ab");
    byte[] second = indexer.createToken(key, 2, "ab");

    assertEquals(indexer.formatVersion(), 1);
    assertEquals(first.length, 39);
    assertEquals(HexFormat.of().formatHex(Arrays.copyOf(first, 7)), "4546534e010202");
    assertTrue(Arrays.equals(first, second));
    assertTrue(indexer.isSupportedToken(first));
    assertFalse(new String(first, StandardCharsets.UTF_8).contains("ab"));
  }

  @Test
  public void tokenDomainSeparatesGramAndStrategySize() throws Exception {
    byte[] bigram = indexer.createToken(key, 2, "ab");
    byte[] trigram = indexer.createToken(key, 3, "ab");
    byte[] differentGram = indexer.createToken(key, 2, "ac");

    assertFalse(Arrays.equals(bigram, trigram));
    assertFalse(Arrays.equals(bigram, differentGram));
  }

  @Test
  public void validatesTokenShapeAndSupportedHeaders() throws Exception {
    byte[] valid = indexer.createToken(key, 3, "ab");
    assertFalse(indexer.isSupportedToken(new byte[39]));
    assertFalse(indexer.isSupportedToken(nullSafeTokenWithHeader("XFSN", 1, 3, 2)));
    assertFalse(indexer.isSupportedToken(nullSafeTokenWithHeader("EFSN", 2, 3, 2)));
    assertFalse(indexer.isSupportedToken(nullSafeTokenWithHeader("EFSN", 1, 1, 1)));
    assertFalse(indexer.isSupportedToken(nullSafeTokenWithHeader("EFSN", 1, 3, 0)));
    assertFalse(indexer.isSupportedToken(nullSafeTokenWithHeader("EFSN", 1, 2, 3)));
    assertTrue(indexer.isSupportedToken(valid));
    assertThrows(NullPointerException.class, () -> indexer.isSupportedToken(null));
  }

  @Test
  public void rejectsInvalidKeyAndGramArguments() {
    SecretKey wrongAlgorithm = new SecretKeySpec(new byte[32], "AES");
    SecretKey shortKey = new SecretKeySpec(new byte[16], "HmacSHA256");

    assertThrows(
        IllegalArgumentException.class, () -> indexer.createToken(wrongAlgorithm, 2, "ab"));
    assertThrows(IllegalArgumentException.class, () -> indexer.createToken(shortKey, 2, "ab"));
    assertThrows(IllegalArgumentException.class, () -> indexer.createToken(key, 1, "a"));
    assertThrows(IllegalArgumentException.class, () -> indexer.createToken(key, 2, ""));
    assertThrows(IllegalArgumentException.class, () -> indexer.createToken(key, 2, "abc"));
    assertThrows(IllegalArgumentException.class, () -> indexer.createToken(key, 3, "\ud800"));
    assertThrows(NullPointerException.class, () -> indexer.createToken(key, 2, null));
    assertThrows(NullPointerException.class, () -> indexer.createToken(null, 2, "ab"));
  }

  private static byte[] nullSafeTokenWithHeader(
      String magic, int version, int strategySize, int actualSize) {
    byte[] token = new byte[39];
    byte[] magicBytes = magic.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magicBytes, 0, token, 0, magicBytes.length);
    token[4] = (byte) version;
    token[5] = (byte) strategySize;
    token[6] = (byte) actualSize;
    return token;
  }
}
