package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Soft TF-IDF with candidate-local document frequencies and one-to-one fuzzy token matches. */
public final class SoftTfIdfScorer implements FinalScorer {
  public static final String ID = "soft-tfidf-candidate-local-v1";
  private final double tokenSimilarityThreshold;
  private final CodePointLevenshteinScorer tokenScorer = new CodePointLevenshteinScorer();

  public SoftTfIdfScorer(double tokenSimilarityThreshold) {
    if (!Double.isFinite(tokenSimilarityThreshold)
        || tokenSimilarityThreshold < 0.0
        || tokenSimilarityThreshold > 1.0) {
      throw new IllegalArgumentException("tokenSimilarityThreshold must be in [0, 1]");
    }
    this.tokenSimilarityThreshold = tokenSimilarityThreshold;
  }

  public SoftTfIdfScorer() {
    this(0.8);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public double score(String query, String candidate) {
    return scoreCandidates(List.of(query), List.of(List.of(candidate))).getFirst();
  }

  @Override
  public List<Double> scoreCandidates(List<String> queryForms, List<List<String>> candidateForms) {
    Objects.requireNonNull(queryForms, "queryForms");
    Objects.requireNonNull(candidateForms, "candidateForms");
    if (queryForms.isEmpty()) {
      throw new IllegalArgumentException("at least one query form is required");
    }
    if (candidateForms.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> queryTerms = SimilarityTokens.frequencies(queryForms);
    validateTokenLengths(queryTerms);
    if (queryTerms.values().stream().mapToInt(Integer::intValue).sum()
        > ScoringLimits.MAX_CANDIDATE_SET_TOKENS) {
      throw new IllegalArgumentException("Soft TF-IDF query exceeds the token work limit");
    }
    List<Map<String, Integer>> documents = new ArrayList<>(candidateForms.size());
    Map<String, Integer> documentFrequency = new HashMap<>();
    int totalCandidateTokens = 0;
    for (List<String> forms : candidateForms) {
      Objects.requireNonNull(forms, "candidate forms");
      if (forms.isEmpty()) {
        throw new IllegalArgumentException("at least one candidate form is required");
      }
      Map<String, Integer> terms = SimilarityTokens.frequencies(forms);
      validateTokenLengths(terms);
      documents.add(terms);
      totalCandidateTokens += terms.values().stream().mapToInt(Integer::intValue).sum();
      if (totalCandidateTokens > ScoringLimits.MAX_CANDIDATE_SET_TOKENS) {
        throw new IllegalArgumentException(
            "Soft TF-IDF candidate set exceeds the token work limit");
      }
      new HashSet<>(terms.keySet()).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
    }
    if (queryTerms.isEmpty()) {
      return documents.stream().map(doc -> doc.isEmpty() ? 1.0 : 0.0).toList();
    }
    List<Double> scores = new ArrayList<>(documents.size());
    long comparisons = 0;
    for (Map<String, Integer> document : documents) {
      Set<String> available = new HashSet<>(document.keySet());
      double numerator = 0.0;
      double denominator = 0.0;
      for (Map.Entry<String, Integer> queryTerm : queryTerms.entrySet()) {
        double idf = idf(queryTerm.getKey(), candidateForms.size(), documentFrequency);
        double queryWeight = idf * queryTerm.getValue();
        denominator += queryWeight;
        String bestToken = null;
        double bestSimilarity = 0.0;
        for (String candidateToken : available) {
          comparisons++;
          if (comparisons > ScoringLimits.MAX_SOFT_TFIDF_COMPARISONS) {
            throw new IllegalArgumentException("Soft TF-IDF exceeded its token comparison limit");
          }
          double similarity = tokenScorer.score(queryTerm.getKey(), candidateToken);
          if (similarity >= tokenSimilarityThreshold && similarity > bestSimilarity) {
            bestSimilarity = similarity;
            bestToken = candidateToken;
          }
        }
        if (bestToken != null) {
          available.remove(bestToken);
          numerator += queryWeight * bestSimilarity;
        }
      }
      scores.add(denominator == 0.0 ? (document.isEmpty() ? 1.0 : 0.0) : numerator / denominator);
    }
    return scores.stream().map(FinalScorer::requireScore).toList();
  }

  private static double idf(String term, int corpusSize, Map<String, Integer> documentFrequency) {
    int frequency = documentFrequency.getOrDefault(term, 0);
    return Math.log(1.0 + (double) corpusSize / (1.0 + frequency));
  }

  private static void validateTokenLengths(Map<String, Integer> terms) {
    if (terms.keySet().stream()
        .anyMatch(
            term ->
                term.codePointCount(0, term.length())
                    > ScoringLimits.MAX_SOFT_TFIDF_TOKEN_CODE_POINTS)) {
      throw new IllegalArgumentException("Soft TF-IDF tokens exceed the 128-code-point limit");
    }
  }
}
