package com.apd.dialysis.controller;

import com.apd.dialysis.model.PeritonitisAlert;
import com.apd.dialysis.model.TurbidityReading;
import com.apd.dialysis.service.TurbidityAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/turbidity")
@RequiredArgsConstructor
public class TurbidityAlertController {

    private final TurbidityAlertService alertService;

    @GetMapping("/alert/current")
    public ResponseEntity<PeritonitisAlert> getCurrentAlert() {
        PeritonitisAlert alert = alertService.getCurrentAlert();
        if (alert == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(alert);
    }

    @PostMapping("/alert/{alertId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(@PathVariable String alertId) {
        boolean ok = alertService.acknowledgeAlert(alertId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("alertId", alertId);
        resp.put("acknowledged", ok);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/alert/dismiss")
    public ResponseEntity<Map<String, Object>> dismissAlert() {
        alertService.dismissAlert();
        Map<String, Object> resp = new HashMap<>();
        resp.put("dismissed", true);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history/72h")
    public ResponseEntity<List<TurbidityReading>> get72hHistory() {
        return ResponseEntity.ok(alertService.getTurbidityHistory72h());
    }

    @GetMapping("/history/flow")
    public ResponseEntity<List<Double>> getDrainFlowHistory() {
        return ResponseEntity.ok(alertService.getDrainFlowHistory());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("historySize", alertService.getHistorySize());
        PeritonitisAlert current = alertService.getCurrentAlert();
        status.put("hasActiveAlert", current != null);
        if (current != null) {
            status.put("alertId", current.getAlertId());
            status.put("severity", current.getSeverity());
            status.put("alertTimestamp", current.getTimestamp());
            status.put("acknowledged", current.isAcknowledged());
        }
        return ResponseEntity.ok(status);
    }
}
