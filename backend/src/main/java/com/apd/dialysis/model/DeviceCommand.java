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
public class DeviceCommand {

    private String commandId;

    private Instant timestamp;

    private CommandType type;

    private double targetValue;

    private int nodeId;

    public enum CommandType {
        START_PUMP, STOP_PUMP, SET_FLOW_RATE,
        START_HEATING, STOP_HEATING, SET_TEMPERATURE,
        TARE_SENSOR, CALIBRATE_SENSOR,
        START_DIALYSIS, STOP_DIALYSIS, EMERGENCY_STOP
    }
}
