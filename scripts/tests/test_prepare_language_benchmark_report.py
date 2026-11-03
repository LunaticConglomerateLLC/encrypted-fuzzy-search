import unittest

from scripts.prepare_language_benchmark_report import (
    EXPECTED_BENCHMARK,
    EXPECTED_INPUT_GENERATOR,
    LANGUAGE_PROVIDERS,
    LanguageBenchmarkError,
    prepare_report,
)


def raw_results(revision: str = "a" * 40) -> list[dict]:
    results = []
    for language_tag in LANGUAGE_PROVIDERS:
        for mode in ("thrpt", "sample"):
            metric = (
                {"score": 0.5, "scoreUnit": "ops/us"}
                if mode == "thrpt"
                else {
                    "score": 2.0,
                    "scoreUnit": "us/op",
                    "scorePercentiles": {"50.0": 1.8, "95.0": 2.2, "99.0": 2.5},
                }
            )
            results.append(
                {
                    "benchmark": EXPECTED_BENCHMARK,
                    "mode": mode,
                    "jmhVersion": "1.37",
                    "jdkVersion": "25.0.4.1",
                    "vmName": "OpenJDK 64-Bit Server VM",
                    "vmVersion": "25.0.4.1+1-LTS",
                    "threads": 1,
                    "forks": 2,
                    "warmupIterations": 3,
                    "warmupTime": "1 s",
                    "measurementIterations": 5,
                    "measurementTime": "1 s",
                    "params": {
                        "languageTag": language_tag,
                        "inputGenerator": EXPECTED_INPUT_GENERATOR,
                        "sourceRevision": revision,
                        "sourceTreeDirty": "false",
                    },
                    "primaryMetric": metric,
                }
            )
    return results


class PrepareLanguageBenchmarkReportTest(unittest.TestCase):
    def test_normalizes_all_language_modes_without_fixture_text(self) -> None:
        report = prepare_report(raw_results(), expected_revision="a" * 40)

        self.assertEqual("efs.multilingual-component-report.v1", report["schema"])
        self.assertEqual("a" * 40, report["source_revision"])
        self.assertEqual(len(LANGUAGE_PROVIDERS), len(report["results"]))
        self.assertTrue(report["workload"]["synthetic"])
        self.assertEqual(0.5, report["results"][0]["throughput_ops_per_microsecond"])
        self.assertEqual(2.2, report["results"][0]["latency_p95_microseconds"])
        self.assertNotIn("重庆银行", str(report))

    def test_rejects_wrong_revision_missing_language_and_invalid_percentiles(self) -> None:
        with self.assertRaises(LanguageBenchmarkError):
            prepare_report(raw_results(), expected_revision="b" * 40)

        missing_language = raw_results()[:-2]
        with self.assertRaises(LanguageBenchmarkError):
            prepare_report(missing_language)

        invalid_percentile = raw_results()
        sample = next(item for item in invalid_percentile if item["mode"] == "sample")
        sample["primaryMetric"]["scorePercentiles"]["95.0"] = float("nan")
        with self.assertRaises(LanguageBenchmarkError):
            prepare_report(invalid_percentile)

    def test_rejects_unexpected_tags_and_mixed_modes(self) -> None:
        unsupported = raw_results()
        unsupported[0]["params"]["languageTag"] = "en"
        with self.assertRaises(LanguageBenchmarkError):
            prepare_report(unsupported)

        no_throughput = [item for item in raw_results() if item["mode"] != "thrpt"]
        with self.assertRaises(LanguageBenchmarkError):
            prepare_report(no_throughput)


if __name__ == "__main__":
    unittest.main()
