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

    private double transmittancePercent;

    private double absorbance420nm;
    private double absorbance540nm;
    private double absorbance660nm;
    private double absorbance720nm;

    private String spectralHexSignature;

    private double drainElapsedMinutes;
    private double drainTargetMinutes;

    private TurbidityReading.AlertLevel turbidityAlertLevel;
    private double turbidityAnomalyScore;

    public enum DialysisPhase {
        IDLE, FILL, DWELL, DRAIN, COMPLETE
    }

    public enum SensorQuality {
        EXCELLENT, GOOD, FAIR, POOR, NOISY
    }

    public TurbidityReading toTurbidityReading() {
        return TurbidityReading.builder()
                .timestamp(timestamp != null ? timestamp : Instant.now())
                .transmittancePercent(transmittancePercent)
                .absorbance420nm(absorbance420nm)
                .absorbance540nm(absorbance540nm)
                .absorbance660nm(absorbance660nm)
                .absorbance720nm(absorbance720nm)
                .spectralHexSignature(spectralHexSignature)
                .drainFlowRateMlPerMin(outflowFlowRateMlPerMin)
                .drainElapsedMinutes(drainElapsedMinutes)
                .drainTargetMinutes(drainTargetMinutes)
                .anomalyScore(turbidityAnomalyScore)
                .alertLevel(turbidityAlertLevel != null ? turbidityAlertLevel : TurbidityReading.AlertLevel.NONE)
                .build();
    }
}
