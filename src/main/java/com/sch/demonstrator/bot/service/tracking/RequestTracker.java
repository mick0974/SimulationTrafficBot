package com.sch.demonstrator.bot.service.tracking;

import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.model.EvRequestLifecycle;
import com.sch.demonstrator.bot.model.EvRequestLifecycleReal;
import com.sch.demonstrator.bot.model.EvRequestLifecycleSimulated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Gestisce il tracking delle EVRequest dalla creazione alla scrittura su file.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RequestTracker {

    public enum TrackingType {REAL, SIMULATED}

    private static final String CSV_HEADER =
            "request_type,request_id,hub_id,soc_arrival,arrival_time_internal,requested_plug_type," +
                    "found_queue,entered_queue,abandoned_queue,max_queue_time_accepted," +
                    "charger_id,assigned_plug_type," +
                    "start_charging_internal,wait_time_before_charging,charge_duration,end_charging_internal," +
                    "ended_charging_earlier," +
                    "arrival_time_formatted,abandoned_queue_at_formatted,start_charging_formatted,end_charging_formatted," +
                    "wait_time_hour_formatted,charge_duration_hour_formatted";

    private final BotProperties props;
    private final Map<UUID, EvRequestLifecycle> tracked = new ConcurrentHashMap<>();

    public EvRequestLifecycleSimulated getTrackedRequest(UUID requestId) {
        return (EvRequestLifecycleSimulated) tracked.get(requestId);
    }

    public void start(String type, UUID id, long arrivalTime, double soc, String hubId, String requestedChargerType, TrackingType trackingType) {
        tracked.computeIfAbsent(id, k -> {
            if (trackingType.equals(TrackingType.REAL))
                return new EvRequestLifecycleReal(type, id, arrivalTime, soc, hubId, requestedChargerType);
            else
                return new EvRequestLifecycleSimulated(type, id, arrivalTime, soc, hubId, requestedChargerType);
        });
    }

    public void foundQueue(UUID id, boolean foundQueue) {
        update(id, r -> r.foundQueue(foundQueue));
    }

    public void setQueueDecision(UUID id, boolean entersQueue, long maxWaitSeconds) {
        update(id, r -> r.setEntersQueueAndMaxWaitTime(entersQueue, maxWaitSeconds));
    }

    public void markAbandoned(UUID id, long abandonedQueueAt) {
        update(id, r -> r.vehicleAbandonedQueue(abandonedQueueAt));
    }

    public void setCharger(UUID id, String chargerId, String plugType) {
        update(id, r -> r.setChargerData(chargerId, plugType));
    }

    public void startCharging(UUID id, long epochSecond) {
        update(id, r -> r.setStartChargingAndWaitTime(epochSecond));
    }

    public void endCharging(UUID id, long epochSecond) {
        update(id, r -> r.setChargeEndAndDuration(epochSecond));
    }

    /** Rimuove il record dal tracking e lo scrive su CSV. */
    public void endTracking(UUID id) {
        EvRequestLifecycle r = tracked.remove(id);
        if (r != null)
            writeToCsv(r);
    }

    public long getArrivalTimeInternal(UUID id) {
        EvRequestLifecycle r = tracked.get(id);
        if (r == null)
            throw new IllegalStateException("No tracked request for id " + id);
        return r.getArrivalTimeInternal();
    }

    private synchronized void writeToCsv(EvRequestLifecycle r) {
        try {
            Path path = Paths.get(props.getExecutionOutput());
            Files.createDirectories(path.getParent());
            boolean fileExists = Files.exists(path);

            try (BufferedWriter w = Files.newBufferedWriter(
                    path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                if (!fileExists) {
                    w.write(CSV_HEADER);
                    w.newLine();
                }

                w.write(buildCsvRow(r));
                w.newLine();
            }

        } catch (Exception e) {
            log.error("Failed to write CSV for request {}", r.getRequestId(), e);
        }
    }

    private String buildCsvRow(EvRequestLifecycle r) {
        return String.join(",",
                safe(String.valueOf(r.getRequestType())),
                safe(String.valueOf(r.getRequestId())),
                safe(r.getHubId()),
                safe(String.valueOf(r.getSocArrival())),
                safe(String.valueOf(r.getArrivalTimeInternal())),
                safe(r.getRequestedChargerType()),

                safe(String.valueOf(r.getFoundQueue())),
                safe(String.valueOf(r.getEnteredQueue())),
                safe(String.valueOf(r.getAbandonedQueue())),
                safe(String.valueOf(r.getMaxQueueTimeAccepted())),

                safe(r.getChargerId()),
                safe(r.getAssignedChargerType()),

                safe(String.valueOf(r.getStartChargingInternal())),
                safe(String.valueOf(r.getWaitTimeBeforeCharging())),
                safe(String.valueOf(r.getChargeDuration())),
                safe(String.valueOf(r.getEndChargingInternal())),

                safe(r instanceof EvRequestLifecycleSimulated simulated
                        ? String.valueOf(simulated.getEndedChargingEarlier())
                        : ""),

                safe(r.getArrivalTimeFormatted()),
                safe(r.getAbandonedQueueAtFormatted()),
                safe(r.getStartChargingFormatted()),
                safe(r.getEndChargingFormatted()),
                safe(r.getWaitTimeHourFormatted()),
                safe(r.getChargeDurationHourFormatted())
        );
    }

    private void update(UUID id, Consumer<EvRequestLifecycle> operation) {
        tracked.computeIfPresent(id, (k, r) -> {
            operation.accept(r);
            return r;
        });
    }

    private String safe(String s) { return s != null && !s.equals("null") ? s : ""; }
}