package com.apd.dialysis.websocket;

import com.apd.dialysis.buffer.DialysisDataBuffer;
import com.apd.dialysis.config.ApdProperties;
import com.apd.dialysis.model.DialysisDataPoint;
import com.apd.dialysis.model.PeritonitisAlert;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class DialysisWebSocketBroadcaster {

    public static final String TOPIC_DATA_POINT = "/topic/dialysis/datapoint";
    public static final String TOPIC_STATUS = "/topic/dialysis/status";
    public static final String TOPIC_DEVICES = "/topic/dialysis/devices";
    public static final String TOPIC_ALERT_PERITONITIS = "/topic/dialysis/alerts/peritonitis";

    private static final int DEFAULT_QUEUE_CAPACITY = 2000;
    private static final int HIGH_WATER_MARK = 1500;
    private static final int LOW_WATER_MARK = 500;
    private static final int BATCH_SIZE = 50;
    private static final long SLOW_CLIENT_THRESHOLD_MS = 500;
    private static final int MAX_SLOW_SEND_COUNT = 5;
    private static final long CLIENT_MONITOR_INTERVAL_MS = 10000;

    private final SimpMessagingTemplate messagingTemplate;
    private final DialysisDataBuffer dataBuffer;
    private final ObjectMapper objectMapper;
    private final ApdProperties properties;

    private final BlockingQueue<DialysisDataPoint> sendQueue;
    private final ConcurrentHashMap<String, ClientSession> activeSessions = new ConcurrentHashMap<>();

    private ExecutorService senderExecutor;
    private ScheduledExecutorService monitorExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicBoolean backpressureActive = new AtomicBoolean(false);

    public DialysisWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate,
                                        DialysisDataBuffer dataBuffer,
                                        ObjectMapper objectMapper,
                                        ApdProperties properties) {
        this.messagingTemplate = messagingTemplate;
        this.dataBuffer = dataBuffer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sendQueue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY);
    }

    @PostConstruct
    public void init() {
        running.set(true);

        senderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ws-broadcast-sender");
            t.setDaemon(true);
            return t;
        });
        senderExecutor.submit(this::senderLoop);

        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-client-monitor");
            t.setDaemon(true);
            return t;
        });
        monitorExecutor.scheduleAtFixedRate(
                this::monitorSlowClients,
                CLIENT_MONITOR_INTERVAL_MS,
                CLIENT_MONITOR_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        dataBuffer.addFlushListener(this::enqueueDataPoint);
        log.info("WebSocket broadcaster initialized with backpressure (queueCap={}, highWater={}, lowWater={})",
                DEFAULT_QUEUE_CAPACITY, HIGH_WATER_MARK, LOW_WATER_MARK);
    }

    @PreDestroy
    public void destroy() {
        running.set(false);
        if (senderExecutor != null) {
            senderExecutor.shutdownNow();
        }
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        sendQueue.clear();
        activeSessions.clear();
        log.info("WebSocket broadcaster destroyed. Enqueued={}, Dropped={}, Sent={}",
                totalEnqueued.get(), totalDropped.get(), totalSent.get());
    }

    private void enqueueDataPoint(DialysisDataPoint point) {
        if (point == null || !running.get()) return;

        if (backpressureActive.get() && sendQueue.size() > LOW_WATER_MARK) {
            totalDropped.incrementAndGet();
            return;
        }

        boolean offered = sendQueue.offer(point);
        if (offered) {
            totalEnqueued.incrementAndGet();
            if (sendQueue.size() >= HIGH_WATER_MARK && backpressureActive.compareAndSet(false, true)) {
                log.warn("WebSocket send queue reached high-water mark ({}), backpressure ACTIVATED", sendQueue.size());
            }
        } else {
            totalDropped.incrementAndGet();
            if (sendQueue.size() >= HIGH_WATER_MARK) {
                backpressureActive.set(true);
            }
        }
    }

    private void senderLoop() {
        final List<DialysisDataPoint> batch = new ArrayList<>(BATCH_SIZE);
        while (running.get()) {
            try {
                batch.clear();
                DialysisDataPoint first = sendQueue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                sendQueue.drainTo(batch, BATCH_SIZE - 1);

                if (backpressureActive.get() && sendQueue.size() <= LOW_WATER_MARK) {
                    if (backpressureActive.compareAndSet(true, false)) {
                        log.info("WebSocket send queue drained to low-water mark ({}), backpressure DEACTIVATED", sendQueue.size());
                    }
                }

                if (batch.isEmpty()) continue;

                long startTs = System.currentTimeMillis();
                for (DialysisDataPoint point : batch) {
                    try {
                        messagingTemplate.convertAndSend(TOPIC_DATA_POINT, point);
                    } catch (Exception e) {
                        log.debug("Failed to broadcast data point", e);
                    }
                }
                long elapsed = System.currentTimeMillis() - startTs;
                totalSent.addAndGet(batch.size());

                if (elapsed > SLOW_CLIENT_THRESHOLD_MS) {
                    log.warn("Slow WebSocket broadcast detected: batch={}pts, elapsed={}ms", batch.size(), elapsed);
                    for (ClientSession session : activeSessions.values()) {
                        session.recordSlowSend(elapsed);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("WebSocket sender loop error", e);
            }
        }
    }

    private void monitorSlowClients() {
        try {
            List<String> toEvict = new ArrayList<>();
            for (ClientSession session : activeSessions.values()) {
                if (session.shouldEvict()) {
                    toEvict.add(session.sessionId);
                }
            }
            for (String sessionId : toEvict) {
                ClientSession evicted = activeSessions.remove(sessionId);
                if (evicted != null) {
                    log.warn("Evicting slow WebSocket client: sessionId={}, slowCount={}, lastSlowMs={}",
                            evicted.sessionId, evicted.slowSendCount.get(), evicted.lastSlowSendMs.get());
                }
            }
        } catch (Exception e) {
            log.error("Client monitor error", e);
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

    public void broadcastAlert(PeritonitisAlert alert) {
        if (alert == null) return;
        try {
            messagingTemplate.convertAndSend(TOPIC_ALERT_PERITONITIS, alert);
            log.error("BROADCAST CRITICAL ALERT: id={}, severity={}", alert.getAlertId(), alert.getSeverity());
        } catch (Exception e) {
            log.warn("Failed to broadcast peritonitis alert", e);
        }
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        String sessionId = resolveSessionId(event);
        if (sessionId != null) {
            activeSessions.putIfAbsent(sessionId, new ClientSession(sessionId));
            log.info("WebSocket client CONNECTED: sessionId={}, activeClients={}", sessionId, activeSessions.size());
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId != null) {
            ClientSession removed = activeSessions.remove(sessionId);
            if (removed != null) {
                log.info("WebSocket client DISCONNECTED: sessionId={}, activeClients={}", sessionId, activeSessions.size());
            }
        }
    }

    @EventListener
    public void onSessionSubscribe(SessionSubscribeEvent event) {
        String sessionId = resolveSessionId(event);
        if (sessionId != null) {
            activeSessions.putIfAbsent(sessionId, new ClientSession(sessionId));
            log.debug("WebSocket client SUBSCRIBED: sessionId={}", sessionId);
        }
    }

    @EventListener
    public void onSessionUnsubscribe(SessionUnsubscribeEvent event) {
        log.debug("WebSocket client UNSUBSCRIBED: sessionId={}", resolveSessionId(event));
    }

    @EventListener
    public void onBrokerAvailability(BrokerAvailabilityEvent event) {
        log.info("STOMP broker availability: available={}", event.isBrokerAvailable());
    }

    private String resolveSessionId(Object event) {
        try {
            if (event instanceof SessionDisconnectEvent) {
                return ((SessionDisconnectEvent) event).getSessionId();
            }
            org.springframework.messaging.Message<?> msg = null;
            if (event instanceof SessionConnectedEvent) {
                msg = ((SessionConnectedEvent) event).getMessage();
            } else if (event instanceof SessionSubscribeEvent) {
                msg = ((SessionSubscribeEvent) event).getMessage();
            } else if (event instanceof SessionUnsubscribeEvent) {
                msg = ((SessionUnsubscribeEvent) event).getMessage();
            }
            if (msg != null) {
                Object sessionAttr = msg.getHeaders().get("simpSessionId");
                if (sessionAttr != null) return sessionAttr.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public int getQueueSize() { return sendQueue.size(); }
    public long getTotalEnqueued() { return totalEnqueued.get(); }
    public long getTotalDropped() { return totalDropped.get(); }
    public long getTotalSent() { return totalSent.get(); }
    public boolean isBackpressureActive() { return backpressureActive.get(); }
    public int getActiveClientCount() { return activeSessions.size(); }

    public List<ClientSessionInfo> getClientSessionInfos() {
        List<ClientSessionInfo> infos = new ArrayList<>();
        for (ClientSession s : activeSessions.values()) {
            infos.add(new ClientSessionInfo(s.sessionId, s.slowSendCount.get(), s.lastSlowSendMs.get()));
        }
        return Collections.unmodifiableList(infos);
    }

    private static class ClientSession {
        final String sessionId;
        final long connectedAt = System.currentTimeMillis();
        final AtomicInteger slowSendCount = new AtomicInteger(0);
        final AtomicLong lastSlowSendMs = new AtomicLong(0);

        ClientSession(String sessionId) {
            this.sessionId = sessionId;
        }

        void recordSlowSend(long elapsedMs) {
            slowSendCount.incrementAndGet();
            lastSlowSendMs.set(System.currentTimeMillis());
        }

        boolean shouldEvict() {
            return slowSendCount.get() >= MAX_SLOW_SEND_COUNT
                    && (System.currentTimeMillis() - lastSlowSendMs.get()) < 60000;
        }
    }

    public static class ClientSessionInfo {
        public final String sessionId;
        public final int slowSendCount;
        public final long lastSlowSendMs;

        public ClientSessionInfo(String sessionId, int slowSendCount, long lastSlowSendMs) {
            this.sessionId = sessionId;
            this.slowSendCount = slowSendCount;
            this.lastSlowSendMs = lastSlowSendMs;
        }
    }
}
