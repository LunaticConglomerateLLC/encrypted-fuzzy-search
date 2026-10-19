package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.SecretKey;

/** Supplies root key material by an application-managed rotation identifier. */
@FunctionalInterface
public interface MasterKeyProvider {
  /**
   * Returns the root key associated with {@code keyId}.
   *
   * <p>The key must have algorithm {@code HmacSHA256}, be extractable for the RFC 5869 HKDF step,
   * and contain at least 256 bits of secret entropy. Implementations must protect key material and
   * must not log keys or key bytes. Non-exportable HSM keys require HKDF inside the HSM and are not
   * supported by this SPI version.
   *
   * @param keyId opaque, case-sensitive key rotation identifier
   * @return root key material; never {@code null}
   * @throws GeneralSecurityException if the key cannot be retrieved
   */
  SecretKey getMasterKey(String keyId) throws GeneralSecurityException;
}
