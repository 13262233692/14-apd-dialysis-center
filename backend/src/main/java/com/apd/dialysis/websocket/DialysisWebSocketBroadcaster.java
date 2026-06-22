package com.apd.dialysis.websocket;

import com.apd.dialysis.buffer.DialysisDataBuffer;
import com.apd.dialysis.model.DialysisDataPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DialysisWebSocketBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final DialysisDataBuffer dataBuffer;
    private final ObjectMapper objectMapper;

    public static final String TOPIC_DATA_POINT = "/topic/dialysis/datapoint";
    public static final String TOPIC_STATUS = "/topic/dialysis/status";
    public static final String TOPIC_DEVICES = "/topic/dialysis/devices";

    @PostConstruct
    public void init() {
        dataBuffer.addFlushListener(this::broadcastDataPoint);
        log.info("WebSocket broadcaster initialized");
    }

    public void broadcastDataPoint(DialysisDataPoint point) {
        try {
            messagingTemplate.convertAndSend(TOPIC_DATA_POINT, point);
            log.trace("Broadcasted data point to {}: netUF={}ml", TOPIC_DATA_POINT, point.getNetUltrafiltrationMl());
        } catch (Exception e) {
            log.warn("Failed to broadcast data point", e);
        }
    }

    public void broadcastStatus(Object status) {
        try {
            messagingTemplate.convertAndSend(TOPIC_STATUS, status);
        } catch (Exception e) {
            log.warn("Failed to broadcast status", e);
        }
    }

    public void broadcastDevices(Object devices) {
        try {
            messagingTemplate.convertAndSend(TOPIC_DEVICES, devices);
        } catch (Exception e) {
            log.warn("Failed to broadcast devices", e);
        }
    }
}
