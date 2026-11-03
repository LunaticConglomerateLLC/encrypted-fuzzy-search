#!/usr/bin/env python3
"""Validate raw JMH results and produce a privacy-safe multilingual report."""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
from pathlib import Path


LANGUAGE_PROVIDERS = {
    "zh-Hans": ("icu4j", "efs-translit-zh-Hans-icu4j-78.3-v2"),
    "ja-JP": ("lucene-kuromoji", "efs-jajp-kuro1052-icu783-v1"),
    "ar-Arab": ("icu4j", "efs-translit-ar-Arab-icu4j-78.3-v1"),
    "hi-Deva": ("icu4j", "efs-translit-hi-Deva-icu4j-78.3-v1"),
    "bn-Beng": ("icu4j", "efs-translit-bn-Beng-icu4j-78.3-v1"),
    "gu-Gujr": ("icu4j", "efs-translit-gu-Gujr-icu4j-78.3-v1"),
    "pa-Guru": ("icu4j", "efs-translit-pa-Guru-icu4j-78.3-v1"),
    "ta-Taml": ("icu4j", "efs-translit-ta-Taml-icu4j-78.3-v1"),
    "te-Telu": ("icu4j", "efs-translit-te-Telu-icu4j-78.3-v1"),
    "kn-Knda": ("icu4j", "efs-translit-kn-Knda-icu4j-78.3-v1"),
    "ml-Mlym": ("icu4j", "efs-translit-ml-Mlym-icu4j-78.3-v1"),
    "or-Orya": ("icu4j", "efs-translit-or-Orya-icu4j-78.3-v1"),
}
EXPECTED_BENCHMARK = (
    "io.github.lunaticconglomeratellc.encryptedfuzzysearch.benchmark."
    "LanguageVariantBenchmark.transformVariants"
)
EXPECTED_INPUT_GENERATOR = "synthetic-language-regression-v1"


class LanguageBenchmarkError(ValueError):
    """Raised when raw JMH results cannot support a comparable language report."""


def _number(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise LanguageBenchmarkError(f"invalid numeric value for {field}")
    if value <= 0:
        raise LanguageBenchmarkError(f"{field} must be greater than zero")
    return float(value)


def prepare_report(raw_results: list[dict], expected_revision: str | None = None) -> dict:
    if not isinstance(raw_results, list) or not raw_results:
        raise LanguageBenchmarkError("JMH results must be a non-empty array")

    by_language: dict[str, dict[str, dict]] = {}
    runtime: dict | None = None
    revision: str | None = None
    source_tree_dirty: bool | None = None
    for item in raw_results:
        if not isinstance(item, dict) or item.get("benchmark") != EXPECTED_BENCHMARK:
            raise LanguageBenchmarkError("unexpected JMH benchmark in report")
        mode = item.get("mode")
        if mode not in {"thrpt", "sample"}:
            raise LanguageBenchmarkError("JMH results must include throughput and sample modes")
        params = item.get("params")
        if not isinstance(params, dict):
            raise LanguageBenchmarkError("JMH result is missing parameters")
        language_tag = params.get("languageTag")
        if language_tag not in LANGUAGE_PROVIDERS:
            raise LanguageBenchmarkError("JMH result uses an unsupported language tag")
        if params.get("inputGenerator") != EXPECTED_INPUT_GENERATOR:
            raise LanguageBenchmarkError("JMH result uses an unsupported input generator")
        current_revision = params.get("sourceRevision")
        if not isinstance(current_revision, str) or re.fullmatch(r"[0-9a-f]{40,64}", current_revision) is None:
            raise LanguageBenchmarkError("JMH result has an invalid source revision")
        if revision is not None and revision != current_revision:
            raise LanguageBenchmarkError("JMH results contain different source revisions")
        revision = current_revision
        dirty_value = params.get("sourceTreeDirty")
        if dirty_value not in {"true", "false"}:
            raise LanguageBenchmarkError("JMH result is missing source-tree state")
        dirty = dirty_value == "true"
        if source_tree_dirty is not None and source_tree_dirty != dirty:
            raise LanguageBenchmarkError("JMH results contain different source-tree states")
        source_tree_dirty = dirty

        current_runtime = {
            "jmh_version": item.get("jmhVersion"),
            "java_version": item.get("jdkVersion"),
            "java_vm": item.get("vmName"),
            "java_vm_version": item.get("vmVersion"),
            "threads": item.get("threads"),
            "forks": item.get("forks"),
            "warmup_iterations": item.get("warmupIterations"),
            "warmup_time": item.get("warmupTime"),
            "measurement_iterations": item.get("measurementIterations"),
            "measurement_time": item.get("measurementTime"),
        }
        if any(value in (None, "") for value in current_runtime.values()):
            raise LanguageBenchmarkError("JMH result has incomplete runtime metadata")
        if runtime is not None and runtime != current_runtime:
            raise LanguageBenchmarkError("JMH results have different runtime configurations")
        runtime = current_runtime

        language_modes = by_language.setdefault(language_tag, {})
        if mode in language_modes:
            raise LanguageBenchmarkError(f"duplicate {mode} result for {language_tag}")
        metric = item.get("primaryMetric")
        if not isinstance(metric, dict):
            raise LanguageBenchmarkError("JMH result is missing its primary metric")
        score = _number(metric.get("score"), f"{language_tag}.{mode}.score")
        if mode == "thrpt":
            if metric.get("scoreUnit") != "ops/us":
                raise LanguageBenchmarkError("throughput results must use ops/us")
            language_modes[mode] = {"throughput_ops_per_microsecond": score}
        else:
            if metric.get("scoreUnit") != "us/op":
                raise LanguageBenchmarkError("sample results must use us/op")
            percentiles = metric.get("scorePercentiles")
            if not isinstance(percentiles, dict):
                raise LanguageBenchmarkError("sample results are missing latency percentiles")
            language_modes[mode] = {
                "latency_mean_microseconds": score,
                "latency_p50_microseconds": _number(percentiles.get("50.0"), "p50"),
                "latency_p95_microseconds": _number(percentiles.get("95.0"), "p95"),
                "latency_p99_microseconds": _number(percentiles.get("99.0"), "p99"),
            }

    expected_languages = set(LANGUAGE_PROVIDERS)
    if set(by_language) != expected_languages:
        raise LanguageBenchmarkError("JMH report does not contain every configured language tag")
    if any(set(modes) != {"thrpt", "sample"} for modes in by_language.values()):
        raise LanguageBenchmarkError("each language needs throughput and sample results")
    if expected_revision and revision != expected_revision:
        raise LanguageBenchmarkError("JMH report revision does not match the expected revision")

    assert runtime is not None and revision is not None and source_tree_dirty is not None
    results = []
    for tag, modes in by_language.items():
        provider_id, transform_id = LANGUAGE_PROVIDERS[tag]
        results.append(
            {
                "language_tag": tag,
                "provider_id": provider_id,
                "transform_id": transform_id,
                **modes["thrpt"],
                **modes["sample"],
            }
        )
    return {
        "schema": "efs.multilingual-component-report.v1",
        "source_revision": revision,
        "source_tree_dirty": source_tree_dirty,
        "workload": {"input_generator": EXPECTED_INPUT_GENERATOR, "synthetic": True},
        "environment": {
            **runtime,
            "os_name": platform.system(),
            "os_version": platform.release(),
            "architecture": platform.machine(),
            "processor_count": os.cpu_count() or 1,
        },
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_results", type=Path)
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--expected-revision")
    args = parser.parse_args()
    try:
        raw = json.loads(args.raw_results.read_text(encoding="utf-8"))
        report = prepare_report(raw, args.expected_revision)
    except (OSError, json.JSONDecodeError, LanguageBenchmarkError) as error:
        parser.error(str(error))
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
