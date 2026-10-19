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
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ExactBlindIndexerTest {
  private ExactBlindIndexer indexer;
  private ScopedKeyDeriver keyDeriver;

  @BeforeMethod
  public void setUp() {
    indexer = new ExactBlindIndexer();
    byte[] root = new byte[32];
    Arrays.fill(root, (byte) 0x5a);
    SecretKey rootKey = new SecretKeySpec(root, "HmacSHA256");
    keyDeriver = new ScopedKeyDeriver(keyId -> rootKey);
  }

  @Test
  public void equalNormalizedValuesHaveStableTokensInSameScope() throws Exception {
    SecretKey key = keyDeriver.deriveIndexKey("key-v1", "tenant-a", "email");
    byte[] first = indexer.createToken(key, new byte[] {1, 2, 3});
    byte[] second = indexer.createToken(key, new byte[] {1, 2, 3});

    assertTrue(Arrays.equals(first, second));
    assertTrue(indexer.isSupportedToken(first));
  }

  @Test
  public void changedValuesProduceDifferentTokens() throws Exception {
    SecretKey key = keyDeriver.deriveIndexKey("key-v1", "tenant-a", "email");

    assertFalse(
        Arrays.equals(
            indexer.createToken(key, "alice".getBytes(StandardCharsets.UTF_8)),
            indexer.createToken(key, "alicia".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  public void tokensDifferAcrossTenantAndFieldScopes() throws Exception {
    byte[] value = "same normalized input".getBytes(StandardCharsets.UTF_8);
    byte[] baseline =
        indexer.createToken(keyDeriver.deriveIndexKey("key-v1", "tenant-a", "email"), value);

    assertFalse(
        Arrays.equals(
            baseline,
            indexer.createToken(keyDeriver.deriveIndexKey("key-v1", "tenant-b", "email"), value)));
    assertFalse(
        Arrays.equals(
            baseline,
            indexer.createToken(keyDeriver.deriveIndexKey("key-v1", "tenant-a", "phone"), value)));
  }

  @Test
  public void emitsVersionedOpaqueTokenAndDoesNotReturnPlaintext() throws Exception {
    byte[] plaintext = "private@example.test".getBytes(StandardCharsets.UTF_8);
    SecretKey key = keyDeriver.deriveIndexKey("key-v1", "tenant-a", "email");

    byte[] token = indexer.createToken(key, plaintext);

    assertEquals(indexer.formatVersion(), 1);
    assertEquals(token.length, 37);
    assertEquals(HexFormat.of().formatHex(Arrays.copyOf(token, 5)), "4546534901");
    assertFalse(Arrays.equals(token, plaintext));
    assertFalse(
        new String(token, StandardCharsets.UTF_8)
            .contains(new String(plaintext, StandardCharsets.UTF_8)));
    assertTrue(indexer.isSupportedToken(token));
  }

  @Test
  public void knownAnswerVectorMatchesVersionedHmacContract() throws Exception {
    byte[] keyBytes = new byte[32];
    for (int index = 0; index < keyBytes.length; index++) {
      keyBytes[index] = (byte) index;
    }
    SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");

    byte[] token = indexer.createToken(key, "test".getBytes(StandardCharsets.UTF_8));

    assertEquals(
        HexFormat.of().formatHex(token),
        "4546534901e890a3bdfe8a0f9b5c6cc02a278a7e839165210c2a9b3010b5da59eb987ae433");
  }

  @Test
  public void rejectsMalformedOrUnsupportedTokens() {
    assertFalse(indexer.isSupportedToken(new byte[37]));
    byte[] unknownVersion = HexFormat.of().parseHex("4546534902" + "00".repeat(32));
    assertFalse(indexer.isSupportedToken(unknownVersion));
    assertThrows(NullPointerException.class, () -> indexer.isSupportedToken(null));
  }

  @Test
  public void rejectsIncorrectIndexKeyAlgorithmOrLength() {
    SecretKey wrongAlgorithm = new SecretKeySpec(new byte[32], "AES");
    SecretKey shortKey = new SecretKeySpec(new byte[16], "HmacSHA256");

    assertThrows(
        IllegalArgumentException.class, () -> indexer.createToken(wrongAlgorithm, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> indexer.createToken(shortKey, new byte[0]));
  }
}
