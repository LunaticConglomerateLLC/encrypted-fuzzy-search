package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

public class IsotonicScoreCalibratorTest {
  @Test
  public void fitsMonotoneModelAndReportsOnlyNonSensitiveMetadata() {
    List<Double> scores = new ArrayList<>();
    List<Boolean> labels = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      scores.add(index / 100.0);
      labels.add(false);
      scores.add(0.8 + index / 100.0);
      labels.add(true);
    }
    IsotonicScoreCalibrator model =
        IsotonicScoreCalibrator.fit("synthetic-fixture-v1", scores, labels);

    assertEquals(model.modelId(), "synthetic-fixture-v1");
    assertEquals(model.sampleCount(), 20);
    assertEquals(model.positiveCount(), 10);
    assertEquals(model.calibrate(0.08), 0.0);
    assertEquals(model.calibrate(0.75), 1.0);
    assertEquals(model.calibrate(0.82), 1.0);
    assertEquals(model.calibrate(1.0), 1.0);
    assertTrue(model.calibrate(0.1) <= model.calibrate(0.9));
    assertTrue(!model.toString().contains("synthetic"));
  }

  @Test
  public void poolsNonMonotoneObservationsAndRepeatedScores() {
    List<Double> scores = new ArrayList<>();
    List<Boolean> labels = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      scores.add(0.2);
      labels.add(index < 8);
      scores.add(0.8);
      labels.add(index < 2);
    }
    IsotonicScoreCalibrator model = IsotonicScoreCalibrator.fit("pooled-v1", scores, labels);

    assertEquals(model.calibrate(0.2), 0.5);
    assertEquals(model.calibrate(0.8), 0.5);
  }

  @Test
  public void requiresStableIdAdequateSamplesBothClassesAndValidScores() {
    List<Double> twentyScores = java.util.Collections.nCopies(20, 0.5);
    List<Boolean> tenPositive = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      tenPositive.add(index < 10);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> IsotonicScoreCalibrator.fit("bad id", twentyScores, tenPositive));
    assertThrows(
        IllegalArgumentException.class,
        () -> IsotonicScoreCalibrator.fit("too-few", List.of(0.1), List.of(false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> IsotonicScoreCalibrator.fit("mismatch", twentyScores, List.of(true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IsotonicScoreCalibrator.fit(
                "one-class", twentyScores, java.util.Collections.nCopies(20, true)));
    List<Double> invalidScores = new ArrayList<>(twentyScores);
    invalidScores.set(0, Double.NaN);
    assertThrows(
        IllegalArgumentException.class,
        () -> IsotonicScoreCalibrator.fit("invalid", invalidScores, tenPositive));
    assertThrows(
        NullPointerException.class,
        () -> IsotonicScoreCalibrator.fit(null, twentyScores, tenPositive));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IsotonicScoreCalibrator.fit(
                "bad-label-count", twentyScores, labelsWithFourPositives()));
  }

  @Test
  public void evaluatesBrierAndExpectedCalibrationErrorOnHeldOutLabels() {
    ScoreCalibrationMetrics metrics =
        ScoreCalibrationEvaluator.evaluate(
            List.of(0.1, 0.2, 0.9, 1.0), List.of(false, false, true, true), 4);
    assertEquals(metrics.sampleCount(), 4);
    assertEquals(metrics.binCount(), 4);
    assertEquals(metrics.brierScore(), 0.015, 0.000001);
    assertEquals(metrics.expectedCalibrationError(), 0.1, 0.000001);

    IsotonicScoreCalibrator model =
        IsotonicScoreCalibrator.fit("heldout-model", trainingScores(), trainingLabels());
    ScoreCalibrationMetrics calibrated =
        ScoreCalibrationEvaluator.evaluate(
            model, List.of(0.1, 0.2, 0.9, 1.0), List.of(false, false, true, true), 4);
    assertEquals(calibrated.brierScore(), 0.0);
    assertEquals(calibrated.expectedCalibrationError(), 0.0);
  }

  @Test
  public void validatesMetricInputsAndCalibrationScoreRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreCalibrationEvaluator.evaluate(List.of(), List.of(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreCalibrationEvaluator.evaluate(List.of(0.5), List.of(false, true), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreCalibrationEvaluator.evaluate(List.of(0.5), List.of(false), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreCalibrationEvaluator.evaluate(List.of(0.5), List.of(false), 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreCalibrationEvaluator.evaluate(List.of(2.0), List.of(false), 1));
    assertThrows(IllegalArgumentException.class, () -> new ScoreCalibrationMetrics(0, 1, 0.0, 0.0));
    assertThrows(
        IllegalArgumentException.class, () -> new ScoreCalibrationMetrics(1, 1, Double.NaN, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new ScoreCalibrationMetrics(1, 1, 0.0, 1.1));
    IsotonicScoreCalibrator model =
        IsotonicScoreCalibrator.fit("bounds", trainingScores(), trainingLabels());
    assertThrows(IllegalArgumentException.class, () -> model.calibrate(-0.1));
    assertThrows(IllegalArgumentException.class, () -> model.calibrate(Double.NaN));
    assertThrows(
        NullPointerException.class,
        () -> ScoreCalibrationEvaluator.evaluate(model, null, List.of(), 1));
  }

  private static List<Double> trainingScores() {
    List<Double> scores = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      scores.add(index * 0.05);
      scores.add(0.55 + index * 0.05);
    }
    return List.copyOf(scores);
  }

  private static List<Boolean> trainingLabels() {
    List<Boolean> labels = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      labels.add(false);
      labels.add(true);
    }
    return List.copyOf(labels);
  }

  private static List<Boolean> labelsWithFourPositives() {
    List<Boolean> labels = new ArrayList<>(java.util.Collections.nCopies(20, false));
    for (int index = 0; index < 4; index++) {
      labels.set(index, true);
    }
    return labels;
  }
}
