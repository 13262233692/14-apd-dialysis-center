package com.apd.dialysis.hardware;

import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.model.DeviceCommand;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.model.WeightSensorReading;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "apd.hardware.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class SimulatedHardwareService implements HardwareService {

    private final ApdProperties properties;

    private final ConcurrentHashMap<Integer, DeviceStatus> deviceStatusMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, WeightSensorReading> latestReadings = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<WeightSensorReading>> sensorCallbacks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DeviceStatus>> deviceStatusCallbacks = new CopyOnWriteArrayList<>();

    private final Random random = new Random();
    private final AtomicLong cycleStartTime = new AtomicLong(0);
    private volatile DialysisDataPoint.DialysisPhase currentPhase = DialysisDataPoint.DialysisPhase.IDLE;
    private volatile double currentAbdominalVolumeMl = 0.0;
    private volatile double totalInflowMl = 0.0;
    private volatile double totalOutflowMl = 0.0;
    private volatile double targetInflowMl = 2000.0;
    private volatile double targetDwellMinutes = 0.5;
    private volatile double bagWeightSensor1 = 5000.0;
    private volatile double bagWeightSensor2 = 0.0;
    private volatile double heaterTemperature = 25.0;
    private volatile double targetHeaterTemperature = 37.0;
    private volatile double pumpFlowRate = 100.0;
    private volatile boolean pumpRunning = false;
    private volatile boolean heaterRunning = false;

    private volatile double baseTransmittance = 98.0;
    private volatile double baseAbsorbance420 = 0.04;
    private volatile double baseAbsorbance540 = 0.03;
    private volatile double baseAbsorbance660 = 0.02;
    private volatile double baseAbsorbance720 = 0.02;
    private volatile boolean peritonitisTriggered = false;
    private volatile long peritonitisTriggerAt = 0L;
    private volatile double drainStartTime = 0.0;
    private volatile double extraDrainDelayFactor = 1.0;

    private volatile boolean running = false;

    @PostConstruct
    @Override
    public void start() {
        running = true;
        initializeDevices();
        log.info("Simulated hardware service started with realistic dialysis cycle");
    }

    @PreDestroy
    @Override
    public void stop() {
        running = false;
        pumpRunning = false;
        heaterRunning = false;
        log.info("Simulated hardware service stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void initializeDevices() {
        deviceStatusMap.put(1, DeviceStatus.builder()
                .timestamp(Instant.now())
                .nodeId(1)
                .deviceType(DeviceStatus.DeviceType.PERISTALTIC_PUMP)
                .state(DeviceStatus.OperationalState.STANDBY)
                .currentValue(0)
                .targetValue(100)
                .online(true)
                .build());

        deviceStatusMap.put(2, DeviceStatus.builder()
                .timestamp(Instant.now())
                .nodeId(2)
                .deviceType(DeviceStatus.DeviceType.HEATING_BAG)
                .state(DeviceStatus.OperationalState.STANDBY)
                .currentValue(25.0)
                .targetValue(37.0)
                .online(true)
                .build());

        deviceStatusMap.put(3, DeviceStatus.builder()
                .timestamp(Instant.now())
                .nodeId(3)
                .deviceType(DeviceStatus.DeviceType.WEIGHT_SENSOR)
                .state(DeviceStatus.OperationalState.RUNNING)
                .currentValue(5000)
                .targetValue(0)
                .online(true)
                .build());

        deviceStatusMap.put(4, DeviceStatus.builder()
                .timestamp(Instant.now())
                .nodeId(4)
                .deviceType(DeviceStatus.DeviceType.WEIGHT_SENSOR)
                .state(DeviceStatus.OperationalState.RUNNING)
                .currentValue(0)
                .targetValue(0)
                .online(true)
                .build());
    }

    @Override
    public List<DeviceStatus> getAllDeviceStatuses() {
        return new ArrayList<>(deviceStatusMap.values());
    }

    @Override
    public Optional<DeviceStatus> getDeviceStatus(int nodeId) {
        return Optional.ofNullable(deviceStatusMap.get(nodeId));
    }

    @Override
    public List<WeightSensorReading> getLatestSensorReadings() {
        return new ArrayList<>(latestReadings.values());
    }

    @Override
    public void sendCommand(DeviceCommand command) {
        log.info("Simulated command received: {} node={} value={}", command.getType(), command.getNodeId(), command.getTargetValue());
        DeviceCommand.CommandType type = command.getType();
        if (type == DeviceCommand.CommandType.START_DIALYSIS) {
            startDialysisCycle();
        } else if (type == DeviceCommand.CommandType.STOP_DIALYSIS) {
            stopDialysisCycle();
        } else if (type == DeviceCommand.CommandType.EMERGENCY_STOP) {
            emergencyStop();
        } else if (type == DeviceCommand.CommandType.START_PUMP) {
            pumpRunning = true;
            updatePumpStatus();
        } else if (type == DeviceCommand.CommandType.STOP_PUMP) {
            pumpRunning = false;
            updatePumpStatus();
        } else if (type == DeviceCommand.CommandType.SET_FLOW_RATE) {
            pumpFlowRate = command.getTargetValue();
            updatePumpStatus();
        } else if (type == DeviceCommand.CommandType.START_HEATING) {
            heaterRunning = true;
            updateHeaterStatus();
        } else if (type == DeviceCommand.CommandType.STOP_HEATING) {
            heaterRunning = false;
            updateHeaterStatus();
        } else if (type == DeviceCommand.CommandType.SET_TEMPERATURE) {
            targetHeaterTemperature = command.getTargetValue();
            updateHeaterStatus();
        } else if (type == DeviceCommand.CommandType.TARE_SENSOR) {
            bagWeightSensor1 = 0;
            bagWeightSensor2 = 0;
        } else {
            log.debug("Unhandled simulated command: {}", command.getType());
        }
    }

    private void startDialysisCycle() {
        cycleStartTime.set(System.currentTimeMillis());
        currentPhase = DialysisDataPoint.DialysisPhase.FILL;
        totalInflowMl = 0.0;
        totalOutflowMl = 0.0;
        currentAbdominalVolumeMl = 0.0;
        pumpRunning = true;
        heaterRunning = true;
        baseTransmittance = 98.0;
        baseAbsorbance420 = 0.04;
        baseAbsorbance540 = 0.03;
        baseAbsorbance660 = 0.02;
        baseAbsorbance720 = 0.02;
        peritonitisTriggered = false;
        peritonitisTriggerAt = System.currentTimeMillis() + 90000L;
        extraDrainDelayFactor = 1.0;
        drainStartTime = 0.0;
        updatePumpStatus();
        updateHeaterStatus();
        log.info("Dialysis cycle started - FILL phase. Peritonitis will trigger in 90s for demo.");
    }

    private void stopDialysisCycle() {
        currentPhase = DialysisDataPoint.DialysisPhase.COMPLETE;
        pumpRunning = false;
        heaterRunning = false;
        updatePumpStatus();
        updateHeaterStatus();
        log.info("Dialysis cycle stopped");
    }

    private void emergencyStop() {
        currentPhase = DialysisDataPoint.DialysisPhase.IDLE;
        pumpRunning = false;
        heaterRunning = false;
        updatePumpStatus();
        updateHeaterStatus();
        log.warn("EMERGENCY STOP activated");
    }

    private void updatePumpStatus() {
        DeviceStatus status = deviceStatusMap.get(1);
        if (status != null) {
            status.setTimestamp(Instant.now());
            status.setState(pumpRunning ? DeviceStatus.OperationalState.RUNNING : DeviceStatus.OperationalState.STANDBY);
            status.setCurrentValue(pumpRunning ? pumpFlowRate : 0);
            status.setTargetValue(pumpFlowRate);
            for (Consumer<DeviceStatus> cb : deviceStatusCallbacks) {
                try { cb.accept(status); } catch (Exception e) { log.warn("Callback error", e); }
            }
        }
    }

    private void updateHeaterStatus() {
        DeviceStatus status = deviceStatusMap.get(2);
        if (status != null) {
            status.setTimestamp(Instant.now());
            status.setState(heaterRunning ? DeviceStatus.OperationalState.RUNNING : DeviceStatus.OperationalState.STANDBY);
            status.setTargetValue(targetHeaterTemperature);
            for (Consumer<DeviceStatus> cb : deviceStatusCallbacks) {
                try { cb.accept(status); } catch (Exception e) { log.warn("Callback error", e); }
            }
        }
    }

    @Scheduled(fixedRateString = "${apd.hardware.sensor.sample-interval-ms:100}")
    public void simulateSensorReadings() {
        if (!running) return;
        advanceDialysisCycle();
        generateWeightSensorReadings();
        updateHeaterTemperature();
    }

    private void advanceDialysisCycle() {
        long elapsedMs = System.currentTimeMillis() - cycleStartTime.get();
        double elapsedSec = elapsedMs / 1000.0;
        double inflowRateMlPerSec = pumpFlowRate / 60.0;

        long now = System.currentTimeMillis();
        if (!peritonitisTriggered && now >= peritonitisTriggerAt && peritonitisTriggerAt > 0) {
            peritonitisTriggered = true;
            extraDrainDelayFactor = 1.8;
            log.warn("!!! SIMULATED PERITONITIS TRIGGERED: transmittance cliff-drop + drain time extended by 80%");
        }

        double outflowRateMlPerSec;
        if (currentPhase == DialysisDataPoint.DialysisPhase.DRAIN) {
            double factor = peritonitisTriggered ? 0.45 : 0.9;
            outflowRateMlPerSec = (pumpFlowRate * factor) / 60.0;
        } else {
            outflowRateMlPerSec = (pumpFlowRate * 0.9) / 60.0;
        }

        if (peritonitisTriggered) {
            baseTransmittance = 45.0 + random.nextGaussian() * 5;
            baseAbsorbance420 = 0.62 + random.nextGaussian() * 0.05;
            baseAbsorbance540 = 0.48 + random.nextGaussian() * 0.04;
            baseAbsorbance660 = 0.35 + random.nextGaussian() * 0.03;
            baseAbsorbance720 = 0.28 + random.nextGaussian() * 0.03;
        } else {
            baseTransmittance = 96.0 + random.nextGaussian() * 2;
            baseAbsorbance420 = 0.04 + Math.abs(random.nextGaussian() * 0.01);
            baseAbsorbance540 = 0.03 + Math.abs(random.nextGaussian() * 0.008);
            baseAbsorbance660 = 0.02 + Math.abs(random.nextGaussian() * 0.005);
            baseAbsorbance720 = 0.02 + Math.abs(random.nextGaussian() * 0.005);
        }

        if (currentPhase == DialysisDataPoint.DialysisPhase.FILL) {
            double amount = inflowRateMlPerSec * 0.1;
            if (totalInflowMl + amount >= targetInflowMl) {
                currentAbdominalVolumeMl += (targetInflowMl - totalInflowMl);
                totalInflowMl = targetInflowMl;
                currentPhase = DialysisDataPoint.DialysisPhase.DWELL;
                pumpRunning = false;
                updatePumpStatus();
                cycleStartTime.set(System.currentTimeMillis());
                log.info("FILL complete, entering DWELL phase. Total inflow: {}ml", String.format("%.1f", totalInflowMl));
            } else {
                currentAbdominalVolumeMl += amount;
                totalInflowMl += amount;
            }
        } else if (currentPhase == DialysisDataPoint.DialysisPhase.DWELL) {
            if (elapsedSec / 60.0 >= targetDwellMinutes) {
                currentPhase = DialysisDataPoint.DialysisPhase.DRAIN;
                pumpRunning = true;
                drainStartTime = System.currentTimeMillis() / 1000.0;
                updatePumpStatus();
                cycleStartTime.set(System.currentTimeMillis());
                log.info("DWELL complete, entering DRAIN phase");
            }
        } else if (currentPhase == DialysisDataPoint.DialysisPhase.DRAIN) {
            double amount = outflowRateMlPerSec * 0.1;
            if (currentAbdominalVolumeMl - amount <= 0) {
                totalOutflowMl += currentAbdominalVolumeMl;
                currentAbdominalVolumeMl = 0;
                currentPhase = DialysisDataPoint.DialysisPhase.COMPLETE;
                pumpRunning = false;
                updatePumpStatus();
                log.info("DRAIN complete. Net UF: {}ml", String.format("%.1f", totalOutflowMl - totalInflowMl));
            } else {
                currentAbdominalVolumeMl -= amount;
                totalOutflowMl += amount;
            }
        }

        bagWeightSensor1 = 5000.0 - totalInflowMl;
        bagWeightSensor2 = totalOutflowMl;
    }

    public double getCurrentTransmittance() { return baseTransmittance; }
    public double getCurrentAbsorbance420() { return baseAbsorbance420; }
    public double getCurrentAbsorbance540() { return baseAbsorbance540; }
    public double getCurrentAbsorbance660() { return baseAbsorbance660; }
    public double getCurrentAbsorbance720() { return baseAbsorbance720; }
    public String getCurrentSpectralHex() {
        int v420 = Math.max(0, Math.min(255, (int)(baseAbsorbance420 * 255)));
        int v540 = Math.max(0, Math.min(255, (int)(baseAbsorbance540 * 255)));
        int v660 = Math.max(0, Math.min(255, (int)(baseAbsorbance660 * 255)));
        int v720 = Math.max(0, Math.min(255, (int)(baseAbsorbance720 * 255)));
        return String.format("%02X%02X%02X%02X", v420, v540, v660, v720);
    }
    public double getDrainElapsedMinutes() {
        if (currentPhase != DialysisDataPoint.DialysisPhase.DRAIN) return 0.0;
        return Math.max(0.0, (System.currentTimeMillis() / 1000.0 - drainStartTime) / 60.0);
    }
    public double getDrainTargetMinutes() {
        double base = targetInflowMl / 90.0;
        return peritonitisTriggered ? base * extraDrainDelayFactor : base;
    }
    public boolean isPeritonitisTriggered() { return peritonitisTriggered; }

    private void generateWeightSensorReadings() {
        double noise1 = 0;
        double noise2 = 0;

        if (properties.getHardware().getSimulator().isPatientMovementNoise()) {
            double movementProbability = 0.08;
            if (random.nextDouble() < movementProbability) {
                double spikeMagnitude = 30 + random.nextDouble() * 150;
                int direction = random.nextBoolean() ? 1 : -1;
                noise1 = direction * spikeMagnitude;
                noise2 = direction * spikeMagnitude * 0.8;
            }
            noise1 += (random.nextDouble() - 0.5) * 2.0;
            noise2 += (random.nextDouble() - 0.5) * 2.0;
        }

        double w1 = bagWeightSensor1 + noise1;
        double w2 = bagWeightSensor2 + noise2;

        publishSensorReading(3, w1, heaterTemperature);
        publishSensorReading(4, w2, heaterTemperature);
    }

    private void publishSensorReading(int sensorId, double weightGrams, double temperature) {
        WeightSensorReading reading = WeightSensorReading.builder()
                .timestamp(Instant.now())
                .sensorId(sensorId)
                .weightGrams(Math.round(weightGrams * 10) / 10.0)
                .temperatureC(Math.round(temperature * 100) / 100.0)
                .isValid(true)
                .rawHexData("SIMULATED")
                .build();
        latestReadings.put(sensorId, reading);
        for (Consumer<WeightSensorReading> cb : sensorCallbacks) {
            try { cb.accept(reading); } catch (Exception e) { log.warn("Sensor callback error", e); }
        }
    }

    private void updateHeaterTemperature() {
        if (heaterRunning) {
            double diff = targetHeaterTemperature - heaterTemperature;
            heaterTemperature += diff * 0.05 + (random.nextDouble() - 0.5) * 0.1;
            heaterTemperature = Math.min(heaterTemperature, targetHeaterTemperature + 0.5);
        } else {
            heaterTemperature += (25.0 - heaterTemperature) * 0.02;
        }
        DeviceStatus heater = deviceStatusMap.get(2);
        if (heater != null) {
            heater.setCurrentValue(Math.round(heaterTemperature * 10) / 10.0);
            heater.setTimestamp(Instant.now());
        }
    }

    @Override
    public void registerSensorReadingCallback(Consumer<WeightSensorReading> callback) {
        sensorCallbacks.add(callback);
    }

    @Override
    public void registerDeviceStatusCallback(Consumer<DeviceStatus> callback) {
        deviceStatusCallbacks.add(callback);
    }

    public DialysisDataPoint.DialysisPhase getCurrentPhase() { return currentPhase; }
    public double getCurrentAbdominalVolumeMl() { return currentAbdominalVolumeMl; }
    public double getTotalInflowMl() { return totalInflowMl; }
    public double getTotalOutflowMl() { return totalOutflowMl; }
}
