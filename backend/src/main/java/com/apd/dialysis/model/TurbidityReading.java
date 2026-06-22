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
public class TurbidityReading {

    private Instant timestamp;

    private double transmittancePercent;

    private double absorbance420nm;
    private double absorbance540nm;
    private double absorbance660nm;
    private double absorbance720nm;

    private String spectralHexSignature;

    private double drainFlowRateMlPerMin;
    private double drainElapsedMinutes;
    private double drainTargetMinutes;

    private boolean anomalyFlag;
    private double anomalyScore;
    private AlertLevel alertLevel;

    public enum AlertLevel {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    public double computeSpectralTurbidityIndex() {
        double s1 = Math.max(0, 1.0 - absorbance420nm);
        double s2 = Math.max(0, 1.0 - absorbance540nm);
        double s3 = Math.max(0, 1.0 - absorbance660nm);
        double s4 = Math.max(0, 1.0 - absorbance720nm);
        return (s1 + s2 + s3 + s4) / 4.0;
    }

    public String toSpectralHex() {
        int v420 = clampInt((int) (absorbance420nm * 255), 0, 255);
        int v540 = clampInt((int) (absorbance540nm * 255), 0, 255);
        int v660 = clampInt((int) (absorbance660nm * 255), 0, 255);
        int v720 = clampInt((int) (absorbance720nm * 255), 0, 255);
        return String.format("%02X%02X%02X%02X", v420, v540, v660, v720);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static TurbidityReading fromSpectralHex(String hex, Instant ts) {
        if (hex == null || hex.length() < 8) {
            return TurbidityReading.builder()
                    .timestamp(ts == null ? Instant.now() : ts)
                    .transmittancePercent(100.0)
                    .absorbance420nm(0.05).absorbance540nm(0.03)
                    .absorbance660nm(0.02).absorbance720nm(0.02)
                    .alertLevel(AlertLevel.NONE).anomalyScore(0.0)
                    .build();
        }
        try {
            double a420 = Integer.parseInt(hex.substring(0, 2), 16) / 255.0;
            double a540 = Integer.parseInt(hex.substring(2, 4), 16) / 255.0;
            double a660 = Integer.parseInt(hex.substring(4, 6), 16) / 255.0;
            double a720 = Integer.parseInt(hex.substring(6, 8), 16) / 255.0;
            double transmittance = 100.0 * Math.max(0, 1.0 - (a420 + a540 + a660 + a720) / 4.0);
            return TurbidityReading.builder()
                    .timestamp(ts == null ? Instant.now() : ts)
                    .transmittancePercent(transmittance)
                    .absorbance420nm(a420).absorbance540nm(a540)
                    .absorbance660nm(a660).absorbance720nm(a720)
                    .spectralHexSignature(hex)
                    .alertLevel(AlertLevel.NONE).anomalyScore(0.0)
                    .build();
        } catch (Exception e) {
            return TurbidityReading.builder()
                    .timestamp(ts == null ? Instant.now() : ts)
                    .transmittancePercent(100.0).alertLevel(AlertLevel.NONE)
                    .build();
        }
    }
}
