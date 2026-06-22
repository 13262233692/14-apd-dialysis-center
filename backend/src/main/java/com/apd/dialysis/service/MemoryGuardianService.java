package com.apd.dialysis.service;

import com.apd.dialysis.buffer.DialysisDataBuffer;
import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.hardware.HardwareService;
import com.apd.dialysis.websocket.DialysisWebSocketBroadcaster;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryGuardianService {

    private static final long MONITOR_INTERVAL_MS = 5000L;
    private static final double HEAP_CRITICAL_RATIO = 0.92;
    private static final double HEAP_DANGER_RATIO = 0.85;
    private static final double HEAP_WARN_RATIO = 0.70;
    private static final int FORCE_GC_MIN_INTERVAL_MS = 30000;
    private static final long GC_PAUSE_WARN_THRESHOLD_MS = 500;

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    private final ApplicationContext applicationContext;
    private final ApdProperties properties;

    private ScheduledExecutorService monitorExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean criticalMode = new AtomicBoolean(false);
    private final AtomicLong lastForceGcAt = new AtomicLong(0);
    private final AtomicLong totalForceGcCount = new AtomicLong(0);
    private final AtomicLong totalGcPauseMs = new AtomicLong(0);
    private final AtomicLong peakGcPauseMs = new AtomicLong(0);

    private volatile double lastHeapUsageRatio = 0.0;
    private volatile double lastOldGenUsageRatio = 0.0;

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-guardian");
            t.setDaemon(true);
            return t;
        });
        monitorExecutor.scheduleAtFixedRate(
                this::monitorTick,
                MONITOR_INTERVAL_MS,
                MONITOR_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.info("Memory Guardian started. critical={}%, danger={}%, warn={}%, interval={}ms",
                (int) (HEAP_CRITICAL_RATIO * 100),
                (int) (HEAP_DANGER_RATIO * 100),
                (int) (HEAP_WARN_RATIO * 100),
                MONITOR_INTERVAL_MS);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        log.info("Memory Guardian stopped. totalForceGc={}, peakGcPause={}ms",
                totalForceGcCount.get(), peakGcPauseMs.get());
    }

    private void monitorTick() {
        try {
            MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
            double heapRatio = ratioOf(heap);
            lastHeapUsageRatio = heapRatio;

            double oldGenRatio = getOldGenUsageRatio();
            lastOldGenUsageRatio = oldGenRatio;

            measureGcPauses();

            if (heapRatio >= HEAP_CRITICAL_RATIO) {
                handleCritical(heap, heapRatio, oldGenRatio);
            } else if (heapRatio >= HEAP_DANGER_RATIO) {
                handleDanger(heap, heapRatio, oldGenRatio);
            } else if (heapRatio >= HEAP_WARN_RATIO) {
                if (!criticalMode.get()) {
                    log.warn("HEAP WARNING: used={}MB, max={}MB, ratio={}%, oldGen={}%",
                            heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024,
                            (int) (heapRatio * 100), (int) (oldGenRatio * 100));
                }
            } else {
                if (criticalMode.compareAndSet(true, false)) {
                    log.info("HEAP recovered: ratio={}%, critical mode deactivated", (int) (heapRatio * 100));
                }
            }
        } catch (Exception e) {
            log.error("Memory Guardian tick error", e);
        }
    }

    private void handleCritical(MemoryUsage heap, double heapRatio, double oldGenRatio) {
        boolean firstTime = criticalMode.compareAndSet(false, true);
        if (firstTime) {
            log.error("========== HEAP CRITICAL ACTIVATED ==========");
            log.error("HEAP CRITICAL: used={}MB, max={}MB, ratio={}%, oldGen={}%",
                    heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024,
                    (int) (heapRatio * 100), (int) (oldGenRatio * 100));
        }

        forceGcIfNeeded();
        emergencyDropBuffers();
    }

    private void handleDanger(MemoryUsage heap, double heapRatio, double oldGenRatio) {
        log.warn("HEAP DANGER: used={}MB, max={}MB, ratio={}%, oldGen={}%",
                heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024,
                (int) (heapRatio * 100), (int) (oldGenRatio * 100));

        forceGcIfNeeded();
        dropHardwareBuffers();
    }

    private void forceGcIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastForceGcAt.get() < FORCE_GC_MIN_INTERVAL_MS) return;
        if (!lastForceGcAt.compareAndSet(lastForceGcAt.get(), now)) return;

        long start = System.currentTimeMillis();
        log.warn("Memory Guardian forcing System.gc()...");
        try {
            System.gc();
        } catch (Exception ignored) {}
        long elapsed = System.currentTimeMillis() - start;
        totalForceGcCount.incrementAndGet();
        totalGcPauseMs.addAndGet(elapsed);
        updatePeak(elapsed);
        log.warn("Memory Guardian System.gc() finished in {}ms", elapsed);
    }

    private void emergencyDropBuffers() {
        try {
            DialysisDataBuffer buffer = safeGetBean(DialysisDataBuffer.class);
            if (buffer != null) {
                int sizeBefore = buffer.size();
                List<?> dropped = buffer.drainAll();
                log.error("Memory Guardian: EMERGENCY dropped {} dialysis points (was {})",
                        dropped != null ? dropped.size() : 0, sizeBefore);
            }
        } catch (Exception e) {
            log.error("Error dropping buffers in guardian", e);
        }
    }

    private void dropHardwareBuffers() {
        try {
            DialysisDataBuffer buffer = safeGetBean(DialysisDataBuffer.class);
            if (buffer != null) {
                int sizeBefore = buffer.size();
                if (sizeBefore > 1000) {
                    List<?> all = buffer.drainAll();
                    int keepCount = Math.min(200, all != null ? all.size() : 0);
                    if (all != null && keepCount > 0) {
                        for (int i = all.size() - keepCount; i < all.size(); i++) {
                            buffer.produce((com.apd.dialysis.model.DialysisDataPoint) all.get(i));
                        }
                    }
                    log.warn("Memory Guardian: trimmed dialysis buffer {} -> {} (kept latest {})",
                            sizeBefore, buffer.size(), keepCount);
                }
            }
        } catch (Exception e) {
            log.debug("Error trimming buffers in guardian", e);
        }
    }

    private void measureGcPauses() {
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long last = gcBean.getCollectionTime();
            if (last > 0) {
                updatePeak(last);
            }
        }
    }

    private void updatePeak(long value) {
        long prev;
        do {
            prev = peakGcPauseMs.get();
            if (value <= prev) return;
        } while (!peakGcPauseMs.compareAndSet(prev, value));
    }

    private double getOldGenUsageRatio() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            if (name.contains("Old Gen") || name.contains("Tenured") || name.contains("PS Old")) {
                MemoryUsage usage = pool.getUsage();
                return ratioOf(usage);
            }
        }
        return 0.0;
    }

    private static double ratioOf(MemoryUsage usage) {
        if (usage == null) return 0.0;
        long max = usage.getMax();
        if (max <= 0) return 0.0;
        return (double) usage.getUsed() / max;
    }

    private <T> T safeGetBean(Class<T> type) {
        try {
            return applicationContext.getBean(type);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isCriticalMode() { return criticalMode.get(); }
    public double getLastHeapUsageRatio() { return lastHeapUsageRatio; }
    public double getLastOldGenUsageRatio() { return lastOldGenUsageRatio; }
    public long getTotalForceGcCount() { return totalForceGcCount.get(); }
    public long getTotalGcPauseMs() { return totalGcPauseMs.get(); }
    public long getPeakGcPauseMs() { return peakGcPauseMs.get(); }

    public MemorySnapshot getSnapshot() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
        return new MemorySnapshot(
                heap.getUsed(), heap.getCommitted(), heap.getMax(),
                nonHeap.getUsed(), nonHeap.getCommitted(), nonHeap.getMax(),
                lastHeapUsageRatio, lastOldGenUsageRatio,
                criticalMode.get(), totalForceGcCount.get(), peakGcPauseMs.get()
        );
    }

    public static class MemorySnapshot {
        public final long heapUsed;
        public final long heapCommitted;
        public final long heapMax;
        public final long nonHeapUsed;
        public final long nonHeapCommitted;
        public final long nonHeapMax;
        public final double heapUsageRatio;
        public final double oldGenUsageRatio;
        public final boolean criticalMode;
        public final long totalForceGcCount;
        public final long peakGcPauseMs;

        public MemorySnapshot(long heapUsed, long heapCommitted, long heapMax,
                              long nonHeapUsed, long nonHeapCommitted, long nonHeapMax,
                              double heapUsageRatio, double oldGenUsageRatio,
                              boolean criticalMode, long totalForceGcCount, long peakGcPauseMs) {
            this.heapUsed = heapUsed;
            this.heapCommitted = heapCommitted;
            this.heapMax = heapMax;
            this.nonHeapUsed = nonHeapUsed;
            this.nonHeapCommitted = nonHeapCommitted;
            this.nonHeapMax = nonHeapMax;
            this.heapUsageRatio = heapUsageRatio;
            this.oldGenUsageRatio = oldGenUsageRatio;
            this.criticalMode = criticalMode;
            this.totalForceGcCount = totalForceGcCount;
            this.peakGcPauseMs = peakGcPauseMs;
        }
    }
}
