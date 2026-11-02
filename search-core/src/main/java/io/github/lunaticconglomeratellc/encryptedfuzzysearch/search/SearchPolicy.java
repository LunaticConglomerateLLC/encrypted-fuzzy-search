package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;
import java.util.Set;

/** Explicit allowlist for strategy namespaces and their declared leakage. */
public record SearchPolicy(
    Set<String> allowedStrategyIds,
    Set<IndexLeakage> allowedLeakage,
    boolean aliasesAllowed,
    Set<String> allowedLanguageProviderIds,
    Set<String> allowedScorerIds) {
  private static final Set<String> PRODUCTION_STRATEGY_IDS =
      Set.of("efs-exact", "efs-ngram-2", "efs-ngram-3");
  private static final Set<IndexLeakage> PRODUCTION_LEAKAGE =
      Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP);

  public SearchPolicy {
    Objects.requireNonNull(allowedStrategyIds, "allowedStrategyIds");
    Objects.requireNonNull(allowedLeakage, "allowedLeakage");
    Objects.requireNonNull(allowedLanguageProviderIds, "allowedLanguageProviderIds");
    Objects.requireNonNull(allowedScorerIds, "allowedScorerIds");
    allowedStrategyIds = Set.copyOf(allowedStrategyIds);
    allowedLeakage = Set.copyOf(allowedLeakage);
    allowedLanguageProviderIds = Set.copyOf(allowedLanguageProviderIds);
    allowedScorerIds = Set.copyOf(allowedScorerIds);
    if (allowedStrategyIds.isEmpty()) {
      throw new IllegalArgumentException("at least one strategy ID must be allowed");
    }
    if (allowedStrategyIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("allowed strategy IDs must not be blank");
    }
    if (allowedLanguageProviderIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("allowed language provider IDs must not be blank");
    }
    if (allowedScorerIds.isEmpty()
        || allowedScorerIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("allowed scorer IDs must not be empty or blank");
    }
  }

  /** Creates an explicit policy for index, leakage, aliases, and language providers. */
  public SearchPolicy(
      Set<String> allowedStrategyIds,
      Set<IndexLeakage> allowedLeakage,
      boolean aliasesAllowed,
      Set<String> allowedLanguageProviderIds) {
    this(
        allowedStrategyIds,
        allowedLeakage,
        aliasesAllowed,
        allowedLanguageProviderIds,
        Set.of(CodePointLevenshteinScorer.ID));
  }

  /** Creates a strategy-only policy; aliases and language providers remain disabled. */
  public SearchPolicy(Set<String> allowedStrategyIds, Set<IndexLeakage> allowedLeakage) {
    this(
        allowedStrategyIds, allowedLeakage, false, Set.of(), Set.of(CodePointLevenshteinScorer.ID));
  }

  /** Owner-accepted production baseline: exact equality and bigram/trigram indexes only. */
  public static SearchPolicy productionDefault() {
    return new SearchPolicy(
        PRODUCTION_STRATEGY_IDS,
        PRODUCTION_LEAKAGE,
        false,
        Set.of(),
        Set.of(CodePointLevenshteinScorer.ID));
  }

  public boolean allows(ProtectedIndexStrategy strategy) {
    Objects.requireNonNull(strategy, "strategy");
    return allowedStrategyIds.contains(strategy.strategyId(null))
        && allowedLeakage.containsAll(strategy.leakageMetadata());
  }

  public boolean allowsLanguageProvider(String providerId) {
    Objects.requireNonNull(providerId, "providerId");
    return allowedLanguageProviderIds.contains(providerId);
  }

  public boolean allowsScorer(FinalScorer scorer) {
    Objects.requireNonNull(scorer, "scorer");
    return allowedScorerIds.contains(scorer.id());
  }

  /** Returns a copy that explicitly enables the named experimental scorers. */
  public SearchPolicy allowingScorers(Set<String> scorerIds) {
    Objects.requireNonNull(scorerIds, "scorerIds");
    java.util.HashSet<String> combined = new java.util.HashSet<>(allowedScorerIds);
    combined.addAll(scorerIds);
    return new SearchPolicy(
        allowedStrategyIds, allowedLeakage, aliasesAllowed, allowedLanguageProviderIds, combined);
  }

  void requireAllowed(ProtectedIndexStrategy strategy) {
    if (!allows(strategy)) {
      throw new IllegalArgumentException(
          "search policy does not allow strategy " + strategy.strategyId(null));
    }
  }
}
