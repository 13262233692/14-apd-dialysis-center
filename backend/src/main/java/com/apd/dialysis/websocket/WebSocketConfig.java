package com.apd.dialysis.websocket;

import com.apd.dialysis.config.ApdProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final int OUTBOUND_CORE_POOL = 2;
    private static final int OUTBOUND_MAX_POOL = 4;
    private static final int OUTBOUND_QUEUE_CAPACITY = 500;
    private static final int INBOUND_CORE_POOL = 2;
    private static final int INBOUND_MAX_POOL = 4;
    private static final int INBOUND_QUEUE_CAPACITY = 200;
    private static final long SEND_TIME_LIMIT_MS = 10000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final ApdProperties properties;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {
                        properties.getWebsocket().getHeartbeatIntervalMs(),
                        properties.getWebsocket().getHeartbeatIntervalMs()
                })
                .setTaskScheduler(createHeartbeatScheduler());

        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");

        int maxClients = properties.getWebsocket().getMaxClients();
        log.info("WebSocket broker configured: maxClients={}, heartbeatInterval={}ms, outBoundQueueCap={}",
                maxClients, properties.getWebsocket().getHeartbeatIntervalMs(), OUTBOUND_QUEUE_CAPACITY);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(INBOUND_CORE_POOL)
                .maxPoolSize(INBOUND_MAX_POOL)
                .queueCapacity(INBOUND_QUEUE_CAPACITY)
                .keepAliveSeconds(60);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(OUTBOUND_CORE_POOL)
                .maxPoolSize(OUTBOUND_MAX_POOL)
                .queueCapacity(OUTBOUND_QUEUE_CAPACITY)
                .keepAliveSeconds(60);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/dialysis")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setStreamBytesLimit(SEND_BUFFER_SIZE_LIMIT_BYTES)
                .setHttpMessageCacheSize(1000)
                .setDisconnectDelay(5000);

        registry.addEndpoint("/ws/dialysis")
                .setAllowedOriginPatterns("*");
    }

    private org.springframework.scheduling.TaskScheduler createHeartbeatScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
