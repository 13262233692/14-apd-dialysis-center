package com.apd.dialysis.service;

import com.apd.dialysis.algorithm.IsolationForestTurbidityDetector;
import com.apd.dialysis.buffer.DialysisDataBuffer;
import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.model.PeritonitisAlert;
import com.apd.dialysis.model.TurbidityReading;
import com.apd.dialysis.websocket.DialysisWebSocketBroadcaster;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class TurbidityAlertService {

    public static final String TOPIC_ALERT = "/topic/dialysis/alerts/peritonitis";
    private static final long HISTORY_WINDOW_MS = 72 * 60 * 60 * 1000L;
    private static final int MAX_HISTORY_ENTRIES = 8640;
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000L;

    private final DialysisDataBuffer dataBuffer;
    private final IsolationForestTurbidityDetector detector;
    private final DialysisWebSocketBroadcaster wsBroadcaster;

    private final Deque<TurbidityReading> turbidityHistory = new ConcurrentLinkedDeque<>();
    private final Deque<Double> drainFlowHistory = new ConcurrentLinkedDeque<>();
    private final AtomicReference<PeritonitisAlert> currentAlert = new AtomicReference<>();
    private final AtomicLong lastAlertAt = new AtomicLong(0);
    private final AtomicBoolean alertAcknowledged = new AtomicBoolean(false);

    private ExecutorService analysisExecutor;

    @PostConstruct
    public void init() {
        analysisExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "turbidity-analyzer");
            t.setDaemon(true);
            return t;
        });
        dataBuffer.addFlushListener(this::ingestDataPoint);
        log.info("TurbidityAlertService initialized: 72h window, cooldown={}s", ALERT_COOLDOWN_MS / 1000);
    }

    @PreDestroy
    public void destroy() {
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
        }
        log.info("TurbidityAlertService stopped");
    }

    private void ingestDataPoint(DialysisDataPoint point) {
        if (point == null) return;
        TurbidityReading reading = point.toTurbidityReading();
        if (reading.getTransmittancePercent() <= 0) {
            reading.setTransmittancePercent(98.0);
            reading.setAbsorbance420nm(0.04);
            reading.setAbsorbance540nm(0.03);
            reading.setAbsorbance660nm(0.02);
            reading.setAbsorbance720nm(0.02);
        }
        turbidityHistory.offerLast(reading);
        drainFlowHistory.offerLast(reading.getDrainFlowRateMlPerMin());
        trimHistory();
    }

    private void trimHistory() {
        Instant now = Instant.now();
        while (!turbidityHistory.isEmpty()) {
            TurbidityReading first = turbidityHistory.peekFirst();
            if (first == null || first.getTimestamp() == null) {
                turbidityHistory.pollFirst();
                continue;
            }
            long ageMs = Duration.between(first.getTimestamp(), now).toMillis();
            if (ageMs > HISTORY_WINDOW_MS || turbidityHistory.size() > MAX_HISTORY_ENTRIES) {
                turbidityHistory.pollFirst();
                if (drainFlowHistory.size() > 0) drainFlowHistory.pollFirst();
            } else {
                break;
            }
        }
        while (drainFlowHistory.size() > MAX_HISTORY_ENTRIES) {
            drainFlowHistory.pollFirst();
        }
    }

    @Scheduled(fixedDelay = 10000L)
    public void scheduledAnalysis() {
        if (analysisExecutor == null || analysisExecutor.isShutdown()) return;
        try {
            analysisExecutor.submit(this::runAnalysisPipeline);
        } catch (Exception e) {
            log.debug("Turbidity analysis submit error", e);
        }
    }

    private void runAnalysisPipeline() {
        try {
            TurbidityReading latest = turbidityHistory.peekLast();
            if (latest == null) return;

            if (latest.getAlertLevel() != null
                    && latest.getAlertLevel().ordinal() < TurbidityReading.AlertLevel.HIGH.ordinal()
                    && latest.getTransmittancePercent() > 85) {
                return;
            }

            Future<List<TurbidityReading>> historyFuture = Executors.newSingleThreadExecutor().submit(this::fetchTurbidityHistory72h);
            Future<List<Double>> flowFuture = Executors.newSingleThreadExecutor().submit(this::fetchDrainFlowHistory);

            List<TurbidityReading> history72h = historyFuture.get(3, java.util.concurrent.TimeUnit.SECONDS);
            List<Double> drainFlows = flowFuture.get(3, java.util.concurrent.TimeUnit.SECONDS);

            TurbidityReading analyzed = detector.analyze(latest);
            IsolationForestTurbidityDetector.DetectionResult result =
                    detector.analyzeWithContext(analyzed, history72h, drainFlows);

            if (result.isPeritonitisSuspected) {
                triggerCriticalAlert(result, history72h, drainFlows);
            } else if (result.alertLevel.ordinal() >= TurbidityReading.AlertLevel.HIGH.ordinal()) {
                log.warn("Turbidity HIGH alert: score={}, drop={}%, drainExt={}%, flowDrop={}%",
                        String.format("%.3f", result.anomalyScore),
                        String.format("%.1f", result.transmittanceDropPercent),
                        String.format("%.1f", result.drainTimeExtensionPercent),
                        String.format("%.1f", result.drainFlowDropPercent));
            }

        } catch (Exception e) {
            log.error("Turbidity analysis pipeline error", e);
        }
    }

    private List<TurbidityReading> fetchTurbidityHistory72h() {
        List<TurbidityReading> result = new ArrayList<>();
        int stride = Math.max(1, turbidityHistory.size() / 600);
        int count = 0;
        for (TurbidityReading r : turbidityHistory) {
            if (count % stride == 0) {
                result.add(r);
            }
            count++;
        }
        result.sort(Comparator.comparing(TurbidityReading::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private List<Double> fetchDrainFlowHistory() {
        List<Double> result = new ArrayList<>();
        int stride = Math.max(1, drainFlowHistory.size() / 600);
        int count = 0;
        for (Double v : drainFlowHistory) {
            if (count % stride == 0) {
                result.add(v);
            }
            count++;
        }
        return result;
    }

    private void triggerCriticalAlert(IsolationForestTurbidityDetector.DetectionResult result,
                                      List<TurbidityReading> history72h,
                                      List<Double> drainFlows) {
        long now = System.currentTimeMillis();
        if (now - lastAlertAt.get() < ALERT_COOLDOWN_MS && currentAlert.get() != null) {
            return;
        }
        if (alertAcknowledged.get() && now - lastAlertAt.get() < 30 * 60 * 1000L) {
            return;
        }

        String alertId = "PERI-" + Long.toHexString(now).toUpperCase();
        PeritonitisAlert alert = PeritonitisAlert.critical(
                alertId, result.reading,
                result.transmittanceDropPercent,
                result.drainTimeExtensionPercent,
                history72h, drainFlows
        );

        lastAlertAt.set(now);
        currentAlert.set(alert);
        alertAcknowledged.set(false);

        log.error("=========================================");
        log.error("!!! PERITONITIS CRITICAL ALERT: {} !!!", alertId);
        log.error("  Transmittance drop: {}%", String.format("%.1f", result.transmittanceDropPercent));
        log.error("  Drain time extension: {}%", String.format("%.1f", result.drainTimeExtensionPercent));
        log.error("  IsolationForest score: {}", String.format("%.3f", result.anomalyScore));
        log.error("=========================================");

        wsBroadcaster.broadcastAlert(alert);
    }

    public PeritonitisAlert getCurrentAlert() {
        return currentAlert.get();
    }

    public boolean acknowledgeAlert(String alertId) {
        PeritonitisAlert existing = currentAlert.get();
        if (existing != null && existing.getAlertId().equals(alertId)) {
            existing.setAcknowledged(true);
            alertAcknowledged.set(true);
            log.info("Alert acknowledged: {}", alertId);
            wsBroadcaster.broadcastAlert(existing);
            return true;
        }
        return false;
    }

    public void dismissAlert() {
        currentAlert.set(null);
        alertAcknowledged.set(false);
    }

    public List<TurbidityReading> getTurbidityHistory72h() {
        return fetchTurbidityHistory72h();
    }

    public List<Double> getDrainFlowHistory() {
        return fetchDrainFlowHistory();
    }

    public int getHistorySize() {
        return turbidityHistory.size();
    }
}
