package com.sch.demonstrator.bot.service;

import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.controller.dto.response.ChargerAssigned;
import com.sch.demonstrator.bot.event.AssignChargerEvent;
import com.sch.demonstrator.bot.event.ChargerFreedEvent;
import com.sch.demonstrator.bot.event.ChargingEndedEvent;
import com.sch.demonstrator.bot.event.ChargingProgressEvent;
import com.sch.demonstrator.bot.model.AdasEVRequest;
import com.sch.demonstrator.bot.model.BackgroundEVRequest;
import com.sch.demonstrator.bot.model.Charger;
import com.sch.demonstrator.bot.model.EVRequest;
import com.sch.demonstrator.bot.service.exception.AdasRequestNotAssignedException;
import com.sch.demonstrator.bot.service.processing.ChargingModelService;
import com.sch.demonstrator.bot.service.processing.DriverBehaviorService;
import com.sch.demonstrator.bot.service.processing.HubManagementService;
import com.sch.demonstrator.bot.service.processing.QueueManager;
import com.sch.demonstrator.bot.service.startup.EVRequestInitializer;
import com.sch.demonstrator.bot.service.tracking.RequestTracker;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@Slf4j
@Profile("prod")
public class ChargingManager {

    private final EVRequestInitializer evRequestInitializer;
    private final HubManagementService hubManagementService;
    private final ChargingModelService chargingModelService;
    private final QueueManager queueManager;
    private final RequestTracker tracker;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher eventPublisher;
    private final BotProperties props;

    private final Queue<BackgroundEVRequest> evRequests = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Charger> adasAssignedChargers = new ConcurrentHashMap<>();

    private final DateTimeFormatter formatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final DriverBehaviorService driverBehaviorService;

    private volatile Instant startTime;
    private long botTimeShift;

    public ChargingManager(
            EVRequestInitializer evRequestInitializer,
            HubManagementService hubManagementService,
            ChargingModelService chargingModelService,
            QueueManager queueManager,
            RequestTracker tracker,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            ApplicationEventPublisher eventPublisher,
            BotProperties props, DriverBehaviorService driverBehaviorService) {

        this.evRequestInitializer = evRequestInitializer;
        this.hubManagementService = hubManagementService;
        this.chargingModelService = chargingModelService;
        this.queueManager = queueManager;
        this.tracker = tracker;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.driverBehaviorService = driverBehaviorService;
    }

    @PostConstruct
    private void init() {
        Map<String, Map<String, Double>> hubStats = hubManagementService.getHubStats();
        evRequests.addAll(evRequestInitializer.generateRequests(hubStats));
        startTime = props.getStartTimeReal();
        botTimeShift = props.getStartTimeInternal();
    }

    // ----------------------------- ADAS REQUESTS HANDLING ----------------------------------------
    public void processAdasRequest(AdasEVRequest request) {
        tracker.start(
                "Adas", request.getRequestId(),
                Instant.now().getEpochSecond() - startTime.getEpochSecond(),
                request.getStartSoc(), request.getHubId(), request.getChargerType(), RequestTracker.TrackingType.REAL);

        processEVRequest(request);
    }

    public ChargerAssigned getAdasAssignedCharger(UUID requestId) {
        Charger charger = adasAssignedChargers.get(requestId);

        if (charger == null)
            throw new AdasRequestNotAssignedException("No connector has yet been assigned to request " + requestId);

        tracker.startCharging(requestId, Instant.now().getEpochSecond());

        return new ChargerAssigned(
                charger.getChargerId(),
                charger.getInfrastructurePowerKw()
        );
    }

    public void updateAssignedChargerPower(UUID requestId, double powerKw) {
        Charger charger = adasAssignedChargers.get(requestId);
        if (charger == null)
            throw new AdasRequestNotAssignedException("No charger found for request " + requestId);

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

        tracker.endCharging(requestId, Instant.now().getEpochSecond());
        tracker.endTracking(requestId);
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
        tracker.start("Background", request.getRequestId(), request.getStartTimeSeconds(), request.getStartSoc(),
                request.getHubId(), request.getChargerType(), RequestTracker.TrackingType.REAL);
        processEVRequest(request);
    }

    @EventListener(ChargerFreedEvent.class)
    protected void retryRequests(ChargerFreedEvent event) {
        log.info("A charger in hub {} has been freed, checking for pending requests...", event);
        String hubFreed = event.hubId();
        tryDryingHubQueue(hubFreed);
    }

    private void scheduleRequest(BackgroundEVRequest request) {
        log.info("[{}] Scheduling request", request.getRequestId());
        Instant now = startTime;
        Instant triggerAt = now.plusSeconds(request.getStartTimeSeconds()).minusSeconds(botTimeShift);
        taskScheduler.schedule(() -> eventPublisher.publishEvent(new AssignChargerEvent(request)), triggerAt);
        log.info("Request scheduled");
    }

    // -----------------------------------------------------------------

    private void processEVRequest(EVRequest request) {
        String logId = request.getLogId();
        UUID requestId = request.getRequestId();
        String hubId = request.getHubId();
        log.info("[{}] Received request from vehicle", logId);

        queueManager.dryQueue(hubId, this::onChargerAssigned);

        log.info("[{}] Start processing request, charger allocation in progress", logId);
        Charger charger = allocateChargerToRequest(
                request.getRequestType(),
                request.getHubId(),
                request.getChargerType(),
                request.getMaxVehiclePowerKw());

        // Aggiorno se la richiesta ha incontrato coda (non può essere soddisfatta immediatamente)
        tracker.foundQueue(requestId, charger == null);

        if (charger != null) {
            log.info("[{}] Request assigned to charger {}", logId, charger);
            onChargerAssigned(request, charger);
            return;
        }

        log.info("[{}] No charger available, evaluating queue entry", logId);

        if (request instanceof BackgroundEVRequest bgRequest) {
            boolean enteredQueue = queueManager.tryEnqueueing(bgRequest);
            if (!enteredQueue) {
                tracker.endTracking(requestId);  // La richiesta di background non si accoda
            }
        } else {
            queueManager.enqueueForced(request); // La richiesta Adas si accoda sempre
        }

    }

    private void onChargerAssigned(EVRequest request, Charger charger) {
        tracker.setCharger(request.getRequestId(), charger.getChargerId(), charger.getType());

        if (request instanceof BackgroundEVRequest bgRequest) {
            scheduleChargingProcess(bgRequest, charger);
        } else {
            adasAssignedChargers.put(request.getRequestId(), charger);
        }
    }

    private void tryDryingHubQueue(String hubId) {
        queueManager.dryQueue(hubId, this::onChargerAssigned);
    }

    private Charger allocateChargerToRequest(String requestType, String hubId, String chargerType, double maxVehiclePowerKw) {
        if (requestType.equalsIgnoreCase("Adas"))
            return hubManagementService.assignChargerAdas(hubId, chargerType, maxVehiclePowerKw);
        else
            return hubManagementService.assignChargerBackground(hubId, chargerType, maxVehiclePowerKw);
    }

    private void scheduleChargingProcess(BackgroundEVRequest request, Charger charger) {
        Instant now = Instant.now();
        log.info("[{}] Generating charging table at {}", request.getRequestId(), formatter.format(now));

        List<Pair<Long, Double>> chargingTable = chargingModelService.computeChargingTable(
                request.getVehicleCapacityKwh(),
                request.getMaxVehiclePowerKw(),
                charger.getInfrastructurePowerKw(),
                request.getStartSoc(),
                request.getTargetSoc(),
                now.getEpochSecond());

        if (chargingTable.isEmpty()) {
            log.warn("[{}] Request is invalid, skipping", request.getRequestId());
            return;
        }

        if (assignedChargerIsDegraded(request.getChargerType(), charger.getType())) {
            chargingTable = updateChargingTable(chargingTable, request, charger);
        }

        log.debug("[{}] Start charging at {} (real time), end charging at {} (real time), total charge time {} seconds ({} min)",
                request.getRequestId(),
                formatter.format(now.plusSeconds(chargingTable.getFirst().getKey())),
                formatter.format(now.plusSeconds(chargingTable.getLast().getKey())),
                chargingTable.getLast().getKey() - chargingTable.getFirst().getKey(),
                (chargingTable.getLast().getKey() - chargingTable.getFirst().getKey()) / 60.0
        );

        log.info("[{}] Start scheduling power usage update", request.getRequestId());
        for (Pair<Long, Double> pair : chargingTable) {
            Instant scheduleAt = now.plusSeconds(pair.getKey());
            log.info("[{}] Scheduling power usage {} at {}", request.getRequestId(), pair.getValue(), formatter.format(scheduleAt));
            taskScheduler.schedule(() ->
                            eventPublisher.publishEvent(
                                    new ChargingProgressEvent(charger.getHubId(), charger.getChargerId(), pair.getValue())),
                    scheduleAt);
        }

        // Aggiorno il tracking della richiesta
        tracker.startCharging(request.getRequestId(), chargingTable.getFirst().getKey());
        tracker.endCharging(request.getRequestId(), chargingTable.getLast().getKey());
        tracker.endTracking(request.getRequestId());

        // Ritarda la liberazione del connettore di 5 secondi dopo l'ultima erogazione
        taskScheduler.schedule(() ->
                        eventPublisher.publishEvent(new ChargingEndedEvent(
                                charger.getHubId(), charger.getChargerId())),
                now.plusSeconds(chargingTable.getLast().getKey() + 5));
        log.debug("[{}] Scheduling ChargingEndedEvent event for hub {} at {}",
                request.getRequestId(),
                charger.getHubId(), formatter.format(now.plusSeconds(chargingTable.getLast().getKey() + 5)));

        log.info("[{}] Charging events scheduled successfully", request.getRequestId());
    }

    private boolean assignedChargerIsDegraded(String chargerRequested, String chargerAssigned) {
        return !chargerRequested.equals("AC") && chargerAssigned.equals("AC");
    }

    private List<Pair<Long, Double>> updateChargingTable(List<Pair<Long, Double>> chargingTable,
            BackgroundEVRequest request, Charger allocatedCharger) {

        long startTime = chargingTable.getFirst().getFirst();
        long maxAcceptedChargeTime =
                driverBehaviorService.computeMaxChargingTimeWithDegradedCharger(
                        request.getHour(),
                        chargingTable.getLast().getFirst() - startTime,
                        allocatedCharger.getInfrastructurePowerKw(),
                        request.getExpectedChargerPower()
                );

        return chargingTable.stream()
                .takeWhile(p -> (p.getFirst() - startTime) <= maxAcceptedChargeTime)
                .toList();
    }
}