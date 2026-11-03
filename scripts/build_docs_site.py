#!/usr/bin/env python3
"""Assemble the static documentation site and its latest aggregate report."""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
from pathlib import Path


def build(report_path: Path, output: Path, expected_revision: str | None = None) -> None:
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("schema") != "efs.benchmark-report.v1":
        raise ValueError("benchmark report has an unsupported schema")
    if not isinstance(report.get("environment"), dict) or not isinstance(
        report.get("workload"), dict
    ):
        raise ValueError("benchmark report is missing runtime or workload metadata")
    if any(
        key not in report["environment"]
        for key in (
            "java_version",
            "java_vm",
            "os_name",
            "os_version",
            "architecture",
            "processors",
            "jvm_max_heap_bytes",
        )
    ):
        raise ValueError("benchmark report has incomplete runtime metadata")
    if not isinstance(report.get("source_revision"), str) or not report["source_revision"]:
        raise ValueError("benchmark report is missing its source revision")
    if re.fullmatch(r"[0-9a-f]{40,64}", report["source_revision"]) is None:
        raise ValueError("benchmark report has an invalid source revision")
    if expected_revision and report["source_revision"] != expected_revision:
        raise ValueError("benchmark report revision does not match the merged revision")
    modes = {item.get("mode") for item in report.get("results", [])}
    if modes != {"EFS_FULL", "PLAINTEXT_CONTROL"}:
        raise ValueError("benchmark report must include both comparison modes")
    metrics = (
        "completed_throughput_qps",
        "warm_latency_ms_p50",
        "warm_latency_ms_p95",
        "warm_latency_ms_p99",
        "final_recall",
        "final_precision",
        "no_match_false_positive_rate",
        "failures",
        "timeouts",
        "index_records_per_second",
        "postgres_token_relation_bytes",
        "postgres_record_relation_bytes",
    )
    for result in report["results"]:
        if not isinstance(result.get("postgres_version"), str) or not isinstance(
            result.get("postgres_configuration"), dict
        ):
            raise ValueError("benchmark report has incomplete PostgreSQL metadata")
        for key in metrics:
            value = result.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ValueError(f"benchmark report has an invalid {key}")
    if output.exists():
        shutil.rmtree(output)
    (output / "data").mkdir(parents=True)
    shutil.copy2(Path("docs/index.html"), output / "index.html")
    shutil.copy2(Path("docs/benchmarking.html"), output / "benchmarking.html")
    shutil.copy2(report_path, output / "data/latest.json")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, default=Path("site"))
    parser.add_argument("--expected-revision")
    args = parser.parse_args()
    build(args.report, args.output, args.expected_revision)


if __name__ == "__main__":
    main()
