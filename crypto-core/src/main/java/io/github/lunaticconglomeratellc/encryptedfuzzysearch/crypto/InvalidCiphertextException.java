package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import java.security.GeneralSecurityException;

/** Indicates that a ciphertext envelope is malformed, unsupported, or unauthenticated. */
public final class InvalidCiphertextException extends GeneralSecurityException {
  private static final long serialVersionUID = 1L;

  InvalidCiphertextException() {
    super("Ciphertext is invalid or could not be authenticated");
  }
}
