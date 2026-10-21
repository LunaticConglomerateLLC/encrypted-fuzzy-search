package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Authorized plaintext result after candidate decryption and fuzzy verification. */
public record FuzzySearchResult(
    String recordId, String value, double similarity, int overlapCount) {
  public FuzzySearchResult {
    Objects.requireNonNull(recordId, "recordId");
    Objects.requireNonNull(value, "value");
    if (!Double.isFinite(similarity) || similarity < 0.0 || similarity > 1.0) {
      throw new IllegalArgumentException("similarity must be finite and between 0.0 and 1.0");
    }
    if (overlapCount < 1) {
      throw new IllegalArgumentException("overlapCount must be positive");
    }
  }
}
