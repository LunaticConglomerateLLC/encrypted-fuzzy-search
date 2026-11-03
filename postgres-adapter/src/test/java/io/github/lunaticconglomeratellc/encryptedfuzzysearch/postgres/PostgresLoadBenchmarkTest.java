package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.Map;
import org.testng.annotations.Test;

public class PostgresLoadBenchmarkTest {
  @Test
  public void profileDefaultsAndAcceptsRepeatableLoadParameters() {
    PostgresLoadBenchmark.Profile defaults = PostgresLoadBenchmark.Profile.parse(new String[0]);
    assertEquals(defaults.recordCount(), 600);
    assertEquals(defaults.candidateLimit(), 100);
    assertEquals(defaults.concurrency(), 4);
    assertEquals(defaults.durationSeconds(), 10);
    assertEquals(defaults.warmupSeconds(), 2);
    assertEquals(defaults.noMatchPercent(), 10);
    assertEquals(defaults.requestTimeoutMillis(), 30_000);
    assertEquals(defaults.mode(), "both");

    PostgresLoadBenchmark.Profile configured =
        PostgresLoadBenchmark.Profile.parse(
            new String[] {
              "--records=120",
              "--candidate-limit=25",
              "--concurrency=8",
              "--duration-seconds=15",
              "--warmup-seconds=3",
              "--no-match-percent=20",
              "--request-timeout-ms=8000",
              "--mode=EFS_FULL"
            });
    assertEquals(configured.recordCount(), 120);
    assertEquals(configured.candidateLimit(), 25);
    assertEquals(configured.concurrency(), 8);
    assertEquals(configured.durationSeconds(), 15);
    assertEquals(configured.warmupSeconds(), 3);
    assertEquals(configured.noMatchPercent(), 20);
    assertEquals(configured.requestTimeoutMillis(), 8_000);
    assertEquals(configured.mode(), "EFS_FULL");
  }

  @Test
  public void profileRejectsOutOfRangeAndUnknownArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PostgresLoadBenchmark.Profile.parse(new String[] {"--concurrency=0"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> PostgresLoadBenchmark.Profile.parse(new String[] {"--no-match-percent=101"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> PostgresLoadBenchmark.Profile.parse(new String[] {"--unrecognized=1"}));
  }

  @Test
  public void corpusAndQuerySizesCanBeSelectedForLoadRuns() {
    var corpus = FuzzySearchBenchmark.createCorpus(8, 4);
    var queries = FuzzySearchBenchmark.createQueries(corpus, 6);

    assertEquals(corpus.size(), 12);
    assertEquals(queries.size(), 6);
  }

  @Test
  public void jsonReportEscapesTextAndSerializesAggregateValues() {
    String json = BenchmarkReportWriter.json(Map.of("label", "synthetic\nvalue", "count", 3));

    assertTrue(json.contains("synthetic\\nvalue"));
    assertTrue(json.contains("\"count\":3"));
  }
}
