package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Candidate retrieval semantics supported by the current token repository contract. */
public enum CandidateMatchMode {
  /** Return records that match at least one token in the query plan. */
  ANY_SHARED_TOKEN
}
