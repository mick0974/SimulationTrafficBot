package com.sch.demonstrator.bot.websocket.dto.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.ToString;

import java.util.List;

@Schema(description = "payload aggiornamento hub e veicoli ad ogni timestep via WebSocket")
@AllArgsConstructor
@ToString
public class TimeStepPayload {

    private Double timestamp;

    private String formattedTime;

    @Schema(description = "List dei veicoli con i loro stati")
    private List<VehicleStatus> vehicles;

    @Schema(description = "List di hub con i loro stati")
    private List<HubStatusPayload> hubs;

}
