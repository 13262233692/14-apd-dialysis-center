package com.apd.dialysis.controller;

import com.apd.dialysis.buffer.DialysisDataBuffer;
import com.apd.dialysis.hardware.HardwareService;
import com.apd.dialysis.model.DeviceCommand;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.service.DialysisDataAggregator;
import com.apd.dialysis.service.MemoryGuardianService;
import com.apd.dialysis.service.PipelineOrchestrator;
import com.apd.dialysis.websocket.DialysisWebSocketBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/dialysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DialysisController {

    private final HardwareService hardwareService;
    private final DialysisDataBuffer dataBuffer;
    private final DialysisDataAggregator aggregator;
    private final PipelineOrchestrator orchestrator;
    private final MemoryGuardianService memoryGuardian;
    private final DialysisWebSocketBroadcaster wsBroadcaster;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        DialysisDataPoint latest = dataBuffer.peekLatest();

        status.put("hardwareRunning", hardwareService.isRunning());
        status.put("timestamp", Instant.now());
        status.put("bufferSize", dataBuffer.size());
        status.put("totalProduced", dataBuffer.getTotalProduced());
        status.put("totalFlushed", dataBuffer.getTotalFlushed());
        status.put("totalDropped", dataBuffer.getTotalDropped());
        status.put("totalDroppedByHeapPressure", dataBuffer.getTotalDroppedByHeapPressure());
        status.put("dropRate", dataBuffer.getDropRate());
        status.put("heapEmergency", dataBuffer.isHeapEmergency());
        status.put("heapUsageRatio", dataBuffer.getLastHeapUsageRatio());
        status.put("spikeSuppressionRate", aggregator.getSpikeSuppressionRate());
        status.put("spikesSuppressed", aggregator.getSpikeSuppressedCount());

        status.put("wsQueueSize", wsBroadcaster.getQueueSize());
        status.put("wsActiveClients", wsBroadcaster.getActiveClientCount());
        status.put("wsTotalEnqueued", wsBroadcaster.getTotalEnqueued());
        status.put("wsTotalSent", wsBroadcaster.getTotalSent());
        status.put("wsTotalDropped", wsBroadcaster.getTotalDropped());
        status.put("wsBackpressureActive", wsBroadcaster.isBackpressureActive());

        MemoryGuardianService.MemorySnapshot mem = memoryGuardian.getSnapshot();
        status.put("memory", mem);

        if (latest != null) {
            status.put("currentDataPoint", latest);
        }

        return ResponseEntity.ok(status);
    }

    @GetMapping("/datapoint/latest")
    public ResponseEntity<DialysisDataPoint> getLatestDataPoint() {
        DialysisDataPoint point = dataBuffer.peekLatest();
        if (point == null) {
            point = aggregator.buildCurrentDataPoint();
        }
        return ResponseEntity.ok(point);
    }

    @GetMapping("/datapoint/history")
    public ResponseEntity<List<DialysisDataPoint>> getHistory(
            @RequestParam(defaultValue = "100") int limit) {
        List<DialysisDataPoint> history = dataBuffer.peekLast(Math.min(limit, 1000));
        return ResponseEntity.ok(history);
    }

    @GetMapping("/devices")
    public ResponseEntity<List<DeviceStatus>> getDevices() {
        return ResponseEntity.ok(hardwareService.getAllDeviceStatuses());
    }

    @GetMapping("/devices/{nodeId}")
    public ResponseEntity<DeviceStatus> getDevice(@PathVariable int nodeId) {
        return hardwareService.getDeviceStatus(nodeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> sendCommand(@RequestBody DeviceCommand command) {
        if (command.getCommandId() == null) {
            command.setCommandId(UUID.randomUUID().toString());
        }
        if (command.getTimestamp() == null) {
            command.setTimestamp(Instant.now());
        }

        hardwareService.sendCommand(command);

        if (command.getType() == DeviceCommand.CommandType.START_DIALYSIS
                || command.getType() == DeviceCommand.CommandType.STOP_DIALYSIS) {
            aggregator.tareSensors();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("commandId", command.getCommandId());
        response.put("status", "ACCEPTED");
        response.put("timestamp", Instant.now());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tare")
    public ResponseEntity<Map<String, Object>> tareSensors() {
        aggregator.tareSensors();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "TARED");
        response.put("timestamp", Instant.now());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pipeline")
    public ResponseEntity<String> getPipelineStatus() {
        return ResponseEntity.ok(orchestrator.getPipelineStatus());
    }

    @GetMapping("/ws/clients")
    public ResponseEntity<List<DialysisWebSocketBroadcaster.ClientSessionInfo>> getWsClients() {
        return ResponseEntity.ok(wsBroadcaster.getClientSessionInfos());
    }

    @GetMapping("/memory")
    public ResponseEntity<MemoryGuardianService.MemorySnapshot> getMemorySnapshot() {
        return ResponseEntity.ok(memoryGuardian.getSnapshot());
    }
}
