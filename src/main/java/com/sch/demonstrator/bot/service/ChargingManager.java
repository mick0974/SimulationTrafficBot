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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
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
            BotProperties props) {

        this.evRequestInitializer = evRequestInitializer;
        this.hubManagementService = hubManagementService;
        this.chargingModelService = chargingModelService;
        this.queueManager = queueManager;
        this.tracker = tracker;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    @PostConstruct
    private void init() {
        String[] hubIds = hubManagementService.getHubIds();
        evRequests.addAll(evRequestInitializer.generateRequests(hubIds));
        startTime = props.getStartTimeReal();
        botTimeShift = props.getStartTimeInternal();
    }

    // ----------------------------- ADAS REQUESTS HANDLING ----------------------------------------
    public void processAdasRequest(AdasEVRequest request) {
        tracker.start(
                "Adas", request.getId(),
                Instant.now().getEpochSecond() - startTime.getEpochSecond(),
                request.getStartSoc(), request.getHubId(), RequestTracker.TrackingType.REAL);

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
        tracker.start("Background", request.getId(), request.getStartTimeSeconds(), request.getStartSoc(), request.getHubId(), RequestTracker.TrackingType.REAL);
        processEVRequest(request);
    }

    @EventListener(ChargerFreedEvent.class)
    protected void retryRequests(ChargerFreedEvent event) {
        log.info("A charger in hub {} has been freed, checking for pending requests...", event);
        String hubFreed = event.hubId();
        tryDryingHubQueue(hubFreed);
    }

    private void scheduleRequest(BackgroundEVRequest request) {
        log.info("[{}] Scheduling request", request.getId());
        Instant now = startTime;
        Instant triggerAt = now.plusSeconds(request.getStartTimeSeconds()).minusSeconds(botTimeShift);
        taskScheduler.schedule(() -> eventPublisher.publishEvent(new AssignChargerEvent(request)), triggerAt);
        log.info("Request scheduled");
    }

    // -----------------------------------------------------------------

    private void processEVRequest(EVRequest request) {
        String logId = request.getLogId();
        UUID requestId = request.getId();
        String hubId = request.getHubId();
        log.info("[{}] Received request from vehicle", logId);

        queueManager.dryQueue(hubId, this::onChargerAssigned);

        log.info("[{}] Start processing request, charger allocation in progress", logId);
        Charger charger = allocateChargerToRequest(
                request.getHubId(),
                request.getChargerType(),
                request.getMaxVehiclePowerKw());

        // Aggiorno se la richiesta ha incontrato coda
        tracker.foundQueue(requestId, charger != null);

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
        tracker.setCharger(request.getId(), charger.getChargerId(), charger.getType());

        if (request instanceof BackgroundEVRequest bgRequest) {
            scheduleChargingProcess(bgRequest, charger);
        } else {
            adasAssignedChargers.put(request.getId(), charger);
        }
    }

    private void tryDryingHubQueue(String hubId) {
        queueManager.dryQueue(hubId, this::onChargerAssigned);
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
        tracker.startCharging(request.getId(), chargingTable.getFirst().getKey().getEpochSecond());
        tracker.endCharging(request.getId(), chargingTable.getLast().getKey().getEpochSecond());
        tracker.endTracking(request.getId());

        // Ritarda la liberazione del connettore di 5 secondi dopo l'ultima erogazione
        taskScheduler.schedule(() ->
                        eventPublisher.publishEvent(new ChargingEndedEvent(
                                allocatedCharger.getHubId(), allocatedCharger.getChargerId())),
                chargingTable.getLast().getKey().plusSeconds(1));
        log.debug("[{}] Scheduling ChargingEndedEvent event for hub {} at {}",
                request.getId(),
                allocatedCharger.getHubId(), formatter.format(chargingTable.getLast().getKey().plusSeconds(5)));

        log.info("[{}] Charging events scheduled successfully", request.getId());
    }
}