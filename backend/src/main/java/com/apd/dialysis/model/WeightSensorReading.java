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
public class WeightSensorReading {

    private Instant timestamp;

    private int sensorId;

    private double weightGrams;

    private double temperatureC;

    private boolean isValid;

    private String rawHexData;
}
