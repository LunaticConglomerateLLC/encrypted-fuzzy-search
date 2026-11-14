# Benchmark method and interpretation

EFS maintains two separate performance views. PostgreSQL load runs compare the full Java/PostgreSQL EFS path with a plaintext test control. JMH runs measure the cost of individual language transforms. Neither run contains customer data, and neither sets a production service-level guarantee.

## PostgreSQL load benchmark

The CI and publication profile runs a synthetic fixture against the disposable PostgreSQL service:

```sh
docker compose -f compose.test.yaml up --detach --wait
./gradlew --no-daemon :postgres-adapter:postgresLoadBenchmark \
  --args='--records=600 --candidate-limit=100 --concurrency=4 \
  --duration-seconds=30 --warmup-seconds=5 --no-match-percent=10 \
  --request-timeout-ms=30000'
docker compose -f compose.test.yaml down --volumes --remove-orphans
```

For a fast smoke run, reduce the records to 60, concurrency to 2, and measurement and warmup to one second. A smoke run checks that the harness works; its timing is not comparable with the longer profile.

`EFS_FULL` encrypts payloads and stores keyed n-gram postings. `PLAINTEXT_CONTROL` stores generated plaintext and raw n-grams only in the disposable benchmark database. It is a performance control, not an alternative security design. The modes use generated data and the runner checks their labeled matching behavior before reporting results.

The report includes warm p50/p95/p99 latency, completed queries per second, failures and timeouts, indexing throughput, correctness, candidate-limit saturation, JVM and PostgreSQL context, and relation sizes. It reports the measured revision and workload. Different local machines and runner load can change the timing.

Published output contains aggregate measurements, synthetic-workload settings, and runtime metadata. It must not contain customer values, query text, keys, or protected tokens. Review any change to the report schema for sensitive data before publishing it.

## Multilingual JMH profile

JMH measures provider transformation cost on small synthetic text examples. Throughput and sample-time percentiles are reported separately for each configured language tag. These numbers exclude database retrieval, token generation, encryption, decryption, final scoring, and application network overhead. They are not end-to-end search latency.

## Read results with the accuracy data

- Candidate recall and candidate precision describe retrieval before decryption and final scoring.
- Final precision and recall describe accepted results after the configured scorer and threshold.
- Candidate-limit saturation shows how often retrieval reached its cap. A saturated result may have omitted matches.
- Storage sizes depend on the synthetic corpus and PostgreSQL configuration. Logical token bytes and relation sizes are different measures.
- Timing deltas are informational. No owner-approved throughput, latency, encryption-overhead, or capacity threshold exists.

Always interpret performance with matching accuracy and candidate volume. High final precision on one fixture does not make low candidate precision or cap saturation harmless. The [latest published reports](latest.md) include the measured revisions and runtime settings. For the detailed fixture and command options, see [BENCHMARKING.md](https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search/blob/main/BENCHMARKING.md).
