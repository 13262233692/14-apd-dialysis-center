package com.apd.dialysis.service;

import com.apd.dialysis.buffer.DialysisDataBuffer;
import com.apd.dialysis.buffer.HardwareEventBus;
import com.apd.dialysis.hardware.HardwareService;
import com.apd.dialysis.hardware.SimulatedHardwareService;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.model.WeightSensorReading;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final HardwareService hardwareService;
    private final HardwareEventBus eventBus;
    private final DialysisDataBuffer dataBuffer;
    private final DialysisDataAggregator aggregator;

    @PostConstruct
    public void wirePipeline() {
        hardwareService.registerSensorReadingCallback(eventBus::publishSensorReading);
        hardwareService.registerDeviceStatusCallback(eventBus::publishDeviceStatus);

        eventBus.registerSensorListener(this::processSensorReading);

        dataBuffer.addFlushListener(this::onDataPointFlushed);

        log.info("Pipeline orchestrator wired: Hardware → EventBus → Filter → Aggregator → Buffer → WebSocket");
    }

    private void processSensorReading(WeightSensorReading reading) {
        aggregator.buildCurrentDataPoint();
    }

    @Scheduled(fixedRateString = "${apd.buffer.flush-interval-ms:50}")
    public void produceAggregatedDataPoint() {
        DialysisDataPoint point = aggregator.buildCurrentDataPoint();
        dataBuffer.produce(point);
    }

    private void onDataPointFlushed(DialysisDataPoint point) {
        log.trace("Data point flushed: netUF={}ml, phase={}", point.getNetUltrafiltrationMl(), point.getPhase());
    }

    public String getPipelineStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Pipeline Status ===\n");
        sb.append("Hardware running: ").append(hardwareService.isRunning()).append("\n");
        sb.append("Buffer size: ").append(dataBuffer.size()).append("\n");
        sb.append("Buffer total produced: ").append(dataBuffer.getTotalProduced()).append("\n");
        sb.append("Buffer drop rate: ").append(String.format("%.4f%%", dataBuffer.getDropRate() * 100)).append("\n");
        sb.append("Spike suppression rate: ").append(String.format("%.4f%%", aggregator.getSpikeSuppressionRate() * 100)).append("\n");
        sb.append("Spikes suppressed: ").append(aggregator.getSpikeSuppressedCount()).append("\n");
        if (hardwareService instanceof SimulatedHardwareService) {
            SimulatedHardwareService sim = (SimulatedHardwareService) hardwareService;
            sb.append("Simulated phase: ").append(sim.getCurrentPhase()).append("\n");
            sb.append("Simulated abdominal volume: ").append(String.format("%.1fml", sim.getCurrentAbdominalVolumeMl())).append("\n");
            sb.append("Total inflow: ").append(String.format("%.1fml", sim.getTotalInflowMl())).append("\n");
            sb.append("Total outflow: ").append(String.format("%.1fml", sim.getTotalOutflowMl())).append("\n");
        }
        for (DeviceStatus s : hardwareService.getAllDeviceStatuses()) {
            sb.append(String.format("Device[%d] %s: state=%s, current=%.2f, target=%.2f, online=%s%n",
                    s.getNodeId(), s.getDeviceType(), s.getState(),
                    s.getCurrentValue(), s.getTargetValue(), s.isOnline()));
        }
        return sb.toString();
    }
}
