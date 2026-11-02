# Matching corpus and benchmark methodology

**Status:** methodology and exploratory baseline runner. No accuracy/latency thresholds or service-level objectives have been approved.

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

## Paired plaintext and EFS comparison contract

The v3.1 suite compares modes on the same deterministic synthetic records, entity split, queries, expected entity labels, normalization, n-gram semantics, candidate limit, scoring profile, database version/configuration, and load profile. Load the fixture independently for each mode and record the fixture seed and source revision. Compare absolute values and paired deltas; report percentage overhead only when the plaintext measurement is non-zero. A mode comparison is useful only when the correctness measures are reported alongside performance.

| Benchmark mode | Stored field payload | Stored search postings | Intended use |
| --- | --- | --- | --- |
| `PLAINTEXT_CONTROL` | Canonical plaintext | Raw canonical n-grams | Synthetic reference for the same search behavior without storage protection |
| `EFS_FULL` | AES-GCM ciphertext | HMAC-protected n-grams | Production EFS design under evaluation |
| `EFS_PAYLOAD_ONLY_DIAGNOSTIC` | AES-GCM ciphertext | Raw canonical n-grams | Optional synthetic-only diagnosis that separates payload encryption from protected-index costs |

`EFS_PAYLOAD_ONLY_DIAGNOSTIC` is a benchmark-only mode, leaks n-gram content, must use generated test data in a disposable local database, and must not be selectable through production application configuration. Never use customer or real personal data for either plaintext mode. Do not print payloads, normalized forms, postings, keys, or query text in logs or reports.

The primary correctness comparison reports candidate recall/precision/count before decrypt-and-verify and final precision/recall after verification. It also reports no-match false positives and candidate-cap saturation by query stratum. All modes use the same labels and acceptance threshold for a given profile; if actual match sets differ, report the difference instead of silently tuning one mode to match another. The benchmark is a comparator, not evidence that plaintext storage meets EFS privacy properties.

For each mode, separate fixture loading/indexing from query runs. For queries report latency distributions (p50, p95, p99), achieved query rate, failures/timeouts, candidate counts, and cap saturation. State whether each measurement includes normalization, key derivation/access, token generation, database retrieval, decryption, and final scoring. Run single-client and concurrent profiles; record load shape and concurrency. Treat cold-start and warmed steady-state measurements as distinct runs with an explicit reset/warmup procedure.

Report storage as payload bytes, logical posting bytes, total PostgreSQL relation/index bytes, and bytes per record. Keep JVM CPU/heap/GC and PostgreSQL resource/configuration context separate. Component-level JVM timings belong in JMH reports and must not be presented as end-to-end service latency. SQL-only `pgbench` results also remain separate from full Java/EFS request results.

Initial v3.1 reports establish reproducible baselines and relative costs only. No maximum latency, minimum throughput, acceptable encryption overhead, or capacity promise is a release gate until the owner approves a representative deployment workload and threshold.

## Exploratory PostgreSQL baseline

The repository provides a deterministic synthetic benchmark runner in the PostgreSQL adapter test source set. Start the disposable Compose database, then run `./gradlew --no-daemon :postgres-adapter:fuzzySearchBenchmark`. The runner seeds 400 unique synthetic positive records and 200 prefix-sharing hard negatives from seed `20261019`, evaluates 300 single-substitution queries and 50 no-match queries, and excludes 30 warm-up searches from timed samples. It emits aggregate metrics and runtime/database metadata only; it does not print synthetic values or protected tokens. It reports retrieval recall/precision separately from final-match recall/precision, as well as index/token frequency, latency, throughput, and PostgreSQL relation sizes. These numbers are a local exploratory baseline, not a release threshold, production estimate, or security guarantee.

### Concurrent PostgreSQL load profile

Run `./gradlew --no-daemon :postgres-adapter:postgresLoadBenchmark --args='--records=600 --candidate-limit=100 --concurrency=4 --duration-seconds=30 --warmup-seconds=5 --no-match-percent=10 --request-timeout-ms=30000'` with the Compose database running. The runner seeds each mode independently and sequentially, then runs the same typo/no-match mix against the Java service plus PostgreSQL adapter. Configure record count (two-thirds labeled positives, remainder hard negatives), candidate limit, fixed worker concurrency, warmup/measurement duration, no-match percentage, server-side PostgreSQL statement timeout, and mode (`both`, `EFS_FULL`, or `PLAINTEXT_CONTROL`). It reports first-search cold latency separately from warmed concurrent p50/p95/p99, completed QPS, failures, SQL timeouts, completed requests beyond the configured threshold, candidate volume/cap saturation, index throughput, JVM heap/GC/process CPU deltas, PostgreSQL version/configuration, and per-mode table sizes. Each JDBC connection sets `statement_timeout`, so that guard adds a small equal cost to both modes. Run concurrency 1, 4, 8, and 16 as separate invocations for an increasing-load profile. It has no HTTP layer; it measures direct Java service calls. Results are aggregate-only and use generated values and test keys. `docker compose -f compose.test.yaml down` removes the disposable service and its synthetic database contents.

The concurrent runner writes an aggregate JSON report to `postgres-adapter/build/reports/benchmark/postgres-load.json`; set `EFS_BENCHMARK_REPORT_FILE` to choose another path (relative paths resolve from `postgres-adapter/`). The versioned `efs.benchmark-report.v1` schema records UTC time, checked-out source revision and dirty-tree flag, generator/seed/counts, mode and workload settings, JDK/VM/host metadata, PostgreSQL version/configuration, and per-mode final recall/precision, no-match false positives, candidate counts/cap saturation, cold/warm latency, throughput, failures/timeouts, JVM, and relation-size metrics. A cold first-search failure is recorded and does not abort later measured phases. The report contains no query, record, normalized value, key, or token content. CI retains the one-second correctness smoke report as an artifact; the manual `Benchmark load profile` workflow runs the longer user-selected profile and retains its report. The comparison tables below are the human-readable report. Timing values are artifact data only: no numeric CI threshold is enforced until a representative deployment workload and owner-approved limits exist.

The runner now executes both `EFS_FULL` and `PLAINTEXT_CONTROL` against the same generated corpus and held-out queries. Before emitting the plaintext results, it requires the ordered candidate IDs, final accepted IDs, and no-match outcomes to agree with EFS. The plaintext table is test-source-only and is cleared and reseeded on each run. The comparison records logical posting/payload bytes and physical PostgreSQL relation sizes separately; the two logical byte measures do not include object headers, indexes, or database page overhead.

### Paired plaintext and EFS run (2026-11-02)

Environment: JDK 25.0.4.1, PostgreSQL 18.6 in the local Compose service, macOS/aarch64, 12 reported processors, and a 9 GiB JVM maximum heap. Same deterministic 600-record corpus, 300 single-substitution queries, 50 no-match queries, candidate limit 100, and score threshold 0.80. One local, single-threaded run reported:

| Measure | `PLAINTEXT_CONTROL` | `EFS_FULL` |
| --- | ---: | ---: |
| Candidate recall / precision | 1.000 / 0.010 | 1.000 / 0.010 |
| Final recall / precision | 1.000 / 1.000 | 1.000 / 1.000 |
| No-match false positives | 0/50 | 0/50 |
| Candidate cap saturation | 300/300 | 300/300 |
| Candidate retrieval latency, p50 / p95 / p99 | 8.077 / 12.647 / 16.204 ms | 8.024 / 10.478 / 12.876 ms |
| Full search latency, p50 / p95 / p99 | 8.225 / 13.336 / 16.405 ms | 8.660 / 11.258 / 14.200 ms |
| Full search throughput | 111.68 queries/s | 112.64 queries/s |
| Indexing throughput | 101.46 records/s | 117.81 records/s |
| Postings / distinct tokens | 20,256 / 6,859 raw | 20,256 / 6,859 protected |
| Logical posting bytes | 59,710 raw token-value bytes | 788,784 protected-token bytes |
| Logical payload bytes | 10,800 plaintext bytes | 30,000 ciphertext bytes |
| PostgreSQL token / record relation sizes | 4,784,128 / 155,648 bytes | 6,848,512 / 253,952 bytes |

This single local run does not show a stable latency penalty attributable to protection: the paired measurements are close and noisy, and the EFS and plaintext runs execute sequentially in one process. It does show greater logical token and relation storage for EFS in this fixture. Both modes saturated the candidate cap and had 1% candidate precision; the final scorer recovered all labeled targets for this narrow corpus. Treat these measurements as a reproducibility check, not a capacity, performance, or quality guarantee. Repeat with randomized run order, multiple forks/processes, representative deployment hardware, and approved workload thresholds before making performance claims.

### Concurrent local load run (2026-11-02)

One full default run used JDK 25.0.4.1, PostgreSQL 18.6, macOS/aarch64 with 12 reported processors and a 9 GiB JVM maximum heap. It seeded 600 synthetic records (400 labeled positives and 200 hard negatives), used candidate limit 100, four workers, a two-second warmup, ten-second measured phases per mode, 10% no-match traffic, and a 30-second PostgreSQL statement timeout.

| Measure | `EFS_FULL` | `PLAINTEXT_CONTROL` |
| --- | ---: | ---: |
| Cold first-search latency | 30.647 ms | 12.166 ms |
| Warm search latency, p50 / p95 / p99 | 10.687 / 14.080 / 21.776 ms | 9.257 / 12.639 / 19.323 ms |
| Completed throughput | 365.57 queries/s | 417.69 queries/s |
| Failures / statement timeouts | 0 / 0 | 0 / 0 |
| Final recall / precision | 1.000 / 1.000 (3,289 positive requests) | 1.000 / 1.000 (3,766 positive requests) |
| No-match false positives | 0/372 | 0/412 |
| Mean candidates / cap saturation | 89.839 / 89.84% | 90.139 / 90.14% |
| Indexing throughput | 101.42 records/s | 111.76 records/s |
| PostgreSQL token / record relation sizes | 6,840,320 / 253,952 bytes | 4,775,936 / 155,648 bytes |

This single sequential local run is a smoke measurement, not a stable comparison: each mode was driven until its fixed duration ended, so completed request counts differ, and order, JIT, garbage collection, and local Docker scheduling can affect the result. Both modes reached the candidate limit for about 90% of requests. The observed final recall does not remove that truncation risk for other data. Use the attached JSON schema for reproducibility, repeat with randomized mode order and representative deployment hardware, and obtain owner-approved thresholds before making capacity or performance claims.

### Recorded local run (2026-10-19)

Environment: Gradle 9.8.1 wrapper, JDK 25.0.4.1, PostgreSQL 18.6 in digest-pinned Docker Compose, macOS 26.6.2 arm64, 12 reported processors, and a 9 GiB JVM maximum heap. The corpus contains 600 records (400 positives and 200 prefix-sharing hard negatives); 300 held-out single-substitution queries and 50 no-match queries; candidate limit 100; final similarity threshold 0.80. One single-threaded local run produced:

| Measure | Observed result |
| --- | ---: |
| Candidate recall | 1.000 (300/300) |
| Candidate precision | 0.010 (300/30,000 candidates) |
| Candidate-limit saturation | 1.000 (300/300 queries returned the full cap) |
| Final-match recall / precision | 1.000 / 1.000 |
| No-match false-positive rate | 0/50 |
| Candidate retrieval latency, p50 / p95 | 9.403 / 12.287 ms |
| Full search latency, p50 / p95 | 10.207 / 14.178 ms |
| Full search throughput | 94.73 queries/s |
| Indexing throughput | 99.81 records/s |
| Token postings / unique protected tokens | 20,256 / 6,859 |
| Maximum / mean protected-token frequency | 40 / 2.953 |
| Logical protected-token bytes / ciphertext bytes | 788,784 / 30,000 |
| Logical index-to-ciphertext byte ratio | 26.293x |
| PostgreSQL token / record relation sizes | 6,848,512 / 253,952 bytes |

Interpretation: the protected-token OR retrieval included every relevant record in this fixture, but returned the maximum 100 candidates on every typo query, so candidate precision was only 1%. Final Levenshtein verification removed the observed hard negatives for this corpus and threshold. The saturation and low retrieval precision are important limitations, not evidence that 100 is a generally safe cap: unseen relevant candidates may be truncated, and the fixture is a narrow ASCII workload. Further strategy/overlap tuning should be evaluated on a new held-out corpus before setting any candidate or accuracy target. The latency and throughput are local, single-run measurements without a production hardware profile.

### Review rerun (2026-10-21)

The deterministic benchmark was rerun against PostgreSQL 18.6 on JDK 25.0.4.1, macOS/aarch64, 12 reported processors, and a 9 GiB JVM maximum heap. It used the same corpus seed, 600 records, 300 held-out single-substitution queries, 50 no-match queries, candidate limit 100, and similarity threshold 0.80. The process ran from a dirty working tree based on revision `5bd8ac7`; the review changes did not alter the benchmark fixture or default exact/n-gram strategy, but this is not a clean-release-artifact measurement.

| Measure | Observed result |
| --- | ---: |
| Candidate recall | 1.000 (300/300) |
| Candidate precision | 0.010 (300/30,000 candidates) |
| Candidate-limit saturation | 1.000 (300/300 queries returned the full cap) |
| Final-match recall / precision | 1.000 / 1.000 |
| No-match false-positive rate | 0/50 |
| Candidate retrieval latency, p50 / p95 | 10.339 / 16.611 ms |
| Full search latency, p50 / p95 | 10.749 / 19.053 ms |
| Full search throughput | 81.54 queries/s |
| Indexing throughput | 74.79 records/s |
| Token postings / unique protected tokens | 20,256 / 6,859 |
| Maximum / mean protected-token frequency | 40 / 2.953 |
| Logical protected-token bytes / ciphertext bytes | 788,784 / 30,000 |
| Logical index-to-ciphertext byte ratio | 26.293x |
| PostgreSQL token / record relation sizes | 6,848,512 / 253,952 bytes |

Interpretation remains unchanged: the candidate limit saturated for every typo query and retrieval precision was 1%, even though final verification matched this fixture perfectly. These local measurements do not establish a production SLO or a safe recall guarantee. The current release recommendation must account for this candidate-volume behavior, the absence of approved production thresholds/workload profiles, and the out-of-scope leakage acceptance for optional strategies.

### Deterministic scorer comparison (2026-11-02)

`./gradlew --no-daemon :search-core:test --tests '*ScorerComparisonTest'` compares built-in final scorers on four fixed synthetic name-like cases: an adjacent transposition, OCR-style `1`/letter substitutions, token reordering, and a token typo. Each scorer sees the same four authorized candidate strings per case. The test reports macro reciprocal rank (MRR) and top-1 count; it does not perform protected-token retrieval or use corpus-wide plaintext statistics.

| Final scorer | MRR | Top-1 |
| --- | ---: | ---: |
| Code-point Levenshtein baseline | 0.8750 | 3/4 |
| Damerau-Levenshtein (OSA) | 0.8750 | 3/4 |
| Weighted edit (`1` to `i`/`l`, `0` to `o`) | 0.8750 | 3/4 |
| Pair-local weighted character bigrams | 1.0000 | 4/4 |
| Token-set Jaccard | 1.0000 | 4/4 |
| Candidate-local BM25 | 0.8750 | 3/4 |
| Candidate-local Soft TF-IDF | 1.0000 | 4/4 |

This fixture shows that token-aware profiles rank its reordered and typo cases well; it does not establish general accuracy gains. Four examples are too few to tune or select a production scorer. The weighted edit profile uses only the listed caller-supplied OCR costs, so it does not improve the transposition case. The BM25 profile uses term statistics from the four candidates only. These final scoring differences do not alter candidate generation: the separate recorded PostgreSQL baseline still has 1% candidate precision and saturates the 100-candidate cap for every typo query. Use larger entity-disjoint held-out fixtures before drawing broader conclusions or changing defaults.

### v0.2 PostgreSQL candidate-set scorer comparison (2026-11-02)

The PostgreSQL benchmark runner now also applies each scorer to the same v0.2 candidate-generation workload and reports `final_recall`, `final_precision`, MRR, top-1 count, and no-match false positives per profile. This was run with JDK 25.0.4.1 and PostgreSQL 18.6 on the deterministic 600-record corpus, 300 single-substitution queries, candidate limit 100, and score threshold 0.80. Candidate recall remained 1.000 and candidate precision 0.010 for every profile because the scoring stage does not change retrieval.

| Final scorer | Final recall | Final precision | MRR | Top-1 | No-match FP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Code-point Levenshtein | 1.000 | 1.000 | 1.000 | 300/300 | 0/50 |
| Damerau-Levenshtein (OSA) | 1.000 | 1.000 | 1.000 | 300/300 | 0/50 |
| Weighted edit (configured OCR costs) | 1.000 | 1.000 | 1.000 | 300/300 | 0/50 |
| Pair-local weighted character bigrams | 0.020 | 1.000 | 0.020 | 6/300 | 0/50 |
| Token-set Jaccard | 0.000 | undefined (no accepted results) | 0.000 | 0/300 | 0/50 |
| Candidate-local BM25 | 0.000 | undefined (no accepted results) | 0.000 | 0/300 | 0/50 |
| Candidate-local Soft TF-IDF | 1.000 | 1.000 | 1.000 | 300/300 | 0/50 |

This fixture contains one continuous random code-point token per record and only single substitutions. It therefore does not exercise token order, multiple terms, OCR confusions, or adjacent transpositions. The zero-result token-set/BM25 precision is undefined, not zero. Weighted bigrams rejected most relevant candidates at threshold `0.80`; candidate retrieval itself still included them. Soft TF-IDF handled this single-token typo workload, while the small name-like fixture above favors token-aware profiles on different transformations. These results show dataset dependence and are exploratory; they do not justify changing the default scorer or establish general quality.

The isotonic-calibration sanity test fits 20 synthetic score/label pairs (10 per class) and evaluates four separate synthetic held-out examples. On that deliberately separable fixture it reports Brier score `0.0000` and expected calibration error `0.0000`. This checks model fitting and metric calculation only; four held-out cases do not support a real-world confidence claim or production calibration.

### JVM component microbenchmarks

Run the JMH suite with `./gradlew --no-daemon :jmh-benchmarks:jmh`. It writes JSON results under `jmh-benchmarks/build/reports/jmh/results.json`, which is generated output and should not be committed. JMH 1.37 benchmarks AES-GCM encrypt/decrypt, exact HMAC token generation, protected bigram/trigram generation, canonical normalization, and each supported final scorer. Text lengths are 32, 128, and 512 ASCII code points; scoring candidate sets contain 1, 10, or 50 entries. Inputs are deterministic synthetic values. The scorer benchmark returns all candidate scores to JMH's result consumer.

The default methods use throughput mode, two forks, five one-second warmup iterations, and eight one-second measurement iterations. JMH output and JSON include the JDK/VM, machine, source revision/dirty-tree flag, generator ID/seed, benchmark parameters, fork/warmup/measurement configuration, and measured values. For a quick compile-and-run smoke only, for example: `./gradlew --no-daemon :jmh-benchmarks:jmh --args='CryptoAndScoringBenchmark.aesGcmEncrypt -p textLength=32 -wi 1 -i 1 -f 1 -w 100ms -r 100ms -rf json -rff build/reports/jmh/smoke.json'`. Smoke settings are not suitable for performance conclusions. These component measurements exclude PostgreSQL, fixture loading, request orchestration, key-service latency, and concurrent load; do not interpret them as end-to-end throughput or gate CI on elapsed-time values.

### Entity-disjoint advanced-scoring evaluation (2026-11-02)

Run it with `./gradlew --no-daemon :search-core:test --tests '*ScorerComparisonTest'`. `ScorerComparisonTest.evaluatesStratifiedScorersAndCalibrationOnEntityDisjointSplits` compares the default code-point Levenshtein scorer with the six experimental scorer profiles on a deterministic generated corpus (fixture generator v1, entities numbered 0 through 479). It uses 240 tuning entities and 240 separate held-out entities, with 40 held-out queries in each stratum: single substitution, adjacent transposition, OCR confusion, token reorder, token deletion, and Unicode diacritic. Thresholds are selected only on tuning entities by maximizing F1; scorer parameters remain fixed. The scorer comparison uses the same `candidateLimit=100` and a controlled 100-record cohort per query (one labeled target, three named hard negatives, and 96 distractors). Candidate tokens are generated with the production protected bigram and trigram strategies and HMAC indexer; candidate recall, precision, and count are measured after `ANY_SHARED_TOKEN` retrieval and before final scoring. This is an in-memory synthetic evaluation, not a PostgreSQL performance run.

The same split fits one isotonic calibration model per scorer from tuning candidate scores and labels, then reports raw and calibrated Brier score and 10-bin expected calibration error on held-out candidate scores. Each scorer has 23,028 tuning pairs and 21,520 held-out pairs after protected-token retrieval; the positive source entity sets and all candidate record values are disjoint. Candidate-local BM25 and Soft TF-IDF see only the retrieved candidates for each query.

| Held-out stratum | Scorer | Candidate recall / precision; mean count | Final recall / precision | MRR | Top-1 |
| --- | --- | ---: | ---: | ---: | ---: |
| Single substitution | Levenshtein | 1.000 / 0.0115; 87.15 | 1.000 / 1.000 | 1.000 | 40/40 |
| Single substitution | Damerau-Levenshtein | 1.000 / 0.0115; 87.15 | 1.000 / 1.000 | 1.000 | 40/40 |
| Single substitution | Weighted edit | 1.000 / 0.0115; 87.15 | 1.000 / 1.000 | 1.000 | 40/40 |
| Single substitution | Weighted bigrams | 1.000 / 0.0115; 87.15 | 1.000 / 1.000 | 1.000 | 40/40 |
| Single substitution | Token Jaccard | 1.000 / 0.0115; 87.15 | 1.000 / 0.333 | 1.000 | 40/40 |
| Single substitution | Candidate BM25 | 1.000 / 0.0115; 87.15 | 1.000 / 0.333 | 1.000 | 40/40 |
| Single substitution | Soft TF-IDF | 1.000 / 0.0115; 87.15 | 1.000 / 0.533 | 1.000 | 40/40 |
| Adjacent transposition | Levenshtein | 1.000 / 0.0110; 90.70 | 0.000 / undefined | 0.500 | 0/40 |
| Adjacent transposition | Damerau-Levenshtein | 1.000 / 0.0110; 90.70 | 1.000 / 1.000 | 1.000 | 40/40 |
| Adjacent transposition | Weighted edit | 1.000 / 0.0110; 90.70 | 0.000 / undefined | 0.500 | 0/40 |
| Adjacent transposition | Weighted bigrams | 1.000 / 0.0110; 90.70 | 0.000 / undefined | 0.500 | 0/40 |
| Adjacent transposition | Token Jaccard | 1.000 / 0.0110; 90.70 | 1.000 / 0.333 | 0.500 | 0/40 |
| Adjacent transposition | Candidate BM25 | 1.000 / 0.0110; 90.70 | 1.000 / 0.333 | 0.500 | 0/40 |
| Adjacent transposition | Soft TF-IDF | 1.000 / 0.0110; 90.70 | 0.900 / 0.500 | 0.500 | 0/40 |
| OCR confusion | Levenshtein | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| OCR confusion | Damerau-Levenshtein | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| OCR confusion | Weighted edit | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| OCR confusion | Weighted bigrams | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| OCR confusion | Token Jaccard | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| OCR confusion | Candidate BM25 | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| OCR confusion | Soft TF-IDF | 1.000 / 0.0109; 91.65 | 1.000 / 1.000 | 1.000 | 40/40 |
| Token reorder | Levenshtein | 1.000 / 0.0110; 90.68 | 0.000 / undefined | 1.000 | 40/40 |
| Token reorder | Damerau-Levenshtein | 1.000 / 0.0110; 90.68 | 0.000 / undefined | 1.000 | 40/40 |
| Token reorder | Weighted edit | 1.000 / 0.0110; 90.68 | 0.000 / undefined | 1.000 | 40/40 |
| Token reorder | Weighted bigrams | 1.000 / 0.0110; 90.68 | 1.000 / 1.000 | 1.000 | 40/40 |
| Token reorder | Token Jaccard | 1.000 / 0.0110; 90.68 | 1.000 / 0.333 | 1.000 | 40/40 |
| Token reorder | Candidate BM25 | 1.000 / 0.0110; 90.68 | 1.000 / 1.000 | 1.000 | 40/40 |
| Token reorder | Soft TF-IDF | 1.000 / 0.0110; 90.68 | 1.000 / 0.500 | 1.000 | 40/40 |
| Token deletion | Levenshtein | 1.000 / 0.0112; 89.05 | 1.000 / 0.500 | 0.500 | 0/40 |
| Token deletion | Damerau-Levenshtein | 1.000 / 0.0112; 89.05 | 1.000 / 0.500 | 0.500 | 0/40 |
| Token deletion | Weighted edit | 1.000 / 0.0112; 89.05 | 1.000 / 0.500 | 0.500 | 0/40 |
| Token deletion | Weighted bigrams | 1.000 / 0.0112; 89.05 | 1.000 / 0.500 | 0.500 | 0/40 |
| Token deletion | Token Jaccard | 1.000 / 0.0112; 89.05 | 1.000 / 0.333 | 0.500 | 0/40 |
| Token deletion | Candidate BM25 | 1.000 / 0.0112; 89.05 | 1.000 / 0.333 | 0.500 | 0/40 |
| Token deletion | Soft TF-IDF | 1.000 / 0.0112; 89.05 | 1.000 / 0.500 | 0.500 | 0/40 |
| Unicode diacritic | Levenshtein | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |
| Unicode diacritic | Damerau-Levenshtein | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |
| Unicode diacritic | Weighted edit | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |
| Unicode diacritic | Weighted bigrams | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |
| Unicode diacritic | Token Jaccard | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |
| Unicode diacritic | Candidate BM25 | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |
| Unicode diacritic | Soft TF-IDF | 1.000 / 0.0113; 88.78 | 1.000 / 1.000 | 1.000 | 40/40 |

| Scorer | Tuning threshold | Raw Brier / ECE | Calibrated Brier / ECE |
| --- | ---: | ---: | ---: |
| Levenshtein | 0.9412 | 0.053000 / 0.194966 | 0.005000 / 0.002267 |
| Damerau-Levenshtein | 0.9412 | 0.053126 / 0.195170 | 0.003355 / 0.001153 |
| Weighted edit | 0.9412 | 0.053277 / 0.195237 | 0.004907 / 0.002267 |
| Weighted bigrams | 0.7609 | 0.010695 / 0.047190 | 0.002788 / 0.000016 |
| Token Jaccard | 0.3333 | 0.005783 / 0.001239 | 0.005719 / 0.000000 |
| Candidate BM25 | 1.0000 | 0.011852 / 0.012759 | 0.005576 / 0.000000 |
| Soft TF-IDF | 0.8832 | 0.013757 / 0.016033 | 0.004138 / 0.000174 |

Interpretation: candidate recall and precision did not vary by final scorer because scorers are applied after protected retrieval; this controlled in-memory corpus yielded full candidate recall and roughly 1.1% candidate precision in every stratum. Final thresholds were selected on tuning data, and the held-out scores expose tradeoffs: Damerau-Levenshtein helps the transposition stratum; several profiles fail token reordering or deletion at their tuned thresholds; token-set and candidate-local ranking can accept more false positives. Isotonic fitting improved held-out Brier/ECE on this generated sample, but the fixture uses repeated templates and controlled transformations. These are reproducible prototype measurements, not production calibration evidence, a guarantee, or a reason to change production defaults.
