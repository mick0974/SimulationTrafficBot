package com.sch.demonstrator.bot.service;

import com.opencsv.bean.CsvBindByName;
import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.event.AssignChargerEvent;
import com.sch.demonstrator.bot.event.ChargingEndedEvent;
import com.sch.demonstrator.bot.model.BackgroundEVRequest;
import com.sch.demonstrator.bot.model.Charger;
import com.sch.demonstrator.bot.service.processing.ChargingModelService;
import com.sch.demonstrator.bot.service.processing.HubManagementService;
import com.sch.demonstrator.bot.service.startup.EVRequestInitializer;
import com.sch.demonstrator.bot.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.Pair;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@Slf4j
@Profile("dev")
public class ChargingManagerDebug {

    @Getter
    @ToString
    public static class TrackedEVRequest {
        @CsvBindByName(column = "request_id")
        private UUID requestId;
        @CsvBindByName(column = "arrivalTime")
        private long arrivalTime;
        @CsvBindByName(column = "start_charging")
        private long startCharging;
        @CsvBindByName(column = "wait_time")
        private long waitTime;
        @CsvBindByName(column = "charge_duration")
        private long chargeDuration;
        @CsvBindByName(column = "end_charging")
        private long endCharging = -1;
        @CsvBindByName(column = "soc_arrival")
        private double socArrival;

        @CsvBindByName(column = "hubId")
        private String hubId;
        @CsvBindByName(column = "chargerId")
        private String chargerId;
        @CsvBindByName(column = "plugType")
        private String plugType;

        @CsvBindByName(column = "arrival_time_formatted")
        private String arrivalTimeFormatted;
        @CsvBindByName(column = "start_charging_formatted")
        private String startChargingFormatted;
        @CsvBindByName(column = "end_charging_formatted")
        private String endChargingFormatted;

        public TrackedEVRequest(UUID requestId, long arrivalTime, double socArrival, String hubId) {
            this.requestId = requestId;
            this.arrivalTime = arrivalTime;
            this.socArrival = socArrival;
            this.hubId = hubId;
            this.arrivalTimeFormatted = Utils.formatTime(arrivalTime);
        }

        private void setChargerData(String chargerId, String plugType) {
            this.chargerId = chargerId;
            this.plugType = plugType;
        }

        private void setStartChargingAndWaitTime(long startCharging, long waitTime) {
            this.startCharging = startCharging;
            this.waitTime = waitTime;
            this.startChargingFormatted = Utils.formatTime(startCharging);
        }

        private void setChargeEndAndDuration(long endCharging, long chargeDuration) {
            this.chargeDuration = chargeDuration;
            this.endCharging = endCharging;
            this.endChargingFormatted = Utils.formatTime(endCharging);
        }
    }

    private final Map<String, Set<TrackedEVRequest>> trackedRequests = new ConcurrentHashMap<>();

    private final EVRequestInitializer evEVRequestInitializer;
    private final HubManagementService hubManagementService;

    private final Queue<BackgroundEVRequest> evRequests = new ConcurrentLinkedQueue<>();
    private final ChargingModelService chargingModelService;

    @Getter
    private volatile Instant startTime;
    @Getter
    private long botTimeShift;
    private final BotProperties props;

    public ChargingManagerDebug(EVRequestInitializer evEVRequestInitializer, HubManagementService hubManagementService, ChargingModelService chargingModelService, BotProperties props) {
        this.evEVRequestInitializer = evEVRequestInitializer;
        this.hubManagementService = hubManagementService;
        this.chargingModelService = chargingModelService;
        this.props = props;
    }

    @PostConstruct
    private void init() {
        String[] hubIds = hubManagementService.getHubIds();
        evRequests.addAll(evEVRequestInitializer.generateRequests(hubIds));
        startTime = props.getStartTime();
        botTimeShift = props.getBotTimeShift();
    }

    @EventListener(ApplicationReadyEvent.class)
    private void executeBackgroundRequests() {
        log.info("=== Start RequestManager ===");
        log.info("RequestManager started with current internal time {}", Utils.formatTime(botTimeShift));

        for (BackgroundEVRequest evRequest : evRequests) {
            chargeBackgroundVehicle(new AssignChargerEvent(evRequest));
        }

        trackedRequests.values().forEach(set -> set.forEach(this::writeToCsv));
        System.exit(0);
    }

    protected void chargeBackgroundVehicle(AssignChargerEvent event) {
        log.info("[{}] Received ResolveRequestEvent event: {}", event.requestToSatisfy().getId(), event);
        BackgroundEVRequest request = event.requestToSatisfy();

        log.info("[{}] Start processing event, charger allocation in progress", event.requestToSatisfy().getId());
        Charger allocatedCharger = allocateChargerToRequest(request.getHubId(), request.getChargerType(), request.getMaxVehiclePowerKw());

        startTrackingRequestProgression(request.getId(), request.getStartTimeSeconds(), request.getStartSoc(), request.getHubId());

        long delayInQueue = 0;
        if (allocatedCharger == null) {
            log.info("[{}] No charger available, start freeing process", event.requestToSatisfy().getId());
            TrackedEVRequest satisfiedRequest = trackedRequests.get(request.getHubId()).stream()
                    .filter(r -> r.getEndCharging() > 0)
                    .min(Comparator.comparingLong(TrackedEVRequest::getEndCharging))
                    .orElse(null);

            writeToCsv(satisfiedRequest);
            trackedRequests.get(request.getHubId()).remove(satisfiedRequest);

            log.debug("[{}] Freeing charger from request: {}", event.requestToSatisfy().getId(), satisfiedRequest);
            log.debug("[{}] Previous request end charging {}, new request arrival time {}", event.requestToSatisfy().getId(), satisfiedRequest.getEndCharging(), request.getStartTimeSeconds());
            if (satisfiedRequest != null) {
                hubManagementService.freeCharger(new ChargingEndedEvent(satisfiedRequest.getHubId(), satisfiedRequest.getChargerId()));
                allocatedCharger = allocateChargerToRequest(
                        request.getHubId(),
                        request.getChargerType(),
                        request.getMaxVehiclePowerKw());
                delayInQueue = satisfiedRequest.getEndCharging() + 1 - request.getStartTimeSeconds();
            }
            executeChargingProcess(request, allocatedCharger, delayInQueue > 0 ? delayInQueue : 0);
        } else {
            log.info("[{}] Request assigned to charger {}", event.requestToSatisfy().getId(), allocatedCharger);
            executeChargingProcess(request, allocatedCharger, 0);
        }
    }

    private Charger allocateChargerToRequest(String hubId, String chargerType, double maxVehiclePowerKw) {
        return hubManagementService.assignCharger(hubId, chargerType, maxVehiclePowerKw);
    }

    private void executeChargingProcess(BackgroundEVRequest request, Charger charger, long delayInQueue) {
        log.info("[{}] Generating charging table at {} with delayed time {}", request.getId(), request.getStartTimeSeconds() + delayInQueue, delayInQueue);

        addChargerToTrackedRequest(charger.getHubId(), request.getId(), charger.getChargerId(), charger.getType());

        List<Pair<Long, Double>> chargingTable = chargingModelService.computeChargingTable(
                request.getVehicleCapacityKwh(),
                request.getMaxVehiclePowerKw(),
                charger.getInfrastructurePowerKw(),
                request.getStartSoc(),
                request.getTargetSoc(),
                request.getStartTimeSeconds() + delayInQueue);

        if (chargingTable.isEmpty()) {
            log.warn("[{}] Request is invalid, skipping", request.getId());
            return;
        }

        log.info("[{}] Start scheduling power usage update: {}", request.getId(), chargingTable);

        addStartChargingTimeToTrackedRequest(charger.getHubId(), request.getId(), chargingTable.getFirst().getKey());
        addEndChargingTimeToTrackedRequest(charger.getHubId(), request.getId(), chargingTable.getLast().getKey());
    }

    // -----------------------------------------------------------------

    private void startTrackingRequestProgression(UUID requestId, long startTime, double initialSoc, String hubId) {
        trackedRequests
                .computeIfAbsent(hubId, v -> new HashSet<>())
                .add(new TrackedEVRequest(requestId, startTime, initialSoc, hubId));
    }

    private void addChargerToTrackedRequest(String hubId, UUID requestId, String chargerId, String plugType) {
        trackedRequests.computeIfPresent(hubId, (key, trackedRequestList) -> {
            trackedRequestList.stream()
                    .filter(r -> r.getRequestId().equals(requestId))
                    .findFirst()
                    .ifPresent(r -> r.setChargerData(chargerId, plugType));

            return trackedRequestList;
        });
    }

    private void addStartChargingTimeToTrackedRequest(String hubId, UUID requestId, long startCharging) {
        trackedRequests.computeIfPresent(hubId, (key, trackedRequestList) -> {
            trackedRequestList.stream()
                    .filter(r -> r.getRequestId().equals(requestId))
                    .findFirst()
                    .ifPresent(r -> r.setStartChargingAndWaitTime(
                            startCharging,
                            startCharging - r.getArrivalTime()
                    ));

            return trackedRequestList;
        });
    }

    private void addEndChargingTimeToTrackedRequest(String hubId, UUID requestId, long endCharging) {
        trackedRequests.computeIfPresent(hubId, (key, trackedRequestList) -> {
            trackedRequestList.stream()
                    .filter(r -> r.getRequestId().equals(requestId))
                    .findFirst()
                    .ifPresent(r -> r.setChargeEndAndDuration(
                            endCharging,
                            endCharging - r.getStartCharging()
                    ));

            return trackedRequestList;
        });
    }

    private synchronized void writeToCsv(TrackedEVRequest r) {
        try {
            Path path = Paths.get(props.getExecutionOutput());
            Files.createDirectories(path.getParent());

            boolean fileExists = Files.exists(path);

            try (BufferedWriter w = Files.newBufferedWriter(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {

                if (!fileExists) {
                    w.write("request_id,arrivalTime,start_charging,wait_time,charge_duration,end_charging,soc_arrival,hubId,chargerId,plugType,arrivalTimeFormatted,startChargingFormatted,endChargingFormatted");
                    w.newLine();
                }

                w.write(String.join(",",
                        String.valueOf(r.getRequestId()),
                        String.valueOf(r.getArrivalTime()),
                        String.valueOf(r.getStartCharging()),
                        String.valueOf(r.getWaitTime()),
                        String.valueOf(r.getChargeDuration()),
                        String.valueOf(r.getEndCharging()),
                        String.valueOf(r.getSocArrival()),
                        safe(r.getHubId()),
                        safe(r.getChargerId()),
                        safe(r.getPlugType()),
                        safe(r.getArrivalTimeFormatted()),
                        safe(r.getStartChargingFormatted()),
                        safe(r.getEndChargingFormatted())
                ));

                w.newLine();

            }

        } catch (Exception e) {
            log.error("CSV error", e);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

}
