package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts field bytes with AES-256-GCM in the versioned EFS envelope format. */
public final class AuthenticatedFieldCipher {
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final byte[] MAGIC = new byte[] {'E', 'F', 'S'};
  private static final byte FORMAT_VERSION = 1;
  private static final int NONCE_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / Byte.SIZE;
  private static final int HEADER_LENGTH_BYTES = MAGIC.length + Byte.BYTES;
  private static final int MIN_ENVELOPE_LENGTH =
      HEADER_LENGTH_BYTES + NONCE_LENGTH_BYTES + TAG_LENGTH_BYTES;
  private static final int AES_256_KEY_LENGTH_BYTES = 32;

  private final SecureRandom secureRandom;

  /** Creates a cipher using the platform's cryptographically secure random generator. */
  public AuthenticatedFieldCipher() {
    this(new SecureRandom());
  }

  AuthenticatedFieldCipher(SecureRandom secureRandom) {
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
  }

  /**
   * Encrypts plaintext bytes and returns an EFS v1 envelope.
   *
   * @param key an AES-256 key; opaque, non-exportable keys must be provisioned at 256 bits
   * @param plaintext bytes to encrypt; callers encode text as UTF-8 before calling
   * @param associatedData context to authenticate but not encrypt, such as tenant, field, and
   *     record identifiers; pass an empty array when no context is available
   * @return a new envelope containing the header, nonce, ciphertext, and authentication tag
   * @throws GeneralSecurityException if the platform cipher or key cannot be used
   */
  public byte[] encrypt(SecretKey key, byte[] plaintext, byte[] associatedData)
      throws GeneralSecurityException {
    validateKey(key);
    byte[] plaintextCopy = Objects.requireNonNull(plaintext, "plaintext").clone();
    byte[] associatedDataCopy = Objects.requireNonNull(associatedData, "associatedData").clone();

    byte[] nonce = new byte[NONCE_LENGTH_BYTES];
    secureRandom.nextBytes(nonce);
    byte[] header = createHeader();

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
    cipher.updateAAD(header);
    cipher.updateAAD(associatedDataCopy);
    byte[] ciphertextAndTag = cipher.doFinal(plaintextCopy);

    ByteBuffer envelope =
        ByteBuffer.allocate(HEADER_LENGTH_BYTES + nonce.length + ciphertextAndTag.length);
    envelope.put(header);
    envelope.put(nonce);
    envelope.put(ciphertextAndTag);
    return envelope.array();
  }

  /**
   * Authenticates and decrypts an EFS v1 envelope.
   *
   * <p>Malformed, unsupported, or unauthenticated envelopes all fail with {@link
   * InvalidCiphertextException}; callers must not expose distinctions to untrusted users.
   *
   * @param key the AES-256 key used to encrypt the value; opaque keys must be provisioned at 256
   *     bits
   * @param envelope encoded EFS v1 envelope
   * @param associatedData the same context supplied during encryption
   * @return the authenticated plaintext bytes
   * @throws GeneralSecurityException if the platform cipher or key cannot be used
   * @throws InvalidCiphertextException if the envelope is malformed, unsupported, or fails
   *     authentication
   */
  public byte[] decrypt(SecretKey key, byte[] envelope, byte[] associatedData)
      throws GeneralSecurityException {
    validateKey(key);
    byte[] envelopeCopy = Objects.requireNonNull(envelope, "envelope").clone();
    byte[] associatedDataCopy = Objects.requireNonNull(associatedData, "associatedData").clone();

    if (envelopeCopy.length < MIN_ENVELOPE_LENGTH) {
      throw new InvalidCiphertextException();
    }

    ByteBuffer encoded = ByteBuffer.wrap(envelopeCopy);
    byte[] header = new byte[HEADER_LENGTH_BYTES];
    encoded.get(header);
    if (!isSupportedHeader(header)) {
      throw new InvalidCiphertextException();
    }

    byte[] nonce = new byte[NONCE_LENGTH_BYTES];
    encoded.get(nonce);
    byte[] ciphertextAndTag = new byte[encoded.remaining()];
    encoded.get(ciphertextAndTag);

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
    cipher.updateAAD(header);
    cipher.updateAAD(associatedDataCopy);
    try {
      return cipher.doFinal(ciphertextAndTag);
    } catch (AEADBadTagException exception) {
      throw new InvalidCiphertextException();
    }
  }

  private static void validateKey(SecretKey key) {
    Objects.requireNonNull(key, "key");
    if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
      throw new IllegalArgumentException("key must use AES");
    }

    byte[] encodedKey = key.getEncoded();
    if (encodedKey != null) {
      if (encodedKey.length != AES_256_KEY_LENGTH_BYTES) {
        throw new IllegalArgumentException("key must be 256 bits");
      }
    }
  }

  private static byte[] createHeader() {
    return ByteBuffer.allocate(HEADER_LENGTH_BYTES).put(MAGIC).put(FORMAT_VERSION).array();
  }

  private static boolean isSupportedHeader(byte[] header) {
    for (int index = 0; index < MAGIC.length; index++) {
      if (header[index] != MAGIC[index]) {
        return false;
      }
    }
    return header[MAGIC.length] == FORMAT_VERSION;
  }
}
