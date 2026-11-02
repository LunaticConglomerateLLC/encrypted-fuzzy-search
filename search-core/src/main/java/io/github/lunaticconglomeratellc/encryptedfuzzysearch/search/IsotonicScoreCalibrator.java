package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable pool-adjacent-violators calibration model fitted from labeled scorer outputs. */
public final class IsotonicScoreCalibrator {
  public static final int MINIMUM_SAMPLE_COUNT = 20;
  public static final int MINIMUM_PER_CLASS_COUNT = 5;
  private final String modelId;
  private final int sampleCount;
  private final int positiveCount;
  private final List<Block> blocks;

  private IsotonicScoreCalibrator(
      String modelId, int sampleCount, int positiveCount, List<Block> blocks) {
    this.modelId = modelId;
    this.sampleCount = sampleCount;
    this.positiveCount = positiveCount;
    this.blocks = List.copyOf(blocks);
  }

  /** Fits a monotone model; callers must supply a non-sensitive, stable model identifier. */
  public static IsotonicScoreCalibrator fit(
      String modelId, List<Double> rawScores, List<Boolean> relevant) {
    requireModelId(modelId);
    Objects.requireNonNull(rawScores, "rawScores");
    Objects.requireNonNull(relevant, "relevant");
    if (rawScores.size() != relevant.size() || rawScores.size() < MINIMUM_SAMPLE_COUNT) {
      throw new IllegalArgumentException(
          "calibration requires equal score/label lists with at least "
              + MINIMUM_SAMPLE_COUNT
              + " samples");
    }
    int positiveCount = 0;
    List<Observation> observations = new ArrayList<>(rawScores.size());
    for (int index = 0; index < rawScores.size(); index++) {
      double score =
          FinalScorer.requireScore(Objects.requireNonNull(rawScores.get(index), "score"));
      boolean label = Objects.requireNonNull(relevant.get(index), "relevance label");
      if (label) {
        positiveCount++;
      }
      observations.add(new Observation(score == 0.0 ? 0.0 : score, label));
    }
    int negativeCount = rawScores.size() - positiveCount;
    if (positiveCount < MINIMUM_PER_CLASS_COUNT || negativeCount < MINIMUM_PER_CLASS_COUNT) {
      throw new IllegalArgumentException(
          "calibration requires at least " + MINIMUM_PER_CLASS_COUNT + " samples per class");
    }
    observations.sort(Comparator.comparingDouble(Observation::score));

    List<MutableBlock> pooled = new ArrayList<>();
    for (Observation observation : observations) {
      MutableBlock next = MutableBlock.one(observation);
      if (!pooled.isEmpty() && pooled.getLast().maximumScore == observation.score()) {
        pooled.getLast().merge(next);
      } else {
        pooled.add(next);
      }
      while (pooled.size() >= 2) {
        MutableBlock previous = pooled.get(pooled.size() - 2);
        MutableBlock current = pooled.getLast();
        if (previous.rate() <= current.rate()) {
          break;
        }
        previous.merge(current);
        pooled.removeLast();
      }
    }
    List<Block> immutableBlocks =
        pooled.stream().map(block -> new Block(block.maximumScore, block.rate())).toList();
    return new IsotonicScoreCalibrator(modelId, rawScores.size(), positiveCount, immutableBlocks);
  }

  public String modelId() {
    return modelId;
  }

  public int sampleCount() {
    return sampleCount;
  }

  public int positiveCount() {
    return positiveCount;
  }

  /** Maps a raw score to a monotone empirical relevance estimate in [0, 1]. */
  public double calibrate(double rawScore) {
    FinalScorer.requireScore(rawScore);
    for (Block block : blocks) {
      if (rawScore <= block.maximumScore()) {
        return block.probability();
      }
    }
    return blocks.getLast().probability();
  }

  private static void requireModelId(String modelId) {
    Objects.requireNonNull(modelId, "modelId");
    if (!modelId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("modelId must be a non-sensitive stable identifier");
    }
  }

  private record Observation(double score, boolean relevant) {}

  private record Block(double maximumScore, double probability) {}

  private static final class MutableBlock {
    private double maximumScore;
    private int count;
    private int positives;

    private MutableBlock(double maximumScore, int count, int positives) {
      this.maximumScore = maximumScore;
      this.count = count;
      this.positives = positives;
    }

    static MutableBlock one(Observation observation) {
      return new MutableBlock(observation.score(), 1, observation.relevant() ? 1 : 0);
    }

    void merge(MutableBlock other) {
      maximumScore = other.maximumScore;
      count += other.count;
      positives += other.positives;
    }

    double rate() {
      return (double) positives / count;
    }
  }
}
