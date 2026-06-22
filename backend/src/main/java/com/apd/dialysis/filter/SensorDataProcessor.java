package com.apd.dialysis.filter;

import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.model.WeightSensorReading;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SensorDataProcessor {

    private static final double DIALYSATE_DENSITY_G_PER_ML = 1.015;
    private static final double SPIKE_THRESHOLD_SIGMA = 3.0;

    private final Map<Integer, MedianMeanFilter> filterMap = new ConcurrentHashMap<>();
    private final Map<Integer, Double> lastFilteredWeight = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> lastReadingTime = new ConcurrentHashMap<>();
    private final AtomicLong spikeSuppressedCount = new AtomicLong(0);
    private final AtomicLong totalReadingsCount = new AtomicLong(0);

    private final int defaultWindowSize;

    public SensorDataProcessor() {
        this(9);
    }

    public SensorDataProcessor(int windowSize) {
        this.defaultWindowSize = windowSize;
    }

    public double processReading(WeightSensorReading reading) {
        totalReadingsCount.incrementAndGet();
        int sensorId = reading.getSensorId();

        MedianMeanFilter filter = filterMap.computeIfAbsent(sensorId,
                k -> new MedianMeanFilter(defaultWindowSize));

        double rawWeight = reading.getWeightGrams();
        double filteredWeight = filter.filter(rawWeight);
        lastFilteredWeight.put(sensorId, filteredWeight);
        lastReadingTime.put(sensorId, reading.getTimestamp());

        if (filter.isSpikeDetected(SPIKE_THRESHOLD_SIGMA)) {
            spikeSuppressedCount.incrementAndGet();
            log.trace("Spike detected on sensor {}: raw={}g, filtered={}g", sensorId, rawWeight, filteredWeight);
        }

        return filteredWeight;
    }

    public double getFilteredWeightGrams(int sensorId) {
        return lastFilteredWeight.getOrDefault(sensorId, 0.0);
    }

    public double getFilteredVolumeMl(int sensorId) {
        return getFilteredWeightGrams(sensorId) / DIALYSATE_DENSITY_G_PER_ML;
    }

    public DialysisDataPoint.SensorQuality getSensorQuality(int sensorId) {
        MedianMeanFilter filter = filterMap.get(sensorId);
        if (filter == null || filter.getCurrentSize() < 5) {
            return DialysisDataPoint.SensorQuality.FAIR;
        }

        double stdDev = filter.getStandardDeviation();
        double magnitude = Math.abs(getFilteredWeightGrams(sensorId));
        double noiseRatio = magnitude > 0 ? stdDev / magnitude : stdDev;

        if (noiseRatio < 0.002) return DialysisDataPoint.SensorQuality.EXCELLENT;
        if (noiseRatio < 0.005) return DialysisDataPoint.SensorQuality.GOOD;
        if (noiseRatio < 0.01) return DialysisDataPoint.SensorQuality.FAIR;
        if (noiseRatio < 0.02) return DialysisDataPoint.SensorQuality.POOR;
        return DialysisDataPoint.SensorQuality.NOISY;
    }

    public double getFilterStandardDeviation(int sensorId) {
        MedianMeanFilter filter = filterMap.get(sensorId);
        return filter != null ? filter.getStandardDeviation() : 0.0;
    }

    public long getSpikeSuppressedCount() {
        return spikeSuppressedCount.get();
    }

    public long getTotalReadingsCount() {
        return totalReadingsCount.get();
    }

    public double getSpikeSuppressionRate() {
        long total = totalReadingsCount.get();
        return total > 0 ? (double) spikeSuppressedCount.get() / total : 0.0;
    }

    public void resetSensor(int sensorId) {
        MedianMeanFilter filter = filterMap.remove(sensorId);
        if (filter != null) filter.reset();
        lastFilteredWeight.remove(sensorId);
        lastReadingTime.remove(sensorId);
    }

    public void resetAll() {
        filterMap.values().forEach(MedianMeanFilter::reset);
        filterMap.clear();
        lastFilteredWeight.clear();
        lastReadingTime.clear();
        spikeSuppressedCount.set(0);
        totalReadingsCount.set(0);
    }
}
