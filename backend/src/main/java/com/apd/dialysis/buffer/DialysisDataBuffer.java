package com.apd.dialysis.buffer;

import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.model.DialysisDataPoint;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

@Slf4j
@Component
public class DialysisDataBuffer {

    private static final double HEAP_DANGER_RATIO = 0.85;
    private static final double HEAP_WARN_RATIO = 0.70;
    private static final int EMERGENCY_DROP_BATCH = 500;

    private final ApdProperties properties;
    private final MemoryMXBean memoryMXBean;

    private Deque<DialysisDataPoint> buffer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong totalProduced = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalDroppedByHeapPressure = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);

    private final List<Consumer<DialysisDataPoint>> flushListeners = new ArrayList<>();
    private final List<Consumer<List<DialysisDataPoint>>> batchFlushListeners = new ArrayList<>();
    private ScheduledExecutorService flushExecutor;

    private volatile boolean running = false;
    private volatile boolean heapEmergency = false;
    private volatile double lastHeapUsageRatio = 0.0;

    public DialysisDataBuffer(ApdProperties properties) {
        this.properties = properties;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
    }

    @PostConstruct
    public void init() {
        buffer = new ConcurrentLinkedDeque<>();
        running = true;

        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dialysis-buffer-flush");
            t.setDaemon(true);
            return t;
        });

        int intervalMs = properties.getBuffer().getFlushIntervalMs();
        flushExecutor.scheduleAtFixedRate(this::flushToListeners, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        log.info("Dialysis data buffer initialized, capacity={}, flushInterval={}ms, heapDanger={}%, heapWarn={}%",
                properties.getBuffer().getQueueCapacity(), intervalMs,
                (int) (HEAP_DANGER_RATIO * 100), (int) (HEAP_WARN_RATIO * 100));
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (flushExecutor != null) {
            flushExecutor.shutdownNow();
        }
        log.info("Dialysis data buffer destroyed. Produced={}, Flushed={}, Dropped={}, DroppedByHeapPressure={}, FinalSize={}",
                totalProduced.get(), totalFlushed.get(), totalDropped.get(),
                totalDroppedByHeapPressure.get(), size());
    }

    public void produce(DialysisDataPoint point) {
        if (point == null || !running) return;
        totalProduced.incrementAndGet();

        checkHeapPressureAndDrop();

        int capacity = properties.getBuffer().getQueueCapacity();
        lock.writeLock().lock();
        try {
            while (buffer.size() >= capacity) {
                DialysisDataPoint dropped = buffer.pollFirst();
                if (dropped != null) {
                    totalDropped.incrementAndGet();
                }
            }
            buffer.offerLast(point);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void checkHeapPressureAndDrop() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        if (max <= 0) return;

        double ratio = (double) used / max;
        lastHeapUsageRatio = ratio;

        if (ratio >= HEAP_DANGER_RATIO) {
            if (!heapEmergency) {
                heapEmergency = true;
                log.error("HEAP EMERGENCY ACTIVATED: used={}MB, max={}MB, ratio={}%. Forcibly dropping oldest buffer data.",
                        used / 1024 / 1024, max / 1024 / 1024, (int) (ratio * 100));
            }
            emergencyDropOldData();
        } else if (ratio >= HEAP_WARN_RATIO) {
            if (heapEmergency) {
                log.warn("HEAP WARNING: used={}MB, max={}MB, ratio={}%. Buffer size={}",
                        used / 1024 / 1024, max / 1024 / 1024, (int) (ratio * 100), size());
            }
        } else {
            if (heapEmergency) {
                heapEmergency = false;
                log.info("HEAP pressure relieved: ratio={}%, emergency deactivated", (int) (ratio * 100));
            }
        }
    }

    private void emergencyDropOldData() {
        lock.writeLock().lock();
        try {
            int dropped = 0;
            for (int i = 0; i < EMERGENCY_DROP_BATCH && !buffer.isEmpty(); i++) {
                if (buffer.pollFirst() != null) {
                    dropped++;
                }
            }
            if (dropped > 0) {
                totalDroppedByHeapPressure.addAndGet(dropped);
                totalDropped.addAndGet(dropped);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<DialysisDataPoint> drainAll() {
        List<DialysisDataPoint> result = new ArrayList<>();
        lock.writeLock().lock();
        try {
            DialysisDataPoint point;
            while ((point = buffer.pollFirst()) != null) {
                result.add(point);
            }
        } finally {
            lock.writeLock().unlock();
        }
        return result;
    }

    public List<DialysisDataPoint> peekLast(int count) {
        List<DialysisDataPoint> result = new ArrayList<>(count);
        lock.readLock().lock();
        try {
            int i = 0;
            for (DialysisDataPoint point : buffer) {
                if (i >= count) break;
                result.add(point);
                i++;
            }
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    public DialysisDataPoint peekLatest() {
        lock.readLock().lock();
        try {
            return buffer.peekLast();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return buffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getTotalProduced() { return totalProduced.get(); }
    public long getTotalDropped() { return totalDropped.get(); }
    public long getTotalDroppedByHeapPressure() { return totalDroppedByHeapPressure.get(); }
    public long getTotalFlushed() { return totalFlushed.get(); }
    public double getLastHeapUsageRatio() { return lastHeapUsageRatio; }
    public boolean isHeapEmergency() { return heapEmergency; }

    public double getDropRate() {
        long produced = totalProduced.get();
        return produced > 0 ? (double) totalDropped.get() / produced : 0.0;
    }

    public void addFlushListener(Consumer<DialysisDataPoint> listener) {
        flushListeners.add(listener);
    }

    public void removeFlushListener(Consumer<DialysisDataPoint> listener) {
        flushListeners.remove(listener);
    }

    public void addBatchFlushListener(Consumer<List<DialysisDataPoint>> listener) {
        batchFlushListeners.add(listener);
    }

    public void removeBatchFlushListener(Consumer<List<DialysisDataPoint>> listener) {
        batchFlushListeners.remove(listener);
    }

    private void flushToListeners() {
        if (!running) return;
        boolean hasListeners = !flushListeners.isEmpty() || !batchFlushListeners.isEmpty();
        if (!hasListeners) return;

        List<DialysisDataPoint> points = drainAll();
        if (points.isEmpty()) return;

        totalFlushed.addAndGet(points.size());

        if (!batchFlushListeners.isEmpty()) {
            for (Consumer<List<DialysisDataPoint>> batchListener : batchFlushListeners) {
                try {
                    batchListener.accept(points);
                } catch (Exception e) {
                    log.warn("Batch flush listener error", e);
                }
            }
        }

        if (!flushListeners.isEmpty()) {
            for (Consumer<DialysisDataPoint> listener : flushListeners) {
                for (DialysisDataPoint point : points) {
                    try {
                        listener.accept(point);
                    } catch (Exception e) {
                        log.warn("Flush listener error", e);
                    }
                }
            }
        }
    }
}
