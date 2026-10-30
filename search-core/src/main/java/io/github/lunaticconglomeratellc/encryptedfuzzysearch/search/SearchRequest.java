package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Validated caller request for an accuracy-contract search. */
public record SearchRequest(
    String tenantId,
    String fieldId,
    String keyId,
    String query,
    SearchAccuracyContract accuracyContract,
    int candidateLimit,
    double minimumSimilarity) {
  public SearchRequest {
    requireIdentifier(tenantId, "tenantId");
    requireIdentifier(fieldId, "fieldId");
    requireIdentifier(keyId, "keyId");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(accuracyContract, "accuracyContract");
    if (candidateLimit < 1 || candidateLimit > EncryptedFuzzySearchService.MAX_CANDIDATE_LIMIT) {
      throw new IllegalArgumentException(
          "candidateLimit must be between 1 and "
              + EncryptedFuzzySearchService.MAX_CANDIDATE_LIMIT);
    }
    if (!Double.isFinite(minimumSimilarity) || minimumSimilarity < 0.0 || minimumSimilarity > 1.0) {
      throw new IllegalArgumentException(
          "minimumSimilarity must be finite and between 0.0 and 1.0");
    }
    if (accuracyContract.mode() == SearchMode.EXACT && minimumSimilarity != 1.0) {
      throw new IllegalArgumentException("exact mode requires minimumSimilarity to equal 1.0");
    }
  }

  public static SearchRequest exact(
      String tenantId, String fieldId, String keyId, String query, int candidateLimit) {
    return new SearchRequest(
        tenantId, fieldId, keyId, query, SearchAccuracyContract.exact(), candidateLimit, 1.0);
  }

  public static SearchRequest boundedFuzzy(
      String tenantId,
      String fieldId,
      String keyId,
      String query,
      int maximumEditDistance,
      int candidateLimit,
      double minimumSimilarity) {
    return new SearchRequest(
        tenantId,
        fieldId,
        keyId,
        query,
        SearchAccuracyContract.boundedFuzzy(maximumEditDistance),
        candidateLimit,
        minimumSimilarity);
  }

  public static SearchRequest approximate(
      String tenantId,
      String fieldId,
      String keyId,
      String query,
      int candidateLimit,
      double minimumSimilarity) {
    return new SearchRequest(
        tenantId,
        fieldId,
        keyId,
        query,
        SearchAccuracyContract.approximate(),
        candidateLimit,
        minimumSimilarity);
  }

  private static void requireIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must contain 1 to 256 non-blank characters");
    }
  }
}
