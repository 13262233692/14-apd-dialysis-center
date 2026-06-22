package com.apd.dialysis.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/system")
@CrossOrigin(origins = "*")
public class SystemController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now());
        health.put("service", "apd-dialysis-center");
        health.put("version", "1.0.0");
        return ResponseEntity.ok(health);
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "APD Dialysis Cloud Control Center");
        info.put("description", "智能腹膜透析机云端流控中心");
        info.put("version", "1.0.0");
        info.put("features", Map.of(
                "canopen", "Java NIO based CANopen protocol parser",
                "filter", "Median-Mean filter for weight sensor noise reduction",
                "websocket", "STOMP over WebSocket for real-time streaming",
                "echarts", "Net Ultrafiltration real-time area chart"
        ));
        info.put("timestamp", Instant.now());
        return ResponseEntity.ok(info);
    }
}
