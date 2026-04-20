package com.sch.demonstrator.bot.websocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sch.demonstrator.bot.websocket.dto.WebSocketUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SimulationWebSocketPublisher {

    private final SimulationWebSocketHandler wsHandler;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void publishChargerStatuses(WebSocketUpdate message) {
        if (message == null) {
            return;
        }

        try {
            wsHandler.publish(gson.toJson(message));
        } catch (Exception e) {
            log.error("Error sending hubs update", e);
        }
    }
}
