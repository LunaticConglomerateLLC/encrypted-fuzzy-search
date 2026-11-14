# Latest performance reports

The documentation workflow runs a PostgreSQL load profile and a multilingual JMH profile on the merged revision. It validates both report schemas and their source revision, then adds the JSON reports to the published site. The report links below are generated from the site path so they continue to work under `/encrypted-fuzzy-search/`.

<div class="grid cards" markdown>

-   **PostgreSQL load report**

    ---

    Full EFS versus synthetic plaintext control, including correctness, throughput, latency, and storage.

    <span id="postgres-report-link">The report is added after the merged benchmark completes.</span>

-   **Multilingual JMH report**

    ---

    Per-provider transform throughput and latency percentiles for the explicitly configured language tags.

    <span id="language-report-link">The report is added after the merged benchmark completes.</span>

</div>

<p id="report-status" role="status">Loading report metadata…</p>
<div id="postgres-report"></div>
<p id="postgres-metadata"></p>
<div id="language-report"></div>
<p id="language-metadata"></p>

<script>
(() => {
  const status = document.getElementById("report-status");
  const dataUrl = (name) => new URL(`../../data/${name}`, document.baseURI);
  const setDownload = (elementId, name, label) => {
    const link = document.createElement("a");
    link.href = dataUrl(name).toString();
    link.textContent = label;
    document.getElementById(elementId).replaceChildren(link);
  };
  const makeTable = (headings, rows) => {
    const table = document.createElement("table");
    const thead = table.createTHead().insertRow();
    for (const heading of headings) {
      const cell = document.createElement("th");
      cell.textContent = heading;
      thead.appendChild(cell);
    }
    const body = table.createTBody();
    for (const row of rows) {
      const tr = body.insertRow();
      for (const value of row) {
        const cell = tr.insertCell();
        cell.textContent = String(value);
      }
    }
    return table;
  };
  const format = (value, digits = 2) => Number(value).toFixed(digits);
  Promise.all([
    fetch(dataUrl("latest.json"), { cache: "no-store" }).then((response) => {
      if (!response.ok) throw new Error("PostgreSQL report is not published yet");
      return response.json();
    }),
    fetch(dataUrl("language-jmh.json"), { cache: "no-store" }).then((response) => {
      if (!response.ok) throw new Error("Multilingual report is not published yet");
      return response.json();
    }),
  ]).then(([postgres, language]) => {
    if (postgres.schema !== "efs.benchmark-report.v1"
        || language.schema !== "efs.multilingual-component-report.v1") {
      throw new Error("Published report schema is not supported");
    }
    const postgresRows = [
      ["Throughput (queries/s)", "completed_throughput_qps", 2],
      ["Warm p50 (ms)", "warm_latency_ms_p50", 3],
      ["Warm p95 (ms)", "warm_latency_ms_p95", 3],
      ["Warm p99 (ms)", "warm_latency_ms_p99", 3],
      ["Final recall", "final_recall", 4],
      ["Final precision", "final_precision", 4],
      ["No-match false-positive rate", "no_match_false_positive_rate", 4],
      ["Candidate cap saturation", "candidate_limit_saturation_rate", 4],
      ["Indexing (records/s)", "index_records_per_second", 2],
      ["Token relation (bytes)", "postgres_token_relation_bytes", 0],
      ["Record relation (bytes)", "postgres_record_relation_bytes", 0],
    ].map(([label, key, digits]) => [
      label,
      ...postgres.results.map((result) => format(result[key], digits)),
    ]);
    document.getElementById("postgres-report").replaceChildren(
      makeTable(["Measure", ...postgres.results.map((result) => result.mode)], postgresRows),
    );
    document.getElementById("postgres-metadata").textContent =
      `Revision ${postgres.source_revision} · Java ${postgres.environment.java_version} · `
      + `PostgreSQL ${postgres.results[0].postgres_version} · `
      + `${postgres.corpus.record_count} synthetic records · `
      + `${postgres.workload.concurrency} workers · generated ${postgres.generated_at_utc}`;
    const languageRows = language.results.map((result) => [
      result.language_tag,
      result.provider_id,
      format(result.throughput_ops_per_microsecond, 3),
      format(result.latency_p50_microseconds),
      format(result.latency_p95_microseconds),
      format(result.latency_p99_microseconds),
    ]);
    document.getElementById("language-report").replaceChildren(
      makeTable(["Language tag", "Provider", "Throughput (ops/µs)", "p50 (µs)", "p95 (µs)", "p99 (µs)"], languageRows),
    );
    document.getElementById("language-metadata").textContent =
      `Revision ${language.source_revision} · JMH ${language.environment.jmh_version} · `
      + `Java ${language.environment.java_version} · ${language.environment.os_name} `
      + `${language.environment.architecture} · synthetic provider-only workload`;
    setDownload("postgres-report-link", "latest.json", "Download the PostgreSQL JSON report");
    setDownload("language-report-link", "language-jmh.json", "Download the multilingual JSON report");
    status.textContent = "Reports loaded. Timing is informational and depends on the recorded runner and workload.";
  }).catch((error) => {
    status.textContent = `${error.message}. Reports are published after the main-branch benchmark succeeds.`;
  });
})();
</script>
