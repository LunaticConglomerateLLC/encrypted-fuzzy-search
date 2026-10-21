package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** A record retrieved by protected-token overlap, before authorized decryption and scoring. */
public record SearchCandidate(
    String recordId, String keyId, String transformVersion, byte[] ciphertext, int overlapCount) {
  public SearchCandidate {
    Objects.requireNonNull(recordId, "recordId");
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(transformVersion, "transformVersion");
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (ciphertext.length == 0) {
      throw new IllegalArgumentException("ciphertext must not be empty");
    }
    if (overlapCount < 1) {
      throw new IllegalArgumentException("overlapCount must be positive");
    }
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
