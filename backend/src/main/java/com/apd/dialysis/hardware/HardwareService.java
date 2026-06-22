package com.apd.dialysis.hardware;

import com.apd.dialysis.model.DeviceCommand;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.WeightSensorReading;

import java.util.List;
import java.util.Optional;

public interface HardwareService {

    void start();

    void stop();

    boolean isRunning();

    List<DeviceStatus> getAllDeviceStatuses();

    Optional<DeviceStatus> getDeviceStatus(int nodeId);

    List<WeightSensorReading> getLatestSensorReadings();

    void sendCommand(DeviceCommand command);

    void registerSensorReadingCallback(java.util.function.Consumer<WeightSensorReading> callback);

    void registerDeviceStatusCallback(java.util.function.Consumer<DeviceStatus> callback);
}
