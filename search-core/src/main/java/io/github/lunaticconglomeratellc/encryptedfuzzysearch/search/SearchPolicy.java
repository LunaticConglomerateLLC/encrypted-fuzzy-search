package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;
import java.util.Set;

/** Explicit allowlist for strategy namespaces and their declared leakage. */
public record SearchPolicy(Set<String> allowedStrategyIds, Set<IndexLeakage> allowedLeakage) {
  private static final Set<String> PRODUCTION_STRATEGY_IDS =
      Set.of("efs-exact", "efs-ngram-2", "efs-ngram-3");
  private static final Set<IndexLeakage> PRODUCTION_LEAKAGE =
      Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP);

  public SearchPolicy {
    Objects.requireNonNull(allowedStrategyIds, "allowedStrategyIds");
    Objects.requireNonNull(allowedLeakage, "allowedLeakage");
    allowedStrategyIds = Set.copyOf(allowedStrategyIds);
    allowedLeakage = Set.copyOf(allowedLeakage);
    if (allowedStrategyIds.isEmpty()) {
      throw new IllegalArgumentException("at least one strategy ID must be allowed");
    }
    if (allowedStrategyIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("allowed strategy IDs must not be blank");
    }
  }

  /** Owner-accepted production baseline: exact equality and bigram/trigram indexes only. */
  public static SearchPolicy productionDefault() {
    return new SearchPolicy(PRODUCTION_STRATEGY_IDS, PRODUCTION_LEAKAGE);
  }

  public boolean allows(ProtectedIndexStrategy strategy) {
    Objects.requireNonNull(strategy, "strategy");
    return allowedStrategyIds.contains(strategy.strategyId(null))
        && allowedLeakage.containsAll(strategy.leakageMetadata());
  }

  void requireAllowed(ProtectedIndexStrategy strategy) {
    if (!allows(strategy)) {
      throw new IllegalArgumentException(
          "search policy does not allow strategy " + strategy.strategyId(null));
    }
  }
}
