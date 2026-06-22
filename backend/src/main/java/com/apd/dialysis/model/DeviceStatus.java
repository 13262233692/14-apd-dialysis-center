package com.apd.dialysis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceStatus {

    private Instant timestamp;

    private int nodeId;

    private DeviceType deviceType;

    private OperationalState state;

    private double currentValue;

    private double targetValue;

    private boolean online;

    private String errorMessage;

    public enum DeviceType {
        PERISTALTIC_PUMP, HEATING_BAG, WEIGHT_SENSOR
    }

    public enum OperationalState {
        OFFLINE, STANDBY, RUNNING, ERROR, CALIBRATING
    }
}
