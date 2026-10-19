package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ScopedKeyDeriverTest {
  private ScopedKeyDeriver deriver;

  @BeforeMethod
  public void setUp() {
    byte[] root = new byte[32];
    Arrays.fill(root, (byte) 0x5a);
    SecretKey rootKey = new SecretKeySpec(root, "HmacSHA256");
    deriver = new ScopedKeyDeriver(keyId -> rootKey);
  }

  @Test
  public void derivesStableKeysForSameScope() throws Exception {
    SecretKey first = deriver.deriveEncryptionKey("key-v1", "tenant-a", "email");
    SecretKey second = deriver.deriveEncryptionKey("key-v1", "tenant-a", "email");

    assertEquals(first.getAlgorithm(), "AES");
    assertEquals(first.getEncoded().length, 32);
    assertEquals(first, second);
  }

  @Test
  public void separatesEncryptionAndIndexPurposes() throws Exception {
    SecretKey encryption = deriver.deriveEncryptionKey("key-v1", "tenant-a", "email");
    SecretKey index = deriver.deriveIndexKey("key-v1", "tenant-a", "email");

    assertEquals(index.getAlgorithm(), "HmacSHA256");
    assertEquals(index.getEncoded().length, 32);
    assertFalse(Arrays.equals(encryption.getEncoded(), index.getEncoded()));
  }

  @Test
  public void separatesKeysByTenantFieldAndRotationIdentifier() throws Exception {
    byte[] baseline = deriver.deriveEncryptionKey("key-v1", "tenant-a", "email").getEncoded();

    assertNotEquals(
        deriver.deriveEncryptionKey("key-v1", "tenant-b", "email").getEncoded(), baseline);
    assertNotEquals(
        deriver.deriveEncryptionKey("key-v1", "tenant-a", "phone").getEncoded(), baseline);
    assertNotEquals(
        deriver.deriveEncryptionKey("key-v2", "tenant-a", "email").getEncoded(), baseline);
  }

  @Test
  public void lengthPrefixesPreventAmbiguousScopeConcatenations() throws Exception {
    byte[] first = deriver.deriveIndexKey("a", "bc", "d").getEncoded();
    byte[] second = deriver.deriveIndexKey("ab", "c", "d").getEncoded();

    assertFalse(Arrays.equals(first, second));
  }

  @Test
  public void rejectsEmptyIdentifiers() {
    assertThrows(
        IllegalArgumentException.class, () -> deriver.deriveEncryptionKey("", "tenant-a", "email"));
    assertThrows(
        IllegalArgumentException.class, () -> deriver.deriveEncryptionKey("key-v1", "", "email"));
    assertThrows(
        IllegalArgumentException.class, () -> deriver.deriveIndexKey("key-v1", "tenant-a", ""));
  }

  @Test
  public void rejectsUnsupportedAndWeakMasterKeys() {
    ScopedKeyDeriver wrongAlgorithm =
        new ScopedKeyDeriver(keyId -> new SecretKeySpec(new byte[32], "HmacSHA512"));
    ScopedKeyDeriver shortKey =
        new ScopedKeyDeriver(keyId -> new SecretKeySpec(new byte[16], "HmacSHA256"));

    assertThrows(
        IllegalArgumentException.class,
        () -> wrongAlgorithm.deriveEncryptionKey("key-v1", "tenant-a", "email"));
    assertThrows(
        IllegalArgumentException.class,
        () -> shortKey.deriveIndexKey("key-v1", "tenant-a", "email"));
  }

  @Test
  public void propagatesProviderFailuresWithoutLoggingKeyMaterial() {
    ScopedKeyDeriver unavailable =
        new ScopedKeyDeriver(
            keyId -> {
              throw new GeneralSecurityException("key unavailable");
            });

    assertThrows(
        GeneralSecurityException.class,
        () -> unavailable.deriveEncryptionKey("key-v1", "tenant-a", "email"));
  }

  @Test
  public void keyIdentifierIsPassedToProviderForRotationSelection() throws Exception {
    String[] requestedKeyId = new String[1];
    SecretKey rootKey = new SecretKeySpec(new byte[32], "HmacSHA256");
    ScopedKeyDeriver recordingProvider =
        new ScopedKeyDeriver(
            keyId -> {
              requestedKeyId[0] = keyId;
              return rootKey;
            });

    recordingProvider.deriveIndexKey("rotation-2026-01", "tenant-a", "email");

    assertEquals(requestedKeyId[0], "rotation-2026-01");
  }
}
