package com.apd.dialysis.filter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

@Slf4j
public class MedianMeanFilter {

    private final int windowSize;
    private final Deque<Double> window;

    public MedianMeanFilter(int windowSize) {
        if (windowSize < 5 || windowSize % 2 == 0) {
            throw new IllegalArgumentException("Window size must be an odd number >= 5");
        }
        this.windowSize = windowSize;
        this.window = new ArrayDeque<>(windowSize);
    }

    public synchronized double filter(double newValue) {
        window.addLast(newValue);

        while (window.size() > windowSize) {
            window.removeFirst();
        }

        if (window.size() < 3) {
            return newValue;
        }

        return calculateMedianMean();
    }

    private double calculateMedianMean() {
        List<Double> sorted = new ArrayList<>(window);
        Collections.sort(sorted);

        int trimCount = sorted.size() >= 9 ? 2 : 1;

        double sum = 0;
        int count = 0;
        for (int i = trimCount; i < sorted.size() - trimCount; i++) {
            sum += sorted.get(i);
            count++;
        }

        return count > 0 ? sum / count : sorted.get(sorted.size() / 2);
    }

    public synchronized double getLatestMedian() {
        if (window.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(window);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    public synchronized double getStandardDeviation() {
        if (window.size() < 2) return 0.0;
        double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = window.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
        return Math.sqrt(variance);
    }

    public synchronized boolean isSpikeDetected(double thresholdSigma) {
        if (window.size() < windowSize) return false;
        double stdDev = getStandardDeviation();
        if (stdDev < 0.001) return false;
        double median = getLatestMedian();
        double latest = window.getLast();
        return Math.abs(latest - median) > thresholdSigma * stdDev;
    }

    public synchronized void reset() {
        window.clear();
    }

    public int getWindowSize() {
        return windowSize;
    }

    public synchronized int getCurrentSize() {
        return window.size();
    }
}
