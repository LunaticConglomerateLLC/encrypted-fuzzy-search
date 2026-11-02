package io.github.lunaticconglomeratellc.encryptedfuzzysearch.benchmark;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.CandidateLocalBm25Scorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.CodePointLevenshteinScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.DamerauLevenshteinScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.FinalScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SoftTfIdfScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.TokenSetScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.WeightedEditScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.WeightedNgramScorer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Compares final scorers over a bounded, deterministic candidate set without database costs. */
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ScoringBenchmark {
  @Param({"32", "128", "512"})
  public int textLength;

  @Param({"1", "10", "50"})
  public int candidateCount;

  @Param({
    "levenshtein-codepoint-v1",
    "damerau-levenshtein-osa-codepoint-v1",
    "weighted-edit-codepoint-v1",
    "weighted-ngram-jaccard-v1-n2",
    "token-set-jaccard-v1",
    "bm25-candidate-local-v1",
    "soft-tfidf-candidate-local-v1"
  })
  public String scorerId;

  private FinalScorer scorer;
  private String query;
  private List<List<String>> candidateForms;

  @Setup(Level.Trial)
  public void setup() {
    SplittableRandom random = new SplittableRandom(20261102L + textLength);
    StringBuilder generated = new StringBuilder(textLength);
    for (int index = 0; index < textLength; index++) {
      generated.append((char) ('a' + random.nextInt(26)));
    }
    String text = generated.toString();
    query = text.substring(0, text.length() - 1) + (text.endsWith("a") ? "b" : "a");
    scorer = scorer(scorerId);
    List<List<String>> forms = new ArrayList<>(candidateCount);
    for (int index = 0; index < candidateCount; index++) {
      forms.add(List.of(index == 0 ? text : mutate(text, index)));
    }
    candidateForms = List.copyOf(forms);
  }

  @Benchmark
  public List<Double> scoreCandidateSet() {
    return scorer.scoreCandidates(List.of(query), candidateForms);
  }

  private static String mutate(String value, int salt) {
    int index = Math.floorMod(salt * 31, value.length());
    char replacement = value.charAt(index) == 'z' ? 'y' : (char) (value.charAt(index) + 1);
    return value.substring(0, index) + replacement + value.substring(index + 1);
  }

  private static FinalScorer scorer(String id) {
    return switch (id) {
      case "levenshtein-codepoint-v1" -> new CodePointLevenshteinScorer();
      case "damerau-levenshtein-osa-codepoint-v1" -> new DamerauLevenshteinScorer();
      case "weighted-edit-codepoint-v1" -> new WeightedEditScorer(Map.of("1i", 0.2, "0o", 0.2));
      case "weighted-ngram-jaccard-v1-n2" -> new WeightedNgramScorer(2);
      case "token-set-jaccard-v1" -> new TokenSetScorer();
      case "bm25-candidate-local-v1" -> new CandidateLocalBm25Scorer();
      case "soft-tfidf-candidate-local-v1" -> new SoftTfIdfScorer(0.75);
      default -> throw new IllegalArgumentException("unknown scorer id: " + id);
    };
  }
}
