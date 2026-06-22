package com.apd.dialysis.buffer;

import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.WeightSensorReading;
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
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class HardwareEventBus {

    private final ApdProperties properties;

    private final Deque<WeightSensorReading> sensorQueue = new ConcurrentLinkedDeque<>();
    private final Deque<DeviceStatus> deviceStatusQueue = new ConcurrentLinkedDeque<>();

    private final List<Consumer<WeightSensorReading>> sensorListeners = new ArrayList<>();
    private final List<Consumer<DeviceStatus>> deviceStatusListeners = new ArrayList<>();

    private ScheduledExecutorService dispatcher;
    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        running = true;
        dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hardware-event-dispatcher");
            t.setDaemon(true);
            return t;
        });
        dispatcher.scheduleAtFixedRate(this::dispatch, 20, 20, TimeUnit.MILLISECONDS);
        log.info("Hardware event bus initialized");
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (dispatcher != null) dispatcher.shutdownNow();
    }

    public void publishSensorReading(WeightSensorReading reading) {
        if (reading == null) return;
        sensorQueue.offer(reading);
        while (sensorQueue.size() > properties.getBuffer().getQueueCapacity()) {
            sensorQueue.poll();
        }
    }

    public void publishDeviceStatus(DeviceStatus status) {
        if (status == null) return;
        deviceStatusQueue.offer(status);
        while (deviceStatusQueue.size() > 1000) {
            deviceStatusQueue.poll();
        }
    }

    public void registerSensorListener(Consumer<WeightSensorReading> listener) {
        sensorListeners.add(listener);
    }

    public void unregisterSensorListener(Consumer<WeightSensorReading> listener) {
        sensorListeners.remove(listener);
    }

    public void registerDeviceStatusListener(Consumer<DeviceStatus> listener) {
        deviceStatusListeners.add(listener);
    }

    public void unregisterDeviceStatusListener(Consumer<DeviceStatus> listener) {
        deviceStatusListeners.remove(listener);
    }

    private void dispatch() {
        if (!running) return;
        dispatchSensorReadings();
        dispatchDeviceStatuses();
    }

    private void dispatchSensorReadings() {
        if (sensorListeners.isEmpty()) {
            sensorQueue.clear();
            return;
        }
        WeightSensorReading reading;
        while ((reading = sensorQueue.poll()) != null) {
            for (Consumer<WeightSensorReading> listener : sensorListeners) {
                try {
                    listener.accept(reading);
                } catch (Exception e) {
                    log.warn("Sensor listener dispatch error", e);
                }
            }
        }
    }

    private void dispatchDeviceStatuses() {
        if (deviceStatusListeners.isEmpty()) {
            deviceStatusQueue.clear();
            return;
        }
        DeviceStatus status;
        while ((status = deviceStatusQueue.poll()) != null) {
            for (Consumer<DeviceStatus> listener : deviceStatusListeners) {
                try {
                    listener.accept(status);
                } catch (Exception e) {
                    log.warn("Device status listener dispatch error", e);
                }
            }
        }
    }
}
