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
public class DialysisDataPoint {

    private Instant timestamp;

    private double inflowVolumeMl;

    private double outflowVolumeMl;

    private double netUltrafiltrationMl;

    private double inflowFlowRateMlPerMin;

    private double outflowFlowRateMlPerMin;

    private double abdominalVolumeMl;

    private double dialysateTemperatureC;

    private DialysisPhase phase;

    private SensorQuality sensorQuality;

    public enum DialysisPhase {
        IDLE, FILL, DWELL, DRAIN, COMPLETE
    }

    public enum SensorQuality {
        EXCELLENT, GOOD, FAIR, POOR, NOISY
    }
}
