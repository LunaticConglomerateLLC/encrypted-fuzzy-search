package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Computes Brier score and equal-width expected calibration error from labeled score pairs. */
public final class ScoreCalibrationEvaluator {
  private ScoreCalibrationEvaluator() {}

  public static ScoreCalibrationMetrics evaluate(
      List<Double> probabilities, List<Boolean> relevant, int binCount) {
    Objects.requireNonNull(probabilities, "probabilities");
    Objects.requireNonNull(relevant, "relevant");
    if (probabilities.isEmpty() || probabilities.size() != relevant.size()) {
      throw new IllegalArgumentException("probabilities and labels must have equal non-zero size");
    }
    if (binCount < 1 || binCount > probabilities.size()) {
      throw new IllegalArgumentException("binCount must be between 1 and sample count");
    }
    int[] counts = new int[binCount];
    double[] confidenceSums = new double[binCount];
    int[] positives = new int[binCount];
    double brierSum = 0.0;
    for (int index = 0; index < probabilities.size(); index++) {
      double probability =
          FinalScorer.requireScore(Objects.requireNonNull(probabilities.get(index)));
      boolean label = Objects.requireNonNull(relevant.get(index), "relevance label");
      double observed = label ? 1.0 : 0.0;
      double error = probability - observed;
      brierSum += error * error;
      int bin = Math.min(binCount - 1, (int) (probability * binCount));
      counts[bin]++;
      confidenceSums[bin] += probability;
      if (label) {
        positives[bin]++;
      }
    }
    double expectedCalibrationError = 0.0;
    for (int bin = 0; bin < binCount; bin++) {
      if (counts[bin] == 0) {
        continue;
      }
      double averageConfidence = confidenceSums[bin] / counts[bin];
      double observedRate = (double) positives[bin] / counts[bin];
      expectedCalibrationError +=
          ((double) counts[bin] / probabilities.size())
              * Math.abs(averageConfidence - observedRate);
    }
    return new ScoreCalibrationMetrics(
        probabilities.size(), binCount, brierSum / probabilities.size(), expectedCalibrationError);
  }

  /** Convenience for using a fitted isotonic model before evaluating held-out labels. */
  public static ScoreCalibrationMetrics evaluate(
      IsotonicScoreCalibrator calibrator,
      List<Double> rawScores,
      List<Boolean> relevant,
      int binCount) {
    Objects.requireNonNull(calibrator, "calibrator");
    Objects.requireNonNull(rawScores, "rawScores");
    List<Double> calibrated = new ArrayList<>(rawScores.size());
    for (Double score : rawScores) {
      calibrated.add(calibrator.calibrate(Objects.requireNonNull(score, "raw score")));
    }
    return evaluate(calibrated, relevant, binCount);
  }
}
