import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts.build_docs_site import build
from scripts.compare_benchmarks import (
    BenchmarkReportError,
    compare_report_sets,
    compare_reports,
    read_report,
    render_markdown,
)


def report(revision: str, qps: float = 100.0) -> dict:
    results = []
    for mode in ("EFS_FULL", "PLAINTEXT_CONTROL"):
        results.append(
            {
                "mode": mode,
                "completed_throughput_qps": qps,
                "warm_latency_ms_p50": 10.0,
                "warm_latency_ms_p95": 20.0,
                "warm_latency_ms_p99": 30.0,
                "index_records_per_second": 50.0,
                "final_recall": 1.0,
                "final_precision": 1.0,
                "no_match_false_positive_rate": 0.0,
                "failures": 0,
                "timeouts": 0,
                "postgres_token_relation_bytes": 4096,
                "postgres_record_relation_bytes": 1024,
                "postgres_version": "18.6",
                "postgres_configuration": {
                    "work_mem": "4MB",
                    "shared_buffers": "128MB",
                    "max_connections": "100",
                },
            }
        )
    return {
        "schema": "efs.benchmark-report.v1",
        "source_revision": revision,
        "corpus": {
            "generator_id": "synthetic-random-codepoints-v1",
            "seed": 20261019,
            "record_count": 600,
            "labeled_query_count": 300,
        },
        "workload": {
            "candidate_limit": 100,
            "concurrency": 4,
            "duration_seconds": 10,
            "warmup_seconds": 2,
            "no_match_percent": 10,
            "request_timeout_ms": 30000,
            "mode": "both",
        },
        "environment": {
            "java_version": "25.0.4",
            "java_vm": "OpenJDK",
            "os_name": "Linux",
            "os_version": "runner",
            "architecture": "x86_64",
            "processors": 4,
            "jvm_max_heap_bytes": 2048,
        },
        "results": results,
    }


class CompareBenchmarksTest(unittest.TestCase):
    def test_computes_mode_specific_percent_changes(self) -> None:
        comparison = compare_reports(report("b" * 40, 100.0), report("a" * 40, 110.0))
        efs = comparison["results"][0]
        self.assertEqual(10.0, efs["metrics"]["completed_throughput_qps"]["delta_percent"])
        self.assertEqual("b" * 40, comparison["baseline_revision"])

    def test_rejects_different_workloads(self) -> None:
        candidate = report("a" * 40)
        candidate["workload"]["concurrency"] = 8
        with self.assertRaises(BenchmarkReportError):
            compare_reports(report("b" * 40), candidate)

    def test_zero_baseline_has_no_percent_delta(self) -> None:
        baseline = report("b" * 40)
        candidate = report("a" * 40)
        for item in baseline["results"]:
            item["failures"] = 0
        for item in candidate["results"]:
            item["failures"] = 1
        metrics = compare_reports(baseline, candidate)["results"][0]["metrics"]
        self.assertIsNone(metrics["failures"]["delta_percent"])

    def test_rejects_different_runtime(self) -> None:
        candidate = report("a" * 40)
        candidate["environment"]["java_version"] = "21"
        with self.assertRaises(BenchmarkReportError):
            compare_reports(report("b" * 40), candidate)

    def test_averages_repeated_runs_per_revision(self) -> None:
        comparison = compare_report_sets(
            [report("b" * 40, 100.0), report("b" * 40, 120.0)],
            [report("a" * 40, 110.0), report("a" * 40, 130.0)],
            expected_baseline_revision="b" * 40,
            expected_candidate_revision="a" * 40,
        )
        efs = comparison["results"][0]
        self.assertEqual(110.0, efs["metrics"]["completed_throughput_qps"]["baseline"])
        self.assertEqual(120.0, efs["metrics"]["completed_throughput_qps"]["candidate"])
        self.assertEqual({"baseline": 2, "candidate": 2}, comparison["sample_count"])

    def test_rejects_repeated_runs_from_different_revisions(self) -> None:
        with self.assertRaises(BenchmarkReportError):
            compare_report_sets(
                [report("b" * 40), report("c" * 40)],
                [report("a" * 40)],
            )

    def test_rejects_unexpected_revision_sha(self) -> None:
        with self.assertRaises(BenchmarkReportError):
            compare_report_sets(
                [report("b" * 40)],
                [report("a" * 40)],
                expected_baseline_revision="c" * 40,
                expected_candidate_revision="a" * 40,
            )

    def test_markdown_states_that_timing_is_informational(self) -> None:
        summary = render_markdown(compare_reports(report("b" * 40), report("a" * 40, 105)))
        self.assertIn("no approved performance threshold", summary)
        self.assertIn("+5.0%", summary)

    def test_does_not_mutate_input_reports(self) -> None:
        baseline = report("b" * 40)
        original = copy.deepcopy(baseline)
        compare_reports(baseline, report("a" * 40))
        self.assertEqual(original, baseline)

    def test_reads_valid_report_and_rejects_invalid_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(report("b" * 40)), encoding="utf-8")
            self.assertEqual("b" * 40, read_report(path)["source_revision"])
            path.write_text('{"schema":"wrong"}', encoding="utf-8")
            with self.assertRaises(BenchmarkReportError):
                read_report(path)

    def test_rejects_non_finite_metrics(self) -> None:
        candidate = report("a" * 40)
        candidate["results"][0]["warm_latency_ms_p95"] = float("nan")
        with self.assertRaises(BenchmarkReportError):
            compare_reports(report("b" * 40), candidate)

    def test_builds_documentation_site_with_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            sample_path = Path(temporary) / "report.json"
            output = Path(temporary) / "site"
            sample_path.write_text(json.dumps(report("b" * 40)), encoding="utf-8")
            build(sample_path, output, expected_revision="b" * 40)
            self.assertTrue((output / "index.html").is_file())
            self.assertTrue((output / "benchmarking.html").is_file())
            self.assertEqual(
                "efs.benchmark-report.v1",
                json.loads((output / "data/latest.json").read_text(encoding="utf-8"))["schema"],
            )
            shutil.rmtree(output, ignore_errors=True)

    def test_site_build_rejects_wrong_merged_revision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report_path = Path(temporary) / "report.json"
            report_path.write_text(json.dumps(report("b" * 40)), encoding="utf-8")
            with self.assertRaises(ValueError):
                build(report_path, Path(temporary) / "site", expected_revision="a" * 40)


if __name__ == "__main__":
    unittest.main()
