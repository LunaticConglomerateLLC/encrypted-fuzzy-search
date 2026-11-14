# Search modes and result guarantees

EFS separates candidate retrieval from final matching. A protected index selects records that may match. The application decrypts those candidates and applies the configured scorer and minimum similarity. The candidate limit applies before that final check, so a high final precision does not prove that retrieval found every relevant record.

## Exact

`SearchRequest.exact(...)` selects an exact-match index strategy and requires a similarity of `1.0`. Exact retrieval can be complete for canonical equality while the candidate limit remains large enough to include the complete posting list. Equality and frequency remain visible to an index observer.

## Bounded fuzzy

`SearchRequest.boundedFuzzy(..., maximumEditDistance, ...)` asks for candidates within a stated edit distance. The planner accepts only a configured strategy that proves that bound. The deletion-neighborhood strategy implements bounded candidate generation, but it is outside the owner-accepted production allowlist. It must not be described as enabled by default or enabled in production without a separate leakage decision.

## Approximate

`SearchRequest.approximate(...)` uses the configured protected strategies as best-effort candidate retrieval. The default production strategy set is exact, bigram, and trigram tokens. N-gram overlap is a heuristic: the candidate list can include many false positives and can omit matches after the candidate limit is reached.

Check the outcome fields rather than inferring completeness from the number of matches:

```java
SearchOutcome outcome = search.search(request);
boolean complete = outcome.candidateRetrievalComplete();
boolean limitReached = outcome.candidateLimitExceeded();
CandidateCompleteness guarantee = outcome.candidateCompleteness();
```

`candidateRetrievalComplete()` is true only when a strategy proves the requested contract and the repository limit did not truncate retrieval. Approximate retrieval reports `BEST_EFFORT`. Final verification can remove false positives from the returned candidates; it cannot recover an item that candidate retrieval never returned.

## Thresholds, candidate limits, and ranking

The candidate limit and minimum similarity are separate controls. A small limit lowers work but can cut off true matches. A lower similarity threshold accepts more verified candidates. The default scorer uses Unicode code-point Levenshtein similarity; alternate scorers and calibrated scores need their own held-out evaluation and explicit policy allowlisting.

Tune these controls against entity-disjoint test data that includes likely typos, hard negatives, short values, multilingual values where enabled, and no-match queries. Report candidate recall and precision separately from final precision and recall. See [the benchmark guide](../benchmarks/index.md).
