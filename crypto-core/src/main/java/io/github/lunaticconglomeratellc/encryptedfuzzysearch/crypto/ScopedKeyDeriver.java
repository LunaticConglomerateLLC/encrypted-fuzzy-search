package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Derives purpose- and scope-separated field-encryption and blind-index keys. */
public final class ScopedKeyDeriver {
  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final byte[] HKDF_SALT = new byte[32];
  private static final byte[] CONTEXT_LABEL =
      "encrypted-fuzzy-search/key-derivation".getBytes(StandardCharsets.US_ASCII);
  private static final byte CONTEXT_VERSION = 1;
  private static final byte ENCRYPTION_PURPOSE = 1;
  private static final byte INDEX_PURPOSE = 2;
  private static final int DERIVED_KEY_LENGTH_BYTES = 32;

  private final MasterKeyProvider masterKeyProvider;

  /** Creates a deriver backed by the application's root-key provider. */
  public ScopedKeyDeriver(MasterKeyProvider masterKeyProvider) {
    this.masterKeyProvider = Objects.requireNonNull(masterKeyProvider, "masterKeyProvider");
  }

  /** Derives the AES-256 key for one key-version, tenant, and field scope. */
  public SecretKey deriveEncryptionKey(String keyId, String tenantId, String fieldId)
      throws GeneralSecurityException {
    return derive(keyId, tenantId, fieldId, ENCRYPTION_PURPOSE, "AES");
  }

  /** Derives the HMAC-SHA-256 key for one key-version, tenant, and field scope. */
  public SecretKey deriveIndexKey(String keyId, String tenantId, String fieldId)
      throws GeneralSecurityException {
    return derive(keyId, tenantId, fieldId, INDEX_PURPOSE, HMAC_SHA_256);
  }

  private SecretKey derive(
      String keyId, String tenantId, String fieldId, byte purpose, String outputAlgorithm)
      throws GeneralSecurityException {
    byte[] context = encodeContext(keyId, tenantId, fieldId, purpose);
    SecretKey masterKey =
        Objects.requireNonNull(masterKeyProvider.getMasterKey(keyId), "masterKey");
    if (!HMAC_SHA_256.equalsIgnoreCase(masterKey.getAlgorithm())) {
      throw new IllegalArgumentException("master key must support HMAC-SHA-256");
    }
    byte[] encodedMasterKey = masterKey.getEncoded();
    if (encodedMasterKey == null) {
      throw new GeneralSecurityException("master key must be extractable for HKDF");
    }
    if (encodedMasterKey.length < DERIVED_KEY_LENGTH_BYTES) {
      throw new IllegalArgumentException("master key must contain at least 256 bits");
    }

    byte[] inputKeyMaterial = encodedMasterKey.clone();
    byte[] outputKey = null;
    try {
      outputKey = HkdfSha256.derive(inputKeyMaterial, HKDF_SALT, context, DERIVED_KEY_LENGTH_BYTES);
      return new SecretKeySpec(outputKey, outputAlgorithm);
    } finally {
      Arrays.fill(context, (byte) 0);
      Arrays.fill(inputKeyMaterial, (byte) 0);
      if (outputKey != null) {
        Arrays.fill(outputKey, (byte) 0);
      }
    }
  }

  private static byte[] encodeContext(String keyId, String tenantId, String fieldId, byte purpose) {
    byte[] keyIdBytes = requireIdentifier(keyId, "keyId");
    byte[] tenantBytes = requireIdentifier(tenantId, "tenantId");
    byte[] fieldBytes = requireIdentifier(fieldId, "fieldId");

    ByteBuffer context =
        ByteBuffer.allocate(
            CONTEXT_LABEL.length
                + Byte.BYTES
                + Byte.BYTES
                + (Integer.BYTES * 3)
                + keyIdBytes.length
                + tenantBytes.length
                + fieldBytes.length);
    context.put(CONTEXT_LABEL);
    context.put(CONTEXT_VERSION);
    context.put(purpose);
    putLengthPrefixed(context, keyIdBytes);
    putLengthPrefixed(context, tenantBytes);
    putLengthPrefixed(context, fieldBytes);
    Arrays.fill(keyIdBytes, (byte) 0);
    Arrays.fill(tenantBytes, (byte) 0);
    Arrays.fill(fieldBytes, (byte) 0);
    return context.array();
  }

  private static byte[] requireIdentifier(String identifier, String name) {
    Objects.requireNonNull(identifier, name);
    if (identifier.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    return identifier.getBytes(StandardCharsets.UTF_8);
  }

  private static void putLengthPrefixed(ByteBuffer buffer, byte[] value) {
    buffer.putInt(value.length);
    buffer.put(value);
  }
}
