package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** BM25-style ranker whose document statistics are scoped to this authorized candidate set. */
public final class CandidateLocalBm25Scorer implements FinalScorer {
  public static final String ID = "bm25-candidate-local-v1";
  private static final double K1 = 1.2;
  private static final double B = 0.75;

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
    if (tokenCount(queryTerms) > ScoringLimits.MAX_CANDIDATE_SET_TOKENS) {
      throw new IllegalArgumentException("BM25 query exceeds the token work limit");
    }
    List<Map<String, Integer>> documents = new ArrayList<>(candidateForms.size());
    Map<String, Integer> documentFrequency = new HashMap<>();
    int totalLength = 0;
    for (List<String> forms : candidateForms) {
      Objects.requireNonNull(forms, "candidate forms");
      if (forms.isEmpty()) {
        throw new IllegalArgumentException("at least one candidate form is required");
      }
      Map<String, Integer> terms = SimilarityTokens.frequencies(forms);
      documents.add(terms);
      totalLength += terms.values().stream().mapToInt(Integer::intValue).sum();
      if (totalLength > ScoringLimits.MAX_CANDIDATE_SET_TOKENS) {
        throw new IllegalArgumentException("BM25 candidate set exceeds the token work limit");
      }
      Set<String> uniqueTerms = new HashSet<>(terms.keySet());
      uniqueTerms.forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
    }
    if (queryTerms.isEmpty()) {
      return documents.stream().map(doc -> doc.isEmpty() ? 1.0 : 0.0).toList();
    }
    double averageLength = (double) totalLength / documents.size();
    double[] rawScores = new double[documents.size()];
    double maximum = 0.0;
    for (int i = 0; i < documents.size(); i++) {
      Map<String, Integer> document = documents.get(i);
      int documentLength = document.values().stream().mapToInt(Integer::intValue).sum();
      double score = 0.0;
      for (Map.Entry<String, Integer> queryTerm : queryTerms.entrySet()) {
        int termFrequency = document.getOrDefault(queryTerm.getKey(), 0);
        if (termFrequency == 0) {
          continue;
        }
        int docFrequency = documentFrequency.getOrDefault(queryTerm.getKey(), 0);
        double idf = Math.log(1.0 + (documents.size() - docFrequency + 0.5) / (docFrequency + 0.5));
        double lengthNorm =
            averageLength == 0.0 ? 1.0 : (1.0 - B) + B * ((double) documentLength / averageLength);
        double termScore = idf * (termFrequency * (K1 + 1.0)) / (termFrequency + K1 * lengthNorm);
        score += termScore * queryTerm.getValue();
      }
      rawScores[i] = score;
      maximum = Math.max(maximum, score);
    }
    List<Double> normalized = new ArrayList<>(rawScores.length);
    for (double score : rawScores) {
      normalized.add(maximum == 0.0 ? 0.0 : FinalScorer.requireScore(score / maximum));
    }
    return List.copyOf(normalized);
  }

  private static int tokenCount(Map<String, Integer> terms) {
    return terms.values().stream().mapToInt(Integer::intValue).sum();
  }
}
