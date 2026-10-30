package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Accuracy contract requested by a search caller. */
public enum SearchMode {
  /** Return exact canonical-value matches only. */
  EXACT,
  /** Retrieve every value within a bounded code-point Levenshtein distance, subject to caps. */
  BOUNDED_FUZZY,
  /** Retrieve likely matches; recall is empirical and is not guaranteed to be exhaustive. */
  APPROXIMATE
}
