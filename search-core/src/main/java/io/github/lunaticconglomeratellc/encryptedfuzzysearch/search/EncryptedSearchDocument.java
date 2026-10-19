package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Encrypted field and its protected tokens prepared for persistence. */
public record EncryptedSearchDocument(
    String tenantId,
    String fieldId,
    String recordId,
    String keyId,
    String transformVersion,
    byte[] ciphertext,
    List<ProtectedToken> protectedTokens) {
  public EncryptedSearchDocument {
    requireIdentifier(tenantId, "tenantId");
    requireIdentifier(fieldId, "fieldId");
    requireIdentifier(recordId, "recordId");
    requireIdentifier(keyId, "keyId");
    requireIdentifier(transformVersion, "transformVersion");
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (ciphertext.length == 0) {
      throw new IllegalArgumentException("ciphertext must not be empty");
    }
    ciphertext = ciphertext.clone();
    protectedTokens = List.copyOf(Objects.requireNonNull(protectedTokens, "protectedTokens"));
    if (new HashSet<>(protectedTokens).size() != protectedTokens.size()) {
      throw new IllegalArgumentException("protectedTokens must be unique");
    }
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  private static void requireIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must contain 1 to 256 non-blank characters");
    }
  }
}
