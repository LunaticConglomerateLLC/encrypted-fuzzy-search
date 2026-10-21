package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Leakage properties that a protected-index strategy may expose to its storage observer. */
public enum IndexLeakage {
  /** Repeated values under the same scoped key produce equal protected tokens. */
  EQUALITY,
  /** Token repetition can reveal value or token frequency. */
  FREQUENCY,
  /** A query may reveal which records share one or more tokens. */
  TOKEN_OVERLAP,
  /** Distinct canonical values may map to the same token before cryptographic protection. */
  STRATEGY_COLLISION,
  /** Shared prefix tokens reveal that values have a common leading substring. */
  PREFIX_RELATION
}
