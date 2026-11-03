#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$repo_root/compose.test.yaml"
record_count="${EFS_BENCHMARK_RECORDS:-600}"
candidate_limit="${EFS_BENCHMARK_CANDIDATE_LIMIT:-100}"
concurrency="${EFS_BENCHMARK_CONCURRENCY:-4}"
duration_seconds="${EFS_BENCHMARK_DURATION_SECONDS:-10}"
warmup_seconds="${EFS_BENCHMARK_WARMUP_SECONDS:-2}"
no_match_percent="${EFS_BENCHMARK_NO_MATCH_PERCENT:-10}"
request_timeout_ms="${EFS_BENCHMARK_REQUEST_TIMEOUT_MS:-30000}"

cleanup() {
  docker compose -f "$compose_file" down
}
trap cleanup EXIT

cd "$repo_root"
docker compose -f "$compose_file" up --detach --wait
./gradlew --no-daemon :postgres-adapter:fuzzySearchBenchmark
./gradlew --no-daemon :postgres-adapter:postgresLoadBenchmark \
  --args="--records=$record_count --candidate-limit=$candidate_limit --concurrency=$concurrency --duration-seconds=$duration_seconds --warmup-seconds=$warmup_seconds --no-match-percent=$no_match_percent --request-timeout-ms=$request_timeout_ms"
