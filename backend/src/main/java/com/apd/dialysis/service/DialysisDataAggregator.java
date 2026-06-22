package com.apd.dialysis.service;

import com.apd.dialysis.filter.SensorDataProcessor;
import com.apd.dialysis.hardware.HardwareService;
import com.apd.dialysis.hardware.SimulatedHardwareService;
import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.model.WeightSensorReading;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DialysisDataAggregator {

    private final HardwareService hardwareService;
    private final SensorDataProcessor sensorDataProcessor;

    private static final int INFLOW_BAG_SENSOR_ID = 3;
    private static final int OUTFLOW_BAG_SENSOR_ID = 4;

    private final AtomicReference<Double> taredInflowWeight = new AtomicReference<>(0.0);
    private final AtomicReference<Double> taredOutflowWeight = new AtomicReference<>(0.0);
    private final AtomicReference<Instant> lastTimestamp = new AtomicReference<>(Instant.now());
    private final AtomicReference<Double> lastInflowVolume = new AtomicReference<>(0.0);
    private final AtomicReference<Double> lastOutflowVolume = new AtomicReference<>(0.0);

    private final CopyOnWriteArrayList<Consumer<DialysisDataPoint>> dataPointListeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        hardwareService.registerSensorReadingCallback(this::onSensorReading);
    }

    private void onSensorReading(WeightSensorReading reading) {
        if (!reading.isValid()) return;
        sensorDataProcessor.processReading(reading);
    }

    public DialysisDataPoint buildCurrentDataPoint() {
        Instant now = Instant.now();

        double filteredInflowGrams = sensorDataProcessor.getFilteredWeightGrams(INFLOW_BAG_SENSOR_ID);
        double filteredOutflowGrams = sensorDataProcessor.getFilteredWeightGrams(OUTFLOW_BAG_SENSOR_ID);

        double inflowVolumeMl = Math.max(0, (taredInflowWeight.get() - filteredInflowGrams) / 1.015);
        double outflowVolumeMl = Math.max(0, (filteredOutflowGrams - taredOutflowWeight.get()) / 1.015);

        Instant prevTime = lastTimestamp.get();
        double elapsedMinutes = Math.max(0.0001, java.time.Duration.between(prevTime, now).toMillis() / 60000.0);

        double prevInflow = lastInflowVolume.get();
        double prevOutflow = lastOutflowVolume.get();
        double inflowRate = Math.max(0, (inflowVolumeMl - prevInflow) / elapsedMinutes);
        double outflowRate = Math.max(0, (outflowVolumeMl - prevOutflow) / elapsedMinutes);

        lastTimestamp.set(now);
        lastInflowVolume.set(inflowVolumeMl);
        lastOutflowVolume.set(outflowVolumeMl);

        double netUf = outflowVolumeMl - inflowVolumeMl;

        DialysisDataPoint.DialysisPhase phase = DialysisDataPoint.DialysisPhase.IDLE;
        if (hardwareService instanceof SimulatedHardwareService) {
            SimulatedHardwareService sim = (SimulatedHardwareService) hardwareService;
            phase = sim.getCurrentPhase();
        }

        double temp = hardwareService.getDeviceStatus(2)
                .isPresent() ? hardwareService.getDeviceStatus(2).get().getCurrentValue() : 37.0;

        DialysisDataPoint.SensorQuality quality = aggregateQuality();

        double abdominalVolume;
        if (phase == DialysisDataPoint.DialysisPhase.DWELL
                || phase == DialysisDataPoint.DialysisPhase.FILL
                || phase == DialysisDataPoint.DialysisPhase.DRAIN) {
            abdominalVolume = Math.max(0, inflowVolumeMl - outflowVolumeMl);
        } else {
            abdominalVolume = 0.0;
        }

        if (hardwareService instanceof SimulatedHardwareService) {
            SimulatedHardwareService sim = (SimulatedHardwareService) hardwareService;
            abdominalVolume = sim.getCurrentAbdominalVolumeMl();
            inflowVolumeMl = sim.getTotalInflowMl();
            outflowVolumeMl = sim.getTotalOutflowMl();
            netUf = outflowVolumeMl - inflowVolumeMl;
        }

        return DialysisDataPoint.builder()
                .timestamp(now)
                .inflowVolumeMl(Math.round(inflowVolumeMl * 10) / 10.0)
                .outflowVolumeMl(Math.round(outflowVolumeMl * 10) / 10.0)
                .netUltrafiltrationMl(Math.round(netUf * 10) / 10.0)
                .inflowFlowRateMlPerMin(Math.round(inflowRate * 10) / 10.0)
                .outflowFlowRateMlPerMin(Math.round(outflowRate * 10) / 10.0)
                .abdominalVolumeMl(Math.round(abdominalVolume * 10) / 10.0)
                .dialysateTemperatureC(Math.round(temp * 10) / 10.0)
                .phase(phase)
                .sensorQuality(quality)
                .build();
    }

    private DialysisDataPoint.SensorQuality aggregateQuality() {
        DialysisDataPoint.SensorQuality q1 = sensorDataProcessor.getSensorQuality(INFLOW_BAG_SENSOR_ID);
        DialysisDataPoint.SensorQuality q2 = sensorDataProcessor.getSensorQuality(OUTFLOW_BAG_SENSOR_ID);
        return q1.ordinal() <= q2.ordinal() ? q1 : q2;
    }

    public void tareSensors() {
        taredInflowWeight.set(sensorDataProcessor.getFilteredWeightGrams(INFLOW_BAG_SENSOR_ID));
        taredOutflowWeight.set(sensorDataProcessor.getFilteredWeightGrams(OUTFLOW_BAG_SENSOR_ID));
        lastInflowVolume.set(0.0);
        lastOutflowVolume.set(0.0);
        log.info("Sensors tared: inflow={}g, outflow={}g", taredInflowWeight.get(), taredOutflowWeight.get());
    }

    public void addDataPointListener(Consumer<DialysisDataPoint> listener) {
        dataPointListeners.add(listener);
    }

    public void removeDataPointListener(Consumer<DialysisDataPoint> listener) {
        dataPointListeners.remove(listener);
    }

    public void publishDataPoint() {
        DialysisDataPoint point = buildCurrentDataPoint();
        for (Consumer<DialysisDataPoint> listener : dataPointListeners) {
            try {
                listener.accept(point);
            } catch (Exception e) {
                log.warn("Data point listener error", e);
            }
        }
    }

    public double getSpikeSuppressionRate() {
        return sensorDataProcessor.getSpikeSuppressionRate();
    }

    public long getSpikeSuppressedCount() {
        return sensorDataProcessor.getSpikeSuppressedCount();
    }
}
