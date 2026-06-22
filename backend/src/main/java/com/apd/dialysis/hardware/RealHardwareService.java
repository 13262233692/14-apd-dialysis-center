package com.apd.dialysis.hardware;

import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.model.DeviceCommand;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.WeightSensorReading;
import com.apd.dialysis.protocol.canopen.CanOpenProtocolManager;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "apd.hardware.simulator.enabled", havingValue = "false", matchIfMissing = false)
public class RealHardwareService implements HardwareService {

    private final ApdProperties properties;
    private final CanOpenProtocolManager protocolManager;

    private final ConcurrentHashMap<Integer, DeviceStatus> deviceStatusMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, WeightSensorReading> latestReadings = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<WeightSensorReading>> sensorCallbacks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DeviceStatus>> deviceStatusCallbacks = new CopyOnWriteArrayList<>();

    private volatile boolean running = false;

    @PostConstruct
    @Override
    public void start() {
        running = true;
        protocolManager.addSensorListener(reading -> {
            latestReadings.put(reading.getSensorId(), reading);
            for (Consumer<WeightSensorReading> cb : sensorCallbacks) {
                try { cb.accept(reading); } catch (Exception e) { log.warn("Sensor callback error", e); }
            }
        });
        protocolManager.addDeviceStatusListener(status -> {
            deviceStatusMap.put(status.getNodeId(), status);
            for (Consumer<DeviceStatus> cb : deviceStatusCallbacks) {
                try { cb.accept(status); } catch (Exception e) { log.warn("Device status callback error", e); }
            }
        });
        log.info("Real hardware service started");
    }

    @PreDestroy
    @Override
    public void stop() {
        running = false;
        log.info("Real hardware service stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
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
        protocolManager.sendCommand(command);
    }

    @Override
    public void registerSensorReadingCallback(Consumer<WeightSensorReading> callback) {
        sensorCallbacks.add(callback);
    }

    @Override
    public void registerDeviceStatusCallback(Consumer<DeviceStatus> callback) {
        deviceStatusCallbacks.add(callback);
    }
}
