package com.apd.dialysis.algorithm;

import com.apd.dialysis.model.TurbidityReading;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class IsolationForestTurbidityDetector {

    private static final int NUM_TREES = 32;
    private static final int SAMPLE_SIZE = 128;
    private static final int MAX_TREE_DEPTH = 12;
    private static final int WINDOW_CAPACITY = 5000;
    private static final int FEATURE_DIM = 7;

    private static final double ANOMALY_SCORE_CRITICAL = 0.80;
    private static final double ANOMALY_SCORE_HIGH = 0.65;
    private static final double ANOMALY_SCORE_MEDIUM = 0.45;
    private static final double ANOMALY_SCORE_LOW = 0.30;

    private final ConcurrentLinkedDeque<double[]> baselineWindow = new ConcurrentLinkedDeque<>();
    private final AtomicInteger baselineSize = new AtomicInteger(0);

    private ITree[] forest;
    private final Random random = new Random(42);

    @PostConstruct
    public void init() {
        forest = new ITree[NUM_TREES];
        for (int i = 0; i < NUM_TREES; i++) {
            forest[i] = buildEmptyTree();
        }
        seedInitialBaseline();
        log.info("IsolationForestTurbidityDetector initialized: trees={}, sampleSize={}, windowCap={}",
                NUM_TREES, SAMPLE_SIZE, WINDOW_CAPACITY);
    }

    private void seedInitialBaseline() {
        for (int i = 0; i < 200; i++) {
            double[] sample = new double[FEATURE_DIM];
            sample[0] = 95 + random.nextGaussian() * 3;
            sample[1] = 0.05 + Math.abs(random.nextGaussian() * 0.01);
            sample[2] = 0.03 + Math.abs(random.nextGaussian() * 0.01);
            sample[3] = 0.02 + Math.abs(random.nextGaussian() * 0.005);
            sample[4] = 0.02 + Math.abs(random.nextGaussian() * 0.005);
            sample[5] = 90 + random.nextGaussian() * 5;
            sample[6] = 1.0 + random.nextGaussian() * 0.1;
            addToBaseline(sample);
        }
        retrain();
    }

    public TurbidityReading analyze(TurbidityReading reading) {
        if (reading == null) return null;

        double[] features = extractFeatures(reading);
        double score = computeAnomalyScore(features);

        reading.setAnomalyScore(score);
        reading.setAlertLevel(scoreToLevel(score));
        reading.setSpectralHexSignature(reading.toSpectralHex());
        reading.setAnomalyFlag(score >= ANOMALY_SCORE_HIGH);

        if (score < ANOMALY_SCORE_LOW && random.nextDouble() < 0.05) {
            addToBaseline(features);
            if (baselineSize.get() >= SAMPLE_SIZE * 2 && random.nextDouble() < 0.02) {
                retrain();
            }
        }
        return reading;
    }

    public DetectionResult analyzeWithContext(TurbidityReading reading,
                                              List<TurbidityReading> history72h,
                                              List<Double> drainFlowHistory) {
        TurbidityReading analyzed = analyze(reading);
        DetectionResult result = new DetectionResult();
        result.reading = analyzed;
        result.anomalyScore = analyzed.getAnomalyScore();
        result.alertLevel = analyzed.getAlertLevel();

        if (history72h != null && !history72h.isEmpty()) {
            double baselineTransmittance = history72h.stream()
                    .mapToDouble(TurbidityReading::getTransmittancePercent)
                    .filter(v -> v > 50)
                    .average().orElse(95.0);
            result.transmittanceDropPercent = Math.max(0,
                    (baselineTransmittance - analyzed.getTransmittancePercent()) / Math.max(1.0, baselineTransmittance) * 100.0);

            double baselineDrainFlow = history72h.stream()
                    .mapToDouble(TurbidityReading::getDrainFlowRateMlPerMin)
                    .filter(v -> v > 10)
                    .average().orElse(90.0);
            if (baselineDrainFlow > 10 && analyzed.getDrainFlowRateMlPerMin() > 0) {
                result.drainFlowDropPercent = Math.max(0,
                        (baselineDrainFlow - analyzed.getDrainFlowRateMlPerMin()) / Math.max(1.0, baselineDrainFlow) * 100.0);
            }
        }

        if (drainFlowHistory != null && !drainFlowHistory.isEmpty()) {
            double baselineDrainFlow = drainFlowHistory.stream()
                    .mapToDouble(v -> v)
                    .filter(v -> v > 10)
                    .average().orElse(90.0);
            if (baselineDrainFlow > 10 && analyzed.getDrainFlowRateMlPerMin() > 0) {
                double drop = Math.max(0,
                        (baselineDrainFlow - analyzed.getDrainFlowRateMlPerMin()) / Math.max(1.0, baselineDrainFlow) * 100.0);
                if (drop > result.drainFlowDropPercent) {
                    result.drainFlowDropPercent = drop;
                }
            }
        }

        if (reading.getDrainTargetMinutes() > 0) {
            double normalTarget = reading.getDrainTargetMinutes() / 1.8;
            if (normalTarget > 1 && reading.getDrainElapsedMinutes() > normalTarget) {
                result.drainTimeExtensionPercent =
                        (reading.getDrainElapsedMinutes() - normalTarget)
                                / normalTarget * 100.0;
            }
            if (reading.getDrainElapsedMinutes() > reading.getDrainTargetMinutes()) {
                double ext = (reading.getDrainElapsedMinutes() - reading.getDrainTargetMinutes())
                        / reading.getDrainTargetMinutes() * 100.0;
                if (ext > result.drainTimeExtensionPercent) {
                    result.drainTimeExtensionPercent = ext;
                }
            }
        }

        boolean scoreCritical = result.anomalyScore >= ANOMALY_SCORE_CRITICAL;
        boolean dropWithTimeExt = result.transmittanceDropPercent >= 25.0
                && (result.drainTimeExtensionPercent >= 25.0 || result.drainFlowDropPercent >= 40.0);
        boolean highScoreWithSignals = result.anomalyScore >= ANOMALY_SCORE_HIGH
                && result.transmittanceDropPercent >= 15.0
                && (result.drainTimeExtensionPercent >= 15.0 || result.drainFlowDropPercent >= 30.0);
        boolean severeDropOnly = result.transmittanceDropPercent >= 40.0
                && result.anomalyScore >= ANOMALY_SCORE_MEDIUM;

        result.isPeritonitisSuspected = scoreCritical || dropWithTimeExt || highScoreWithSignals || severeDropOnly;

        if (result.isPeritonitisSuspected && result.alertLevel.ordinal() < TurbidityReading.AlertLevel.CRITICAL.ordinal()) {
            result.alertLevel = TurbidityReading.AlertLevel.CRITICAL;
            analyzed.setAlertLevel(TurbidityReading.AlertLevel.CRITICAL);
        }
        return result;
    }

    private double[] extractFeatures(TurbidityReading r) {
        double[] f = new double[FEATURE_DIM];
        f[0] = r.getTransmittancePercent();
        f[1] = r.getAbsorbance420nm();
        f[2] = r.getAbsorbance540nm();
        f[3] = r.getAbsorbance660nm();
        f[4] = r.getAbsorbance720nm();
        f[5] = r.getDrainFlowRateMlPerMin();
        double ratio = r.getDrainTargetMinutes() > 0
                ? r.getDrainElapsedMinutes() / r.getDrainTargetMinutes()
                : 1.0;
        f[6] = ratio;
        return f;
    }

    private double computeAnomalyScore(double[] sample) {
        if (forest == null || forest.length == 0) return 0.0;
        double totalPathLength = 0.0;
        int validTrees = 0;
        for (ITree tree : forest) {
            if (tree != null && tree.root != null) {
                totalPathLength += pathLength(tree.root, sample, 0);
                validTrees++;
            }
        }
        if (validTrees == 0) return 0.0;
        double avgPath = totalPathLength / validTrees;
        double c = computeC(SAMPLE_SIZE);
        double score = Math.pow(2.0, -avgPath / c);
        return Math.max(0.0, Math.min(1.0, score));
    }

    private double pathLength(INode node, double[] sample, int currentDepth) {
        if (node == null || currentDepth >= MAX_TREE_DEPTH || node instanceof ExNode) {
            return currentDepth + computeC(((ExNode) node).size);
        }
        InNode in = (InNode) node;
        if (sample[in.splitAttr] < in.splitValue) {
            return pathLength(in.left, sample, currentDepth + 1);
        } else {
            return pathLength(in.right, sample, currentDepth + 1);
        }
    }

    private double computeC(int n) {
        if (n <= 1) return 0.0;
        double H = 0.0;
        for (int i = 1; i < n; i++) H += 1.0 / i;
        return 2.0 * H - (2.0 * (n - 1)) / n;
    }

    private TurbidityReading.AlertLevel scoreToLevel(double score) {
        if (score >= ANOMALY_SCORE_CRITICAL) return TurbidityReading.AlertLevel.CRITICAL;
        if (score >= ANOMALY_SCORE_HIGH) return TurbidityReading.AlertLevel.HIGH;
        if (score >= ANOMALY_SCORE_MEDIUM) return TurbidityReading.AlertLevel.MEDIUM;
        if (score >= ANOMALY_SCORE_LOW) return TurbidityReading.AlertLevel.LOW;
        return TurbidityReading.AlertLevel.NONE;
    }

    private synchronized void retrain() {
        if (baselineSize.get() < SAMPLE_SIZE) return;
        List<double[]> all = new ArrayList<>(baselineWindow);
        for (int t = 0; t < NUM_TREES; t++) {
            List<double[]> sample = randomSample(all, SAMPLE_SIZE);
            forest[t] = buildTree(sample, 0);
        }
        log.debug("Isolation Forest retrained on {} baseline samples", all.size());
    }

    private List<double[]> randomSample(List<double[]> population, int n) {
        List<double[]> result = new ArrayList<>(n);
        int popSize = population.size();
        for (int i = 0; i < n; i++) {
            result.add(population.get(random.nextInt(popSize)));
        }
        return result;
    }

    private ITree buildTree(List<double[]> data, int depth) {
        ITree tree = new ITree();
        tree.root = buildNode(data, depth);
        return tree;
    }

    private INode buildNode(List<double[]> data, int depth) {
        if (depth >= MAX_TREE_DEPTH || data.size() <= 1) {
            ExNode ex = new ExNode();
            ex.size = data.size();
            return ex;
        }
        int q = random.nextInt(FEATURE_DIM);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] row : data) {
            if (row[q] < min) min = row[q];
            if (row[q] > max) max = row[q];
        }
        if (Math.abs(max - min) < 1e-9) {
            ExNode ex = new ExNode();
            ex.size = data.size();
            return ex;
        }
        double splitValue = min + random.nextDouble() * (max - min);
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (double[] row : data) {
            if (row[q] < splitValue) left.add(row);
            else right.add(row);
        }
        InNode in = new InNode();
        in.splitAttr = q;
        in.splitValue = splitValue;
        in.left = buildNode(left, depth + 1);
        in.right = buildNode(right, depth + 1);
        return in;
    }

    private ITree buildEmptyTree() {
        ExNode ex = new ExNode();
        ex.size = 1;
        ITree t = new ITree();
        t.root = ex;
        return t;
    }

    private void addToBaseline(double[] features) {
        baselineWindow.offerLast(features);
        while (baselineWindow.size() > WINDOW_CAPACITY) {
            baselineWindow.pollFirst();
        }
        baselineSize.set(baselineWindow.size());
    }

    public static class DetectionResult {
        public TurbidityReading reading;
        public double anomalyScore;
        public TurbidityReading.AlertLevel alertLevel;
        public double transmittanceDropPercent;
        public double drainTimeExtensionPercent;
        public double drainFlowDropPercent;
        public boolean isPeritonitisSuspected;
    }

    private static class ITree { INode root; }
    private interface INode {}
    private static class InNode implements INode {
        int splitAttr;
        double splitValue;
        INode left;
        INode right;
    }
    private static class ExNode implements INode { int size = 0; }
}
