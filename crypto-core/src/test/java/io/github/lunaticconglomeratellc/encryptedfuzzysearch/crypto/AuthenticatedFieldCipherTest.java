package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AuthenticatedFieldCipherTest {
  private static final byte[] ASSOCIATED_DATA =
      "tenant-a|email|record-7".getBytes(StandardCharsets.UTF_8);

  private AuthenticatedFieldCipher cipher;
  private SecretKey key;

  @BeforeMethod
  public void setUp() {
    cipher = new AuthenticatedFieldCipher();
    key = new SecretKeySpec(new byte[32], "AES");
  }

  @Test
  public void roundTripsEmptyPlaintext() throws Exception {
    byte[] encrypted = cipher.encrypt(key, new byte[0], ASSOCIATED_DATA);

    assertEquals(cipher.decrypt(key, encrypted, ASSOCIATED_DATA), new byte[0]);
  }

  @Test
  public void roundTripsWithoutAssociatedData() throws Exception {
    byte[] plaintext = new byte[] {9, 8, 7};
    byte[] emptyAssociatedData = new byte[0];

    byte[] encrypted = cipher.encrypt(key, plaintext, emptyAssociatedData);

    assertEquals(cipher.decrypt(key, encrypted, emptyAssociatedData), plaintext);
  }

  @Test
  public void roundTripsArbitraryBinaryPlaintext() throws Exception {
    byte[] plaintext = new byte[] {0, 1, -1, 42, 0, -128};

    byte[] encrypted = cipher.encrypt(key, plaintext, ASSOCIATED_DATA);

    assertEquals(cipher.decrypt(key, encrypted, ASSOCIATED_DATA), plaintext);
  }

  @Test
  public void roundTripsUtf8TextBytes() throws Exception {
    byte[] plaintext = "café — Привет — 東京".getBytes(StandardCharsets.UTF_8);

    byte[] encrypted = cipher.encrypt(key, plaintext, ASSOCIATED_DATA);

    assertEquals(cipher.decrypt(key, encrypted, ASSOCIATED_DATA), plaintext);
  }

  @Test
  public void encryptionUsesDifferentNoncesForRepeatedPlaintext() throws Exception {
    byte[] plaintext = "same value".getBytes(StandardCharsets.UTF_8);

    byte[] first = cipher.encrypt(key, plaintext, ASSOCIATED_DATA);
    byte[] second = cipher.encrypt(key, plaintext, ASSOCIATED_DATA);

    assertFalse(Arrays.equals(first, second));
  }

  @Test
  public void rejectsModifiedHeaderNonceCiphertextAndTag() throws Exception {
    byte[] encrypted = cipher.encrypt(key, new byte[] {1, 2, 3, 4}, ASSOCIATED_DATA);
    int[] tamperedOffsets = {0, 3, 4, 16, encrypted.length - 1};

    for (int offset : tamperedOffsets) {
      byte[] tampered = encrypted.clone();
      tampered[offset] ^= 1;

      assertThrows(
          InvalidCiphertextException.class, () -> cipher.decrypt(key, tampered, ASSOCIATED_DATA));
    }
  }

  @Test
  public void rejectsWrongKeyAndAssociatedData() throws Exception {
    byte[] encrypted = cipher.encrypt(key, new byte[] {5, 6}, ASSOCIATED_DATA);
    byte[] wrongKeyBytes = new byte[32];
    wrongKeyBytes[0] = 1;
    SecretKey wrongKey = new SecretKeySpec(wrongKeyBytes, "AES");

    assertThrows(
        InvalidCiphertextException.class,
        () -> cipher.decrypt(wrongKey, encrypted, ASSOCIATED_DATA));
    assertThrows(
        InvalidCiphertextException.class,
        () ->
            cipher.decrypt(
                key, encrypted, "tenant-b|email|record-7".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void rejectsMalformedAndUnsupportedEnvelopes() throws Exception {
    byte[] encrypted = cipher.encrypt(key, new byte[0], ASSOCIATED_DATA);
    byte[] unsupportedVersion = encrypted.clone();
    unsupportedVersion[3] = 2;

    assertThrows(
        InvalidCiphertextException.class, () -> cipher.decrypt(key, new byte[0], ASSOCIATED_DATA));
    assertThrows(
        InvalidCiphertextException.class,
        () -> cipher.decrypt(key, Arrays.copyOf(encrypted, 19), ASSOCIATED_DATA));
    assertThrows(
        InvalidCiphertextException.class,
        () -> cipher.decrypt(key, unsupportedVersion, ASSOCIATED_DATA));
  }

  @Test
  public void rejectsNonAesAndNon256BitKeys() {
    SecretKey nonAesKey = new SecretKeySpec(new byte[32], "HmacSHA256");
    SecretKey shortKey = new SecretKeySpec(new byte[16], "AES");

    assertThrows(
        IllegalArgumentException.class,
        () -> cipher.encrypt(nonAesKey, new byte[0], ASSOCIATED_DATA));
    assertThrows(
        IllegalArgumentException.class,
        () -> cipher.encrypt(shortKey, new byte[0], ASSOCIATED_DATA));
  }
}
