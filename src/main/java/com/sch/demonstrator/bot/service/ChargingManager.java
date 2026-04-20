package com.sch.demonstrator.bot.service;

import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.event.AssignChargerEvent;
import com.sch.demonstrator.bot.event.ChargerFreedEvent;
import com.sch.demonstrator.bot.event.ChargingEndedEvent;
import com.sch.demonstrator.bot.event.ChargingProgressEvent;
import com.sch.demonstrator.bot.controller.dto.response.ChargerAssigned;
import com.sch.demonstrator.bot.model.*;
import com.sch.demonstrator.bot.service.processing.ChargingModelService;
import com.sch.demonstrator.bot.service.processing.HubManagementService;
import com.sch.demonstrator.bot.service.exception.AdasRequestNotAssignedException;
import com.sch.demonstrator.bot.service.startup.EVRequestInitializer;
import com.sch.demonstrator.bot.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.Pair;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

@Component
@Slf4j
@Profile("prod")
public class ChargingManager {

    private final EVRequestInitializer evEVRequestInitializer;
    private final HubManagementService hubManagementService;
    private final ChargingModelService chargingModelService;

    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher eventPublisher;

    private final Queue<BackgroundEVRequest> evRequests = new ConcurrentLinkedQueue<>();
    private final Map<String, Deque<EVRequest>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Charger> adasAssignedChargers = new ConcurrentHashMap<>();
    private final Map<UUID, TrackedEVRequest> completedRequests = new ConcurrentHashMap<>();

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private volatile Instant startTime;
    private long botTimeShift;

    private final BotProperties props;

    public ChargingManager(EVRequestInitializer evEVRequestInitializer, HubManagementService hubManagementService, @Qualifier("taskScheduler") TaskScheduler taskScheduler, ApplicationEventPublisher eventPublisher, ChargingModelService chargingModelService, BotProperties props) {
        this.evEVRequestInitializer = evEVRequestInitializer;
        this.hubManagementService = hubManagementService;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
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

    // ----------------------------- ADAS REQUESTS HANDLING ----------------------------------------
    public void processAdasRequest(AdasEVRequest request) {
        startTrackingRequestProgression("Adas", request.getId(), Instant.now().getEpochSecond() - startTime.getEpochSecond(), -1.0, request.getHubId());
        processEVRequest(request, charger -> updateAdasRequestMap(request.getId(), charger));
    }

    public ChargerAssigned getAdasAssignedCharger(UUID requestId) {
        Charger charger = adasAssignedChargers.get(requestId);

        if (charger == null)
            throw new AdasRequestNotAssignedException("No connector has yet been assigned to request " + requestId);

        return new ChargerAssigned(
                charger.getChargerId(),
                charger.getInfrastructurePowerKw()
        );
    }

    public void updateAssignedChargerPower(UUID requestId, double powerKw) {
        Charger charger = adasAssignedChargers.get(requestId);
        if (charger == null)
            throw new AdasRequestNotAssignedException("No charger found for request " + requestId);

        addStartChargingTimeToTrackedRequest(requestId, Instant.now().getEpochSecond());
        hubManagementService.updatePowerOutputForCharger(
                new ChargingProgressEvent(
                        charger.getHubId(),
                        charger.getChargerId(),
                        powerKw
                ));
    }

    public void freeAssignedCharger(UUID requestId) {
        Charger charger = adasAssignedChargers.remove(requestId);
        if (charger == null)
            throw new AdasRequestNotAssignedException("No charger found for request " + requestId);

        addEndChargingTimeToTrackedRequest(requestId, Instant.now().getEpochSecond());
        logTrackedRequest(requestId);
        hubManagementService.freeCharger(new ChargingEndedEvent(charger.getHubId(), charger.getChargerId()));
    }

    // ----------------------------- BACKGROUND REQUESTS HANDLING ----------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    private void scheduleBackgroundRequests() {
        for (BackgroundEVRequest evRequest : evRequests) {
            scheduleRequest(evRequest);
        }

        log.info("=== Start RequestManager ===");
        log.info("RequestManager started with current internal time {}", Utils.formatTime(botTimeShift));
    }

    @EventListener(AssignChargerEvent.class)
    protected void chargeBackgroundVehicle(AssignChargerEvent event) {
        BackgroundEVRequest request = event.requestToSatisfy();
        startTrackingRequestProgression("Background", request.getId(), request.getStartTimeSeconds(), request.getStartSoc(), request.getHubId());
        processEVRequest(request, charger -> scheduleChargingProcess(request, charger));
    }

    @EventListener(ChargerFreedEvent.class)
    protected void retryRequests(ChargerFreedEvent event) {
        log.info("A charger in hub {} has been freed, checking for pending requests...", event);
        String hubFreed = event.hubId();
        if (hubHasPendingRequests(hubFreed)) {
            dryRetryQueue(pendingRequests.get(hubFreed));
        } else {
            log.info("No requests pending for freed hub {}", hubFreed);
        }
    }

    private void scheduleRequest(BackgroundEVRequest request) {
        log.info("[{}] Scheduling request", request.getId());
        Instant now = startTime;
        Instant triggerAt = now.plusSeconds(request.getStartTimeSeconds()).minusSeconds(botTimeShift);
        taskScheduler.schedule( () -> eventPublisher.publishEvent(new AssignChargerEvent(request)), triggerAt);
        log.info("Request scheduled");
    }

    // -----------------------------------------------------------------

    private void processEVRequest(EVRequest request, Consumer<Charger> handleAssignedRequest) {
        String prefix = request instanceof AdasEVRequest ? "Adas" : "Background";
        UUID requestId = request instanceof AdasEVRequest ? ((AdasEVRequest) request).getId() : ((BackgroundEVRequest) request).getId();
        log.info("[{} - {}] Received request from vehicle", prefix, requestId);

        log.debug("[{} - {}] Checking if hub {} has pending requests", prefix, requestId, request.getHubId());
        if (hubHasPendingRequests(request.getHubId())) {
            log.debug("[{} - {}] Hub {} has {} pending requests",
                    prefix, requestId, request.getHubId(), pendingRequests.size());
            dryRetryQueue(pendingRequests.get(request.getHubId()));
        }

        log.info("[{} - {}] Start processing request, charger allocation in progress", prefix, requestId);
        Charger charger = allocateChargerToRequest(
                request.getHubId(),
                request.getChargerType(),
                request.getMaxVehiclePowerKw());

        if (charger == null) {
            log.info("[{} - {}] No free charger found, adding request to retry-queue", prefix, requestId);
            pendingRequests
                    .computeIfAbsent(request.getHubId(), k -> new ConcurrentLinkedDeque<>())
                    .offer(request);
        } else {
            log.info("[{} - {}] Request assigned to charger {}", prefix, requestId, charger);
            addChargerToTrackedRequest(requestId, charger.getChargerId(), charger.getType());
            handleAssignedRequest.accept(charger);
        }
    }

    private boolean hubHasPendingRequests(String hubId) {
        Deque<EVRequest> hubRequestInQueue = pendingRequests.get(hubId);
        return hubRequestInQueue != null && !hubRequestInQueue.isEmpty();
    }

    private synchronized void dryRetryQueue(Deque<EVRequest> hubRequestInQueue) {
        log.info("Requests in queue: {}, start drying: {}", hubRequestInQueue.size(), hubRequestInQueue);
        EVRequest request;

        while ((request = hubRequestInQueue.pollFirst()) != null) {
            UUID requestId = request instanceof BackgroundEVRequest
                    ? ((BackgroundEVRequest) request).getId()
                    : ((AdasEVRequest) request).getId();

            String id = request instanceof BackgroundEVRequest
                    ? ((BackgroundEVRequest) request).getId().toString()
                    : "Adas - %s".formatted(((AdasEVRequest) request).getId());

            log.debug("[{}] Trying to assigning charger to pending request {}", id, request);
            Charger charger = allocateChargerToRequest(
                    request.getHubId(),
                    request.getChargerType(),
                    request.getMaxVehiclePowerKw());

            if (charger == null) {
                log.debug("[{}] No charger found for pending request, stop drying queue", id);
                hubRequestInQueue.offerFirst(request); // reinserisco la richiesta in testa alla
                log.info("Remaining request in queue: {}", hubRequestInQueue.size());
                return;
            }

            log.debug("[{}] Charger {} assigned to request", id, charger);
            addChargerToTrackedRequest(requestId, charger.getChargerId(), charger.getType());
            if (request instanceof BackgroundEVRequest) {
                scheduleChargingProcess((BackgroundEVRequest) request, charger);
            } else {
                updateAdasRequestMap(((AdasEVRequest) request).getId(), charger);
            }
        }

        log.info("Remaining request in queue: {}", hubRequestInQueue.size());
    }

    private Charger allocateChargerToRequest(String hubId, String chargerType, double maxVehiclePowerKw) {
        return hubManagementService.assignCharger(hubId, chargerType, maxVehiclePowerKw);
    }

    private void scheduleChargingProcess(BackgroundEVRequest request, Charger allocatedCharger) {
        Instant now = Instant.now();
        log.info("[{}] Generating charging table at {}", request.getId(), formatter.format(now));

        List<Pair<Instant, Double>> chargingTable = chargingModelService.computeChargingTable(
                request.getVehicleCapacityKwh(),
                request.getMaxVehiclePowerKw(),
                allocatedCharger.getInfrastructurePowerKw(),
                request.getStartSoc(),
                request.getTargetSoc(),
                now);

        if (chargingTable.isEmpty()) {
            log.warn("[{}] Request is invalid, skipping", request.getId());
            return;
        }

        log.debug("[{}] Start charging at {}, end charging at {}, total time seconds {}",
                request.getId(),
                formatter.format(chargingTable.getFirst().getKey()),
                formatter.format(chargingTable.getLast().getKey()),
                chargingTable.getLast().getKey().getEpochSecond() - chargingTable.getFirst().getKey().getEpochSecond());

        log.info("[{}] Start scheduling power usage update", request.getId());
        for (Pair<Instant, Double> pair : chargingTable) {
            log.info("[{}] Scheduling power usage {} at {}", request.getId(), pair.getValue(), formatter.format(pair.getKey()));
            taskScheduler.schedule(() ->
                            eventPublisher.publishEvent(
                                    new ChargingProgressEvent(allocatedCharger.getHubId(), allocatedCharger.getChargerId(), pair.getValue())),
                    pair.getKey());
        }

        // Aggiorno il tracking della richiesta
        addStartChargingTimeToTrackedRequest(request.getId(), chargingTable.getFirst().getKey().getEpochSecond());
        addEndChargingTimeToTrackedRequest(request.getId(), chargingTable.getLast().getKey().getEpochSecond());
        logTrackedRequest(request.getId());

        // Ritarda la liberazione del connettore di 1 secondi dopo l'ultima erogazione
        taskScheduler.schedule(() ->
                eventPublisher.publishEvent(new ChargingEndedEvent(
                        allocatedCharger.getHubId(), allocatedCharger.getChargerId())),
                chargingTable.getLast().getKey().plusSeconds(1));
        log.debug("[{}] Scheduling ChargingEndedEvent event for hub {} at {}",
                request.getId(),
                allocatedCharger.getHubId(), formatter.format(chargingTable.getLast().getKey().plusSeconds(1)));

        log.info("[{}] Charging events scheduled successfully", request.getId());
    }

    private void updateAdasRequestMap(UUID requestId, Charger charger) {
        adasAssignedChargers.put(requestId, charger);
    }

    // -----------------------------------------------------------------

    private void startTrackingRequestProgression(String requestType, UUID requestId, long startTime, double initialSoc, String hubId) {
        completedRequests.computeIfAbsent(requestId, k -> new TrackedEVRequest(requestType, requestId, startTime, initialSoc, hubId));
    }

    private void addChargerToTrackedRequest(UUID requestId, String chargerId, String plugType) {
        completedRequests.computeIfPresent(requestId, (key, trackedRequest) -> {
            trackedRequest.setChargerData(chargerId, plugType);
            return trackedRequest;
        });
    }

    private void addStartChargingTimeToTrackedRequest(UUID requestId, long startCharging) {
        completedRequests.computeIfPresent(requestId, (key, trackedRequest) -> {
            trackedRequest.setStartChargingAndWaitTime(startCharging);
            return trackedRequest;
        });
    }

    private void addEndChargingTimeToTrackedRequest(UUID requestId, long endCharging) {
        completedRequests.computeIfPresent(requestId, (key, trackedRequest) -> {
            trackedRequest.setChargeEndAndDuration(endCharging);
            return trackedRequest;
        });
    }

    private void logTrackedRequest(UUID requestId) {
        TrackedEVRequest request = completedRequests.remove(requestId);
        if (request == null)
            return;

        writeToCsv(request);
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
                    w.write("request_type,request_id,arrival_time,start_charging,wait_time,charge_duration,end_charging,soc_arrival,hub_id,charger_id,plug_type,arrivalTimeFormatted,startChargingFormatted,endChargingFormatted");
                    w.newLine();
                }

                w.write(String.join(",",
                        r.getSocArrival() == -1 ? "Adas" : "Background",
                        String.valueOf(r.getRequestId()),
                        String.valueOf(r.getArrivalTimeInternal()),
                        String.valueOf(r.getStartChargingInternal()),
                        String.valueOf(r.getWaitTime()),
                        String.valueOf(r.getChargeDuration()),
                        String.valueOf(r.getEndChargingInternal()),
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