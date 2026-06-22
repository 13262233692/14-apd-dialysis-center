package com.apd.dialysis.buffer;

import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.model.DialysisDataPoint;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
@RequiredArgsConstructor
public class DialysisDataBuffer {

    private final ApdProperties properties;

    private Deque<DialysisDataPoint> buffer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong totalProduced = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);

    private final List<Consumer<DialysisDataPoint>> flushListeners = new ArrayList<>();
    private ScheduledExecutorService flushExecutor;

    private volatile boolean running = false;

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

        log.info("Dialysis data buffer initialized, capacity={}, flushInterval={}ms",
                properties.getBuffer().getQueueCapacity(), intervalMs);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (flushExecutor != null) {
            flushExecutor.shutdownNow();
        }
        log.info("Dialysis data buffer destroyed. Total produced: {}, dropped: {}",
                totalProduced.get(), totalDropped.get());
    }

    public void produce(DialysisDataPoint point) {
        if (point == null) return;
        totalProduced.incrementAndGet();

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

    private void flushToListeners() {
        if (!running || flushListeners.isEmpty()) return;

        List<DialysisDataPoint> points = drainAll();
        if (points.isEmpty()) return;

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
