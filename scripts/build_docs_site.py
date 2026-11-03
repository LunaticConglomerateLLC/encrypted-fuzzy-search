#!/usr/bin/env python3
"""Assemble the static documentation site and its latest aggregate report."""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
from pathlib import Path


LANGUAGE_TAGS = {
    "zh-Hans",
    "ja-JP",
    "ar-Arab",
    "hi-Deva",
    "bn-Beng",
    "gu-Gujr",
    "pa-Guru",
    "ta-Taml",
    "te-Telu",
    "kn-Knda",
    "ml-Mlym",
    "or-Orya",
}


def validate_language_report(report_path: Path, expected_revision: str | None) -> dict:
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("schema") != "efs.multilingual-component-report.v1":
        raise ValueError("language benchmark report has an unsupported schema")
    if not isinstance(report.get("source_revision"), str) or re.fullmatch(
        r"[0-9a-f]{40,64}", report["source_revision"]
    ) is None:
        raise ValueError("language benchmark report has an invalid source revision")
    if expected_revision and report["source_revision"] != expected_revision:
        raise ValueError("language benchmark report revision does not match the merged revision")
    if report.get("source_tree_dirty") is not False:
        raise ValueError("published language benchmark must use a clean source tree")
    workload = report.get("workload")
    if not isinstance(workload, dict) or workload.get("synthetic") is not True:
        raise ValueError("language benchmark report must identify its synthetic workload")
    environment = report.get("environment")
    if not isinstance(environment, dict) or any(
        not isinstance(environment.get(key), str) or not environment[key]
        for key in (
            "jmh_version",
            "java_version",
            "java_vm",
            "java_vm_version",
            "os_name",
            "os_version",
            "architecture",
        )
    ) or isinstance(environment.get("processor_count"), bool) or not isinstance(
        environment.get("processor_count"), int
    ) or environment["processor_count"] < 1:
        raise ValueError("language benchmark report has incomplete runtime metadata")
    results = report.get("results")
    if not isinstance(results, list) or any(not isinstance(item, dict) for item in results):
        raise ValueError("language benchmark report has malformed results")
    if {item.get("language_tag") for item in results} != LANGUAGE_TAGS:
        raise ValueError("language benchmark report does not contain every configured language tag")
    if len(results) != len(LANGUAGE_TAGS):
        raise ValueError("language benchmark report contains duplicate language tags")
    metrics = (
        "throughput_ops_per_microsecond",
        "latency_mean_microseconds",
        "latency_p50_microseconds",
        "latency_p95_microseconds",
        "latency_p99_microseconds",
    )
    for item in results:
        if not isinstance(item.get("provider_id"), str) or not isinstance(
            item.get("transform_id"), str
        ):
            raise ValueError("language benchmark result is missing provider provenance")
        for key in metrics:
            value = item.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                raise ValueError(f"language benchmark report has an invalid {key}")
    return report


def build(
    report_path: Path,
    output: Path,
    expected_revision: str | None = None,
    language_report_path: Path | None = None,
) -> None:
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
    language_report = (
        validate_language_report(language_report_path, expected_revision)
        if language_report_path is not None
        else None
    )
    if output.exists():
        shutil.rmtree(output)
    (output / "data").mkdir(parents=True)
    shutil.copy2(Path("docs/index.html"), output / "index.html")
    shutil.copy2(Path("docs/benchmarking.html"), output / "benchmarking.html")
    shutil.copy2(report_path, output / "data/latest.json")
    if language_report is not None:
        shutil.copy2(language_report_path, output / "data/language-jmh.json")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, default=Path("site"))
    parser.add_argument("--expected-revision")
    parser.add_argument("--language-report", type=Path, required=True)
    args = parser.parse_args()
    build(args.report, args.output, args.expected_revision, args.language_report)


if __name__ == "__main__":
    main()
