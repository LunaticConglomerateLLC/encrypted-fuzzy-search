package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Held-out calibration measurements; values are diagnostics, not quality guarantees. */
public record ScoreCalibrationMetrics(
    int sampleCount, int binCount, double brierScore, double expectedCalibrationError) {
  public ScoreCalibrationMetrics {
    if (sampleCount < 1 || binCount < 1) {
      throw new IllegalArgumentException("sampleCount and binCount must be positive");
    }
    if (!Double.isFinite(brierScore)
        || brierScore < 0.0
        || brierScore > 1.0
        || !Double.isFinite(expectedCalibrationError)
        || expectedCalibrationError < 0.0
        || expectedCalibrationError > 1.0) {
      throw new IllegalArgumentException("calibration metrics must be finite and in [0, 1]");
    }
  }
}
