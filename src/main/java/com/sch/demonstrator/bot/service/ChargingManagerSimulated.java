package com.sch.demonstrator.bot.service;

import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.event.AssignChargerEvent;
import com.sch.demonstrator.bot.event.ChargingEndedEvent;
import com.sch.demonstrator.bot.model.BackgroundEVRequest;
import com.sch.demonstrator.bot.model.Charger;
import com.sch.demonstrator.bot.model.EvRequestLifecycleSimulated;
import com.sch.demonstrator.bot.service.processing.ChargingModelService;
import com.sch.demonstrator.bot.service.processing.HubManagementService;
import com.sch.demonstrator.bot.service.processing.DriverBehaviorService;
import com.sch.demonstrator.bot.service.startup.EVRequestInitializer;
import com.sch.demonstrator.bot.service.tracking.RequestTracker;
import com.sch.demonstrator.bot.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.Pair;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@Slf4j
@Profile("dev")
@RequiredArgsConstructor
public class ChargingManagerSimulated {

    private final EVRequestInitializer evRequestInitializer;
    private final HubManagementService hubManagementService;
    private final DriverBehaviorService driverBehaviorService;
    private final ChargingModelService chargingModelService;
    private final RequestTracker tracker;
    private final BotProperties props;

    private final Queue<BackgroundEVRequest> evRequests = new ConcurrentLinkedQueue<>();
    private final Map<String, List<EvRequestLifecycleSimulated>> trackedRequests = new ConcurrentHashMap<>();

    @Getter
    private volatile Instant startTimeReal;
    @Getter
    private long startTimeInternal;

    @PostConstruct
    private void init() {
        Map<String, Map<String, Double>> hubStats = hubManagementService.getHubStats();
        evRequests.addAll(evRequestInitializer.generateRequests(hubStats));
        startTimeReal = props.getStartTimeReal();
        startTimeInternal = props.getStartTimeInternal();
    }


    @EventListener(ApplicationReadyEvent.class)
    private void executeBackgroundRequests() {
        log.info("=== Start RequestManager ===");
        log.info("RequestManager started with current internal time {}", Utils.formatTime(startTimeInternal));

        for (BackgroundEVRequest evRequest : evRequests) {
            chargeBackgroundVehicle(new AssignChargerEvent(evRequest));
        }

        System.exit(0);
    }

    protected void chargeBackgroundVehicle(AssignChargerEvent event) {
        log.info("[{}] Received ResolveRequestEvent event: {}", event.requestToSatisfy().getRequestId(), event);
        BackgroundEVRequest request = event.requestToSatisfy();
        UUID requestId = request.getRequestId();
        long arrivalTime = request.getStartTimeSeconds();
        String hubId = request.getHubId();
        String requestedChargerType = request.getChargerType();
        double soc = request.getStartSoc();

        log.info("[{}] Start processing event, charger allocation in progress", event.requestToSatisfy().getRequestId());

        tracker.start("Background", requestId, arrivalTime, soc, hubId,
                requestedChargerType, RequestTracker.TrackingType.SIMULATED);
        trackedRequests
                .computeIfAbsent(hubId, k -> new ArrayList<>())
                .add(tracker.getTrackedRequest(requestId));

        Charger allocatedCharger = allocateChargerToRequest(hubId, request.getChargerType(), request.getMaxVehiclePowerKw());

        long delayInQueue = 0;
        boolean entersQueue = false;
        long maxQueueWaitTime = 0;
        boolean abandonQueue = false;
        if (allocatedCharger == null) {
            log.info("[{}] No charger available, start freeing process", requestId);

            EvRequestLifecycleSimulated earliestCompleted = findEarliestCompleted(hubId);
            if (earliestCompleted == null) {
                log.error("[{}] No charger freed, stop handling current request", requestId);
                tracker.endTracking(requestId);
                return;
            }

            // Verifico se esiste coda
            boolean foundQueue = earliestCompleted.getEndChargingInternal() >= arrivalTime;
            tracker.foundQueue(requestId, foundQueue);

            if (foundQueue) {
                int hubQueueSize = getHubQueueSize(hubId, arrivalTime);
                log.info("[{}] Identified queue of {} requests for hub {}", requestId, hubQueueSize, hubId);
                entersQueue = driverBehaviorService.joinQueue(
                        hubQueueSize,
                        soc,
                        arrivalTime
                );

                log.info("[{}] Request enters in queue: {}", requestId, entersQueue);
                // Se la richiesta entra in coda determino il tempo massimo di attesa e se abbandona la coda
                if (entersQueue) {
                    maxQueueWaitTime = driverBehaviorService.getMaxQueueingTime(soc, arrivalTime);
                    abandonQueue = (arrivalTime + maxQueueWaitTime) < earliestCompleted.getEndChargingInternal();
                }

                log.info("[{}] Max waiting time in queue: {}", requestId, maxQueueWaitTime);
                tracker.setQueueDecision(requestId, entersQueue, maxQueueWaitTime);

                if (abandonQueue) {
                    log.info("[{}] Request abandons queue: {}", requestId, abandonQueue);
                    tracker.markAbandoned(requestId, arrivalTime + maxQueueWaitTime);
                }

                if (!entersQueue || abandonQueue) {
                    log.info("[{}] Request not entered/abandoned queue, stop tracking", requestId);
                    tracker.endTracking(requestId);
                    return;
                }
            } else {
                tracker.foundQueue(requestId, false);
                log.info("[{}] No queue identified for hub {}", requestId, hubId);
            }

            hubManagementService.freeCharger(new ChargingEndedEvent(hubId, earliestCompleted.getChargerId()));
            delayInQueue = Math.max(0, earliestCompleted.getEndChargingInternal() + 5 - request.getStartTimeSeconds());
            allocatedCharger = allocateChargerToRequest(hubId, request.getChargerType(), request.getMaxVehiclePowerKw());
            removeEarliestCompleted(hubId, earliestCompleted);
        } else {
            tracker.foundQueue(requestId, false);
        }

        executeChargingProcess(request, allocatedCharger, delayInQueue);
    }

    private Charger allocateChargerToRequest(String hubId, String chargerType, double maxVehiclePowerKw) {
        return hubManagementService.assignChargerBackground(hubId, chargerType, maxVehiclePowerKw);
    }

    private void executeChargingProcess(BackgroundEVRequest request, Charger charger, long delayInQueue) {
        log.info("[{}] Generating charging table at {} with delayed time {}", request.getRequestId(), request.getStartTimeSeconds() + delayInQueue, delayInQueue);

        UUID requestId = request.getRequestId();
        tracker.setCharger(request.getRequestId(), charger.getChargerId(), charger.getType());

        List<Pair<Long, Double>> chargingTable = chargingModelService.computeChargingTable(
                request.getVehicleCapacityKwh(),
                request.getMaxVehiclePowerKw(),
                charger.getInfrastructurePowerKw(),
                request.getStartSoc(),
                request.getTargetSoc(),
                request.getStartTimeSeconds() + delayInQueue);

        if (chargingTable.isEmpty()) {
            log.warn("[{}] Charging table not generated, skipping request", request.getRequestId());
            return;
        }

        if (assignedChargerIsDegraded(request.getChargerType(), charger.getType())) {
            chargingTable = updateChargingTable(chargingTable, request, charger);
        }

        log.info("[{}] Start scheduling power usage update: {}", request.getRequestId(), chargingTable);

        tracker.startCharging(requestId, chargingTable.getFirst().getKey());
        tracker.endCharging(requestId, chargingTable.getLast().getKey());
        tracker.endTracking(request.getRequestId());
    }

    private int getHubQueueSize(String hubId, long requestArrivalTime) {
        List<EvRequestLifecycleSimulated> completed = trackedRequests.getOrDefault(hubId, new ArrayList<>());

        long occupiedChargers = completed.stream()
                .filter(r -> {
                    Long end = r.getEndChargingInternal();
                    Long arrival = r.getArrivalTimeInternal();
                    Long maxWait = r.getMaxQueueTimeAccepted();
                    Boolean abandoned = r.getAbandonedQueue();

                    boolean stillCharging =
                            end != null &&
                                    end >= requestArrivalTime;

                    boolean stillInQueue =
                            arrival != null &&
                                    maxWait != null &&
                                    abandoned != null &&
                                    r.getEnteredQueue() &&
                                    !r.getAbandonedQueue() &&
                                    (arrival + maxWait) >= requestArrivalTime;

                    return stillCharging || stillInQueue;
                })
                .count();

        int hubChargers = hubManagementService.getCurrentHubStates().get(hubId).size();

        return (int) Math.max(0, occupiedChargers - hubChargers);
    }

    private EvRequestLifecycleSimulated findEarliestCompleted(String hubId) {
        log.info("[{}] Find earliest completed request: ", hubId);

        // Estraggo solo le richieste completate
        return trackedRequests.getOrDefault(hubId, new ArrayList<>())
                .stream()
                .filter(r -> r.getEndChargingInternal() != null)    // Estraggo solo le richieste completate
                .min(Comparator.comparing(EvRequestLifecycleSimulated::getEndChargingInternal))
                .orElse(null);
    }

    private void removeEarliestCompleted(String hubId, EvRequestLifecycleSimulated request) {
        if (request != null)
            log.info("[{}] Removed request [{}]: {} ", hubId, request.getRequestId(), request);

        trackedRequests.getOrDefault(hubId, new ArrayList<>()).remove(request);
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
