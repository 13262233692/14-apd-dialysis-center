package com.apd.dialysis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "apd")
public class ApdProperties {

    private Hardware hardware = new Hardware();
    private Websocket websocket = new Websocket();
    private Buffer buffer = new Buffer();

    @Data
    public static class Hardware {
        private CanOpen canopen = new CanOpen();
        private Sensor sensor = new Sensor();
        private Simulator simulator = new Simulator();
    }

    @Data
    public static class CanOpen {
        private int port = 60000;
        private int bufferSize = 1024;
        private int readTimeout = 5000;
    }

    @Data
    public static class Sensor {
        private int sampleIntervalMs = 100;
        private int filterWindowSize = 9;
    }

    @Data
    public static class Simulator {
        private boolean enabled = true;
        private boolean patientMovementNoise = true;
    }

    @Data
    public static class Websocket {
        private int heartbeatIntervalMs = 10000;
        private int maxClients = 100;
    }

    @Data
    public static class Buffer {
        private int queueCapacity = 10000;
        private int flushIntervalMs = 50;
    }
}
