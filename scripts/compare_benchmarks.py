#!/usr/bin/env python3
"""Validate and compare privacy-safe PostgreSQL benchmark reports."""

from __future__ import annotations

import argparse
import copy
import json
import math
import re
from pathlib import Path
from typing import Any


SCHEMA = "efs.benchmark-report.v1"
COMPARISON_SCHEMA = "efs.benchmark-comparison.v1"
METRICS = (
    "completed_throughput_qps",
    "warm_latency_ms_p50",
    "warm_latency_ms_p95",
    "warm_latency_ms_p99",
    "index_records_per_second",
    "final_recall",
    "final_precision",
    "no_match_false_positive_rate",
    "failures",
    "timeouts",
    "postgres_token_relation_bytes",
    "postgres_record_relation_bytes",
)


class BenchmarkReportError(ValueError):
    """Raised when a report is malformed or not comparable."""


def validate_report(report: Any, source: str = "report") -> dict[str, Any]:
    if not isinstance(report, dict) or report.get("schema") != SCHEMA:
        raise BenchmarkReportError(f"{source} must use schema {SCHEMA}")
    revision = report.get("source_revision")
    if not isinstance(revision, str) or re.fullmatch(r"[0-9a-f]{40,64}", revision) is None:
        raise BenchmarkReportError(f"{source} has an invalid source_revision")
    if not isinstance(report.get("corpus"), dict) or not isinstance(report.get("workload"), dict):
        raise BenchmarkReportError(f"{source} is missing corpus or workload metadata")
    if not isinstance(report.get("environment"), dict):
        raise BenchmarkReportError(f"{source} is missing runtime metadata")
    environment_keys = (
        "java_version",
        "java_vm",
        "os_name",
        "os_version",
        "architecture",
        "processors",
        "jvm_max_heap_bytes",
    )
    if any(key not in report["environment"] for key in environment_keys):
        raise BenchmarkReportError(f"{source} has incomplete runtime metadata")
    if report["workload"].get("mode") != "both":
        raise BenchmarkReportError(f"{source} must contain both EFS and plaintext measurements")
    results = report.get("results")
    if (
        not isinstance(results, list)
        or len(results) != 2
        or any(not isinstance(item, dict) for item in results)
        or {item.get("mode") for item in results} != {"EFS_FULL", "PLAINTEXT_CONTROL"}
    ):
        raise BenchmarkReportError(f"{source} must contain EFS_FULL and PLAINTEXT_CONTROL results")
    for section, keys in (
        (report["corpus"], ("generator_id", "seed", "record_count", "labeled_query_count")),
        (
            report["workload"],
            (
                "candidate_limit",
                "concurrency",
                "duration_seconds",
                "warmup_seconds",
                "no_match_percent",
                "request_timeout_ms",
                "mode",
            ),
        ),
    ):
        if any(key not in section for key in keys):
            raise BenchmarkReportError(f"{source} has incomplete corpus/workload metadata")
    for item in results:
        if not isinstance(item.get("postgres_version"), str) or not isinstance(
            item.get("postgres_configuration"), dict
        ):
            raise BenchmarkReportError(f"{source} has incomplete PostgreSQL metadata")
        for metric in METRICS:
            value = item.get(metric)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise BenchmarkReportError(f"{source} has an invalid {metric} value")
    return report


def read_report(path: Path) -> dict[str, Any]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BenchmarkReportError(f"cannot read benchmark report {path}: {error}") from error
    return validate_report(report, str(path))


def _comparable_metadata(report: dict[str, Any]) -> dict[str, Any]:
    workload = report["workload"]
    corpus = report["corpus"]
    environment = report["environment"]
    results = {item["mode"]: item for item in report["results"]}
    return {
        "corpus": {
            "generator_id": corpus.get("generator_id"),
            "seed": corpus.get("seed"),
            "record_count": corpus.get("record_count"),
            "labeled_query_count": corpus.get("labeled_query_count"),
        },
        "workload": {
            key: workload.get(key)
            for key in (
                "candidate_limit",
                "concurrency",
                "duration_seconds",
                "warmup_seconds",
                "no_match_percent",
                "request_timeout_ms",
                "mode",
            )
        },
        "environment": {
            key: environment.get(key)
            for key in (
                "java_version",
                "java_vm",
                "os_name",
                "os_version",
                "architecture",
                "processors",
                "jvm_max_heap_bytes",
            )
        },
        "postgres": {
            mode: {
                "version": results[mode].get("postgres_version"),
                "configuration": results[mode].get("postgres_configuration"),
            }
            for mode in ("EFS_FULL", "PLAINTEXT_CONTROL")
        },
    }


def compare_reports(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    baseline = validate_report(baseline, "baseline report")
    candidate = validate_report(candidate, "candidate report")
    if _comparable_metadata(baseline) != _comparable_metadata(candidate):
        raise BenchmarkReportError("baseline and candidate corpus, workload, or environment do not match")
    baseline_by_mode = {item["mode"]: item for item in baseline["results"]}
    candidate_by_mode = {item["mode"]: item for item in candidate["results"]}
    results = []
    for mode in ("EFS_FULL", "PLAINTEXT_CONTROL"):
        metrics = {}
        for metric in METRICS:
            before = baseline_by_mode[mode][metric]
            after = candidate_by_mode[mode][metric]
            delta_percent = None if before == 0 else ((after - before) / abs(before)) * 100
            metrics[metric] = {
                "baseline": before,
                "candidate": after,
                "delta_percent": delta_percent,
            }
        results.append({"mode": mode, "metrics": metrics})
    return {
        "schema": COMPARISON_SCHEMA,
        "baseline_revision": baseline["source_revision"],
        "candidate_revision": candidate["source_revision"],
        "workload": _comparable_metadata(candidate),
        "results": results,
    }


def average_reports(reports: list[dict[str, Any]], label: str) -> dict[str, Any]:
    if not reports:
        raise BenchmarkReportError(f"{label} must contain at least one report")
    validated = [validate_report(report, f"{label} report") for report in reports]
    revision = validated[0]["source_revision"]
    metadata = _comparable_metadata(validated[0])
    if any(report["source_revision"] != revision for report in validated):
        raise BenchmarkReportError(f"{label} reports must use the same source revision")
    if any(_comparable_metadata(report) != metadata for report in validated[1:]):
        raise BenchmarkReportError(f"{label} reports must use matching corpus, workload, and environment")
    averaged = copy.deepcopy(validated[0])
    samples_by_mode = {item["mode"]: item for item in averaged["results"]}
    for mode in ("EFS_FULL", "PLAINTEXT_CONTROL"):
        for metric in METRICS:
            samples = [
                next(item for item in report["results"] if item["mode"] == mode)[metric]
                for report in validated
            ]
            samples_by_mode[mode][metric] = sum(samples) / len(samples)
    return averaged


def compare_report_sets(
    baseline_reports: list[dict[str, Any]],
    candidate_reports: list[dict[str, Any]],
    expected_baseline_revision: str | None = None,
    expected_candidate_revision: str | None = None,
) -> dict[str, Any]:
    baseline = average_reports(baseline_reports, "baseline")
    candidate = average_reports(candidate_reports, "candidate")
    if expected_baseline_revision and baseline["source_revision"] != expected_baseline_revision:
        raise BenchmarkReportError("baseline report revision does not match the pull request target SHA")
    if expected_candidate_revision and candidate["source_revision"] != expected_candidate_revision:
        raise BenchmarkReportError("candidate report revision does not match the pull request head SHA")
    comparison = compare_reports(
        baseline,
        candidate,
    )
    comparison["sample_count"] = {
        "baseline": len(baseline_reports),
        "candidate": len(candidate_reports),
    }
    return comparison


def render_markdown(comparison: dict[str, Any]) -> str:
    lines = [
        "## PostgreSQL performance comparison",
        "",
        f"Base `{comparison['baseline_revision'][:12]}` → head `{comparison['candidate_revision'][:12]}`.",
        "",
        f"Same synthetic corpus and workload on the same runner; values are averaged over {comparison.get('sample_count', {}).get('baseline', 1)} base and {comparison.get('sample_count', {}).get('candidate', 1)} PR runs. Timing deltas are informational; no approved performance threshold is configured.",
        "",
        "The same PR-supplied test-only benchmark harness is overlaid on the target checkout; target production sources remain at the recorded base revision.",
        "",
        "| Mode | Measure | Base | PR | Change |",
        "| --- | --- | ---: | ---: | ---: |",
    ]
    display = (
        ("completed_throughput_qps", "Throughput (queries/s)", 2),
        ("warm_latency_ms_p50", "Warm p50 (ms)", 3),
        ("warm_latency_ms_p95", "Warm p95 (ms)", 3),
        ("warm_latency_ms_p99", "Warm p99 (ms)", 3),
        ("index_records_per_second", "Indexing (records/s)", 2),
        ("final_recall", "Final recall", 4),
        ("final_precision", "Final precision", 4),
        ("no_match_false_positive_rate", "No-match false-positive rate", 4),
        ("failures", "Failures (mean count)", 1),
        ("timeouts", "Timeouts (mean count)", 1),
        ("postgres_token_relation_bytes", "Token relation (bytes)", 0),
        ("postgres_record_relation_bytes", "Record relation (bytes)", 0),
    )
    for result in comparison["results"]:
        for key, label, digits in display:
            values = result["metrics"][key]
            change = values["delta_percent"]
            change_text = "n/a" if change is None else f"{change:+.1f}%"
            lines.append(
                f"| `{result['mode']}` | {label} | {values['baseline']:.{digits}f} "
                f"| {values['candidate']:.{digits}f} | {change_text} |"
            )
    lines.extend(
        [
            "",
            "Each report is one measured run. Runner variance, JVM warmup, and database scheduling can affect the result; investigate changes with repeated controlled runs before treating them as regressions.",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, action="append", required=True)
    parser.add_argument("--candidate", type=Path, action="append", required=True)
    parser.add_argument("--expected-baseline-revision")
    parser.add_argument("--expected-candidate-revision")
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--markdown-out", type=Path, required=True)
    args = parser.parse_args()
    comparison = compare_report_sets(
        [read_report(path) for path in args.baseline],
        [read_report(path) for path in args.candidate],
        args.expected_baseline_revision,
        args.expected_candidate_revision,
    )
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(comparison, indent=2) + "\n", encoding="utf-8")
    args.markdown_out.write_text(render_markdown(comparison), encoding="utf-8")
    print(args.markdown_out.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
