package com.sch.demonstrator.bot.service.processing;

import com.sch.demonstrator.bot.model.BackgroundEVRequest;
import com.sch.demonstrator.bot.model.Charger;
import com.sch.demonstrator.bot.model.EVRequest;
import com.sch.demonstrator.bot.service.tracking.RequestTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiConsumer;

@Component
@Slf4j
public class QueueManager {

    private final QueueBehaviorService queueBehaviorService;
    private final HubManagementService hubManagementService;
    private final RequestTracker tracker;
    private final TaskScheduler taskScheduler;

    private final Map<String, Deque<EVRequest>> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> queueLocks = new ConcurrentHashMap<>();

    public QueueManager(
            QueueBehaviorService queueBehaviorService,
            HubManagementService hubManagementService,
            RequestTracker tracker,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler
    ) {
        this.queueBehaviorService = queueBehaviorService;
        this.hubManagementService = hubManagementService;
        this.tracker = tracker;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Tenta di accodare la richiesta nell'hub.
     * Applica il modello di balking: se il veicolo decide di non aspettare,
     * non viene aggiunto alla coda e il metodo ritorna false.
     *
     * @return true se il veicolo è stato accodato, false se abbandona
     */
    public boolean tryEnqueueing(BackgroundEVRequest request) {
        double startSoc = request.getStartSoc();
        long arrivalTimeInternal = tracker.getArrivalTimeInternal(request.getId());
        int currentSize = getHubQueueSize(request.getHubId());

        boolean entersQueue = queueBehaviorService.joinQueue(currentSize, startSoc, arrivalTimeInternal);
        long maxWaitSec  = 0;

        if (entersQueue) {
            maxWaitSec = queueBehaviorService.getMaxQueueingTime(startSoc, arrivalTimeInternal);
            scheduleQueueAbandonment(request, maxWaitSec);
        }

        tracker.setQueueDecision(request.getId(), entersQueue, maxWaitSec);
        log.info("[{}] Queue decision: enters={} maxWait={}s queueSize={}",
                request.getLogId(), entersQueue, maxWaitSec, currentSize);

        if (!entersQueue)
            return false;

        pendingRequests
                .computeIfAbsent(request.getHubId(), k -> new ConcurrentLinkedDeque<>())
                .offer(request);

        log.info("[{}] Enqueued at hub {} (size with new request = {})", request.getId(), request.getHubId(), currentSize + 1);
        return true;
    }

    /** Accoda senza valutazione statistica (per le richieste Adas). */
    public void enqueueForced(EVRequest request) {
        pendingRequests
                .computeIfAbsent(request.getHubId(), k -> new ConcurrentLinkedDeque<>())
                .offer(request);
        log.info("[{}] Force-enqueued at hub {} (size={})",
                request.getLogId(), request.getHubId(), getHubQueueSize(request.getHubId()));
    }

    public void dryQueue(String hubId, BiConsumer<EVRequest, Charger> onChargerAssigned) {
        synchronized (getLock(hubId)) {
            log.debug("Checking if hub {} has pending requests", hubId);
            if (!hasPendingRequests(hubId)) {
                log.info("Hub {} has no requests in queue", hubId);
                return;
            }

            Deque<EVRequest> hubRequestInQueue = pendingRequests.get(hubId);
            log.info("Requests in queue: {}, start drying: {}", hubRequestInQueue.size(), hubRequestInQueue);
            EVRequest request;

            while ((request = hubRequestInQueue.pollFirst()) != null) {
                UUID requestId = request.getId();
                String logId = request.getLogId();

                log.debug("[{}] Trying to assigning charger to pending request {}", logId, request);
                Charger charger = allocateChargerToRequest(
                        request.getHubId(),
                        request.getChargerType(),
                        request.getMaxVehiclePowerKw());

                if (charger == null) {
                    log.debug("[{}] No charger found for pending request, stop drying queue", logId);
                    hubRequestInQueue.offerFirst(request); // reinserisco la richiesta in testa alla
                    log.info("Remaining request in queue: {}", hubRequestInQueue.size());
                    return;
                }

                log.debug("[{}] Charger {} assigned to request", logId, charger);
                tracker.setCharger(requestId, charger.getChargerId(), charger.getType());

                onChargerAssigned.accept(request, charger);
            }

            log.info("Remaining request in queue: {}", hubRequestInQueue.size());
        }

    }

    private void scheduleQueueAbandonment(BackgroundEVRequest request, long maxWaitSeconds) {
        Instant abandonAt = Instant.now().plusSeconds(maxWaitSeconds);
        taskScheduler.schedule(
                () -> removeFromQueue(request),
                abandonAt);
        log.debug("[{}] Abandonment scheduled at {}", request.getId(), abandonAt);
    }

    private boolean hasPendingRequests(String hubId) {
        Deque<EVRequest> hubRequestInQueue = pendingRequests.get(hubId);
        return hubRequestInQueue != null && !hubRequestInQueue.isEmpty();
    }

    public void removeFromQueue(EVRequest request) {
        synchronized (getLock(request.getHubId())) {
            log.info("[{}] Removing request from queue, max wait time reached", request.getLogId());
            if (pendingRequests.get(request.getHubId()).remove(request)) {
                tracker.markAbandoned(request.getId(), Instant.now().getEpochSecond());
                tracker.endTracking(request.getId());
            } else {
                log.info("[{}] Request no more in queue", request.getLogId());
            }
        }
    }

    public int getHubQueueSize(String hubId) {
        Deque<EVRequest> hubRequestsInQueue = pendingRequests.get(hubId);
        return hubRequestsInQueue != null ? hubRequestsInQueue.size() : 0;
    }

    private Charger allocateChargerToRequest(String hubId, String chargerType, double maxVehiclePowerKw) {
        return hubManagementService.assignCharger(hubId, chargerType, maxVehiclePowerKw);
    }

    private Object getLock(String hubId) {
        return queueLocks.computeIfAbsent(hubId, k -> new Object());
    }

}
