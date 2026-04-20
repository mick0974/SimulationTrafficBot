package com.sch.demonstrator.bot.service.processing;

import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.websocket.SimulationWebSocketPublisher;
import com.sch.demonstrator.bot.websocket.dto.WebSocketUpdate;
import com.sch.demonstrator.bot.websocket.dto.payload.ChargerStatus;
import com.sch.demonstrator.bot.websocket.dto.payload.HubStatusPayload;
import com.sch.demonstrator.bot.websocket.dto.payload.TimeStepPayload;
import com.sch.demonstrator.bot.model.Charger;
import com.sch.demonstrator.bot.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class HubStatePublisher {
    private final BotProperties botProperties;
    private final SimulationWebSocketPublisher simulationWebSocketPublisher;
    private final HubManagementService hubManagementService;

    @Scheduled(fixedRateString = "${request.websocket.publish-interval-seconds:45}000")
    public void publishChargerStatuses() {
        long snapshotTime =  (Instant.now().getEpochSecond() + botProperties.getBotTimeShift()) - botProperties.getStartTime().getEpochSecond();
        Map<String, Map<String, Charger>> hubs = hubManagementService.getCurrentHubStates();

        List<HubStatusPayload> payloads = new ArrayList<>();
        for (Map.Entry<String, Map<String, Charger>> entry : hubs.entrySet()) {
            String hubId = entry.getKey();
            Map<String, ChargerStatus> chargers = new HashMap<>();

            for (Map.Entry<String, Charger> chargerEntry : entry.getValue().entrySet()) {
                chargers.put(chargerEntry.getKey(), new ChargerStatus(
                        chargerEntry.getKey(),
                        chargerEntry.getValue().isOccupied(),
                        chargerEntry.getValue().isActive(),
                        chargerEntry.getValue().getChargingEnergy()
                ));
            }

            HubStatusPayload payload = new HubStatusPayload(
                hubId, chargers
            );

            payloads.add(payload);
        }

        log.info("Publishing charger statuses at {}, internal time {}", Timestamp.from(Instant.now()), Utils.formatTime(snapshotTime));

        WebSocketUpdate message = new WebSocketUpdate(
                "TimeStepUpdate",
                "success",
                new TimeStepPayload(
                        (double) snapshotTime,
                        Utils.formatTime(snapshotTime),
                        new LinkedList<>(),
                        payloads
                )
        );

        debugHubs(hubs);
        simulationWebSocketPublisher.publishChargerStatuses(message);
    }

    private void debugHubs(Map<String, Map<String, Charger>> hubs) {
        List<Charger> sortedChargers = hubs.values().stream()
                .flatMap(innerMap -> innerMap.values().stream())
                .sorted(Comparator.comparing(Charger::getChargerId))
                .toList();

        String state = sortedChargers.stream()
                .map(c -> String.format(
                        "%s | active=%s | occupancy=%s | power=%.2f",
                        c.getChargerId(),
                        c.isActive(),
                        c.isOccupied(),
                        c.getChargingEnergy()
                ))
                .collect(Collectors.joining("\n"));

        log.info(state);
    }


}
