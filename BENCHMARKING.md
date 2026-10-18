# Matching corpus and benchmark methodology

**Status:** methodology only. No search implementation or empirical accuracy/performance results exist yet. No pass thresholds or service-level objectives are set here.

## Goals and comparison boundary

Measure candidate retrieval separately from authorized final verification. Candidate retrieval is the protected-index stage: its purpose is to include relevant records while controlling candidate volume. Final verification applies the configured similarity/scoring logic to authorized candidates. Report both stages so high final-match quality cannot conceal poor candidate recall, and a broad candidate set cannot appear accurate only because final verification filters it.

Compare strategies against the same versioned corpus, entity-disjoint query split, hardware/database setup, normalization version, and workload. Record all configuration choices and do not compare results produced with different candidate limits, transforms, or filtering rules as if they were equivalent.

## Corpus and fixture policy

### Default synthetic fixtures

Use generated records with stable synthetic entity IDs and non-sensitive values. The generator must be deterministic for a recorded seed and generator version. A fixture record contains a synthetic source value, entity ID, language/script tags when applicable, and transformation provenance. Generated query cases identify the source entity and the controlled transformation applied. Keep raw corpus content and generated search forms synthetic; never use secrets or actual customer data in committed fixtures.

Stratify generated cases to reveal different failure modes rather than only sampling random strings:

- Exact values and no-match queries.
- Single and multiple substitutions, insertions, deletions, and adjacent transpositions.
- Repeated characters, duplicated/missing tokens, and common-prefix/suffix changes.
- Short and longer values, with explicit boundaries at strategy-specific minimums and limits.
- Case, whitespace, punctuation, diacritics, and Unicode normalization variants.
- Latin and non-Latin scripts, mixed-script values, and script-preserving edits.
- Transliteration variants, including Chinese character-to-pinyin forms and ambiguous readings, clearly labeled as transliteration rather than translation.
- Hard negatives: distinct synthetic entities with similar spellings, shared tokens, common prefixes, or plausible transliterations.

Do not bake a language provider's expected output into generic correctness labels unless that transformation is explicitly part of the test case. Preserve the original source and transformation metadata so each query's expected relationship is auditable.

### Real-data fixtures

Synthetic data is the default and should be sufficient for repeatable regression tests. A real-data corpus may be used only when its source, license/terms, permitted purpose, consent or other lawful basis, privacy treatment, and redistribution rules have been reviewed and documented. Prefer public, non-personal data with clear reuse terms. Do not commit real or re-identifiable personal data, even if transformed for search. Keep approved private corpora outside the repository, encrypt them at rest, restrict access, and record a non-sensitive corpus identifier/checksum rather than values. Do not include plaintext values, normalized forms, tokens, or sensitive query content in benchmark logs or reports.

## Labels, splits, and reproducibility

Label relevance at the entity level, not just string equality. Each query has a set of relevant entity IDs and may have multiple relevant records. Keep exact identity, typo variant, normalization equivalence, transliteration equivalence, and hard-negative cases distinguishable with labels/tags. A translated name is not labeled as a match unless an explicit alias/translation relationship is supplied by the fixture.

Split by base entity before generating variants: no source entity or its transformed spellings may appear in both tuning and held-out evaluation. Use tuning data to choose strategy parameters and held-out data once for evaluation. If a later experiment changes the strategy based on held-out outcomes, define a new held-out corpus version or split and record that change.

Every run records the corpus ID and checksum, generator version and seed, split ID, source commit, index strategy and parameters, normalization/transliteration version, candidate limit, database and JDK versions, hardware, JVM options, cache state, and workload/concurrency. Report enough metadata to reproduce the experiment without recording sensitive values.

## Accuracy measures

For each query `q`, let `R(q)` be the labeled relevant entity/record set and `C(q, k)` be the candidate set returned under candidate limit `k`.

- **Candidate recall:** `|C(q, k) ∩ R(q)| / |R(q)|`. This measures whether retrieval included relevant items before final verification.
- **Candidate precision:** `|C(q, k) ∩ R(q)| / |C(q, k)|`. This measures how much of the retrieved candidate set is relevant; report candidate count alongside it.
- **Final-match recall and precision:** compute the same measures over the results accepted by final similarity verification, separately from candidate-stage values.
- **No-match and empty-set cases:** report false-positive counts/rates for labeled no-match queries. If `R(q)` or the returned set is empty, mark the corresponding per-query ratio undefined and report the case count; do not silently assign a perfect score.

Report per-query distributions and both macro averages (equal weight per query) and micro averages (aggregate true positives/false positives/false negatives). Break results down by the corpus strata above, especially short strings, repeated characters, Unicode, and transliteration. Keep candidate recall, candidate precision, final recall, and final precision as separate fields and charts/tables. A single blended “accuracy” score is insufficient.

For example, if a fixture query has two relevant records and retrieval returns those two plus two irrelevant records, candidate recall is `2/2` while candidate precision is `2/4`; these are intentionally different measurements. This arithmetic example illustrates the definitions only and is not a result from an implemented strategy.

These are measurement definitions, not observed results. A future benchmark run must calculate them from actual strategy outputs and include the labeled held-out set and run metadata needed to audit the calculation.

## Storage and performance measures

Measure and report the following independently; do not combine them into an invented score:

- **Index size:** total persisted index bytes and bytes per record; also record token count per value, posting-list distribution, and ciphertext size separately. State whether database page/index overhead and compression are included.
- **Ingest/indexing throughput:** completed records per second and elapsed time to build the index, with record count and concurrency stated.
- **Query throughput:** completed queries per second under a stated workload and concurrency; do not infer it from isolated latency.
- **Latency:** report p50 and p95 query latency, sample count, warm-up approach, cache state, concurrency, and whether the measurement includes normalization, key access, database retrieval, decryption, and final verification. Break down these stages when possible.
- **Resource context:** record CPU, memory, storage, database configuration/version, JVM/runtime version, and relevant connection-pool settings so performance figures are interpretable.

Use the same deployment shape and workload when comparing strategies. Separate cold-start from warmed JVM/cache measurements. Preserve raw timing samples or a privacy-safe aggregate sufficient to recompute percentiles. Report failures, timeouts, and rejected/limited candidate sets rather than silently omitting them.

## Report format and decisions left open

Each report should contain a summary table per strategy and corpus stratum, separate precision/recall for retrieval and final verification, candidate-volume and index-size distributions, throughput, p50/p95 latency, run metadata, and limitations. Results must state whether they are exploratory, tuning, or held-out measurements.

The following remain open until owners review empirical baselines and intended deployments:

- Minimum acceptable recall or precision by mode and corpus stratum.
- Maximum candidate counts, index growth, p95 latency, and minimum throughput.
- Production dataset sizes, workload mix, concurrency, and hardware reference profile.
- Which languages/transliteration cases are release-critical and what labels constitute an acceptable match.
- Whether any real-data corpus is permitted and under what governance.

Do not claim an accuracy, scale, or latency guarantee until the threshold is agreed and the relevant held-out benchmark evidence exists.
