package com.sch.demonstrator.bot.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Data
@Configuration
@ConfigurationProperties(prefix = "request")
@ToString
@Slf4j
public class BotProperties {

    private String mode;

    private Generation generation;
    private Upload upload;

    private String executionOutput;

    private Websocket websocket;

    private volatile Instant startTime;
    private long botTimeShift;


    @Data
    @ToString
    public static class Generation {
        private int startHour;
        private int endHour;
        private String generatedRequestOutput;
        private Soc soc;
    }

    @Data
    @ToString
    public static class Soc {
        private Gaussian target;
    }

    @Data
    @ToString
    public static class Gaussian {
        private double mean;
        private double stdDev;
    }

    @Data
    @ToString
    public static class Upload {
        private String input;
    }

    @Data
    @ToString
    public static class Websocket {
        private double publishIntervalSeconds;
    }

    @PostConstruct
    private void init() {
        startTime = Instant.now();
        botTimeShift = generation.getStartHour() * 3600;

        log.info("Bot properties initialized: {}", this);
    }
}