package com.apd.dialysis.protocol.canopen;

import com.apd.dialysis.model.WeightSensorReading;
import com.apd.dialysis.model.DeviceStatus;

import java.nio.ByteBuffer;
import java.time.Instant;

public class PdoParser {

    public static final int WEIGHT_SENSOR_PDO1 = 0x180;
    public static final int WEIGHT_SENSOR_PDO2 = 0x280;
    public static final int PUMP_PDO = 0x380;
    public static final int HEATER_PDO = 0x480;

    public static WeightSensorReading parseWeightSensorPdo(CanOpenFrame frame) {
        if (frame.getData() == null || frame.getData().length < 8) {
            return null;
        }

        byte[] data = frame.getData();
        ByteBuffer buf = ByteBuffer.wrap(data);

        int rawWeight = buf.getInt(0);
        short rawTemp = buf.getShort(4);
        byte status = buf.get(6);
        byte sensorId = buf.get(7);

        double weightGrams = rawWeight / 10.0;
        double temperatureC = rawTemp / 100.0;
        boolean isValid = (status & 0x01) != 0;

        return WeightSensorReading.builder()
                .timestamp(frame.getTimestamp() != null ? frame.getTimestamp() : Instant.now())
                .sensorId(sensorId & 0xFF)
                .weightGrams(weightGrams)
                .temperatureC(temperatureC)
                .isValid(isValid)
                .rawHexData(frame.toHexString())
                .build();
    }

    public static DeviceStatus parsePumpPdo(CanOpenFrame frame) {
        if (frame.getData() == null || frame.getData().length < 8) {
            return null;
        }

        byte[] data = frame.getData();
        ByteBuffer buf = ByteBuffer.wrap(data);

        int currentRpm = buf.getShort(0) & 0xFFFF;
        int targetRpm = buf.getShort(2) & 0xFFFF;
        byte state = buf.get(4);
        byte errorCode = buf.get(5);

        DeviceStatus.OperationalState opState;
        int stateCode = state & 0x0F;
        if (stateCode == 0x00) {
            opState = DeviceStatus.OperationalState.OFFLINE;
        } else if (stateCode == 0x04) {
            opState = DeviceStatus.OperationalState.STANDBY;
        } else if (stateCode == 0x05) {
            opState = DeviceStatus.OperationalState.RUNNING;
        } else if (stateCode == 0x0C) {
            opState = DeviceStatus.OperationalState.CALIBRATING;
        } else {
            opState = DeviceStatus.OperationalState.ERROR;
        }

        return DeviceStatus.builder()
                .timestamp(frame.getTimestamp() != null ? frame.getTimestamp() : Instant.now())
                .nodeId(frame.getNodeId())
                .deviceType(DeviceStatus.DeviceType.PERISTALTIC_PUMP)
                .state(opState)
                .currentValue(currentRpm)
                .targetValue(targetRpm)
                .online((state & 0x80) != 0)
                .errorMessage(errorCode != 0 ? "Error code: 0x" + Integer.toHexString(errorCode & 0xFF) : null)
                .build();
    }

    public static DeviceStatus parseHeaterPdo(CanOpenFrame frame) {
        if (frame.getData() == null || frame.getData().length < 8) {
            return null;
        }

        byte[] data = frame.getData();
        ByteBuffer buf = ByteBuffer.wrap(data);

        short currentTemp = buf.getShort(0);
        short targetTemp = buf.getShort(2);
        byte state = buf.get(4);
        byte powerPercent = buf.get(5);

        DeviceStatus.OperationalState opState;
        int stateCode = state & 0x0F;
        if (stateCode == 0x00) {
            opState = DeviceStatus.OperationalState.OFFLINE;
        } else if (stateCode == 0x04) {
            opState = DeviceStatus.OperationalState.STANDBY;
        } else if (stateCode == 0x05) {
            opState = DeviceStatus.OperationalState.RUNNING;
        } else {
            opState = DeviceStatus.OperationalState.ERROR;
        }

        return DeviceStatus.builder()
                .timestamp(frame.getTimestamp() != null ? frame.getTimestamp() : Instant.now())
                .nodeId(frame.getNodeId())
                .deviceType(DeviceStatus.DeviceType.HEATING_BAG)
                .state(opState)
                .currentValue(currentTemp / 10.0)
                .targetValue(targetTemp / 10.0)
                .online((state & 0x80) != 0)
                .errorMessage(powerPercent > 100 ? "Power overload: " + powerPercent + "%" : null)
                .build();
    }
}
