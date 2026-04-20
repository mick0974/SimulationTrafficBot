package com.sch.demonstrator.bot.websocket.dto.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Map;

@Schema(description = "payload aggiornamento hub via WebSocket")
@ToString
public class HubStatusPayload {

    private String hubId;
    private double energy = 0.0;                          // energia totale consumata dall’hub
    private int occupancy = 0;                          // numero di veicoli in carica
    private Map<String, ChargerStatus> chargers;    // stato dei charger
    private ArrayList<Double> position = new ArrayList<>();             // [lat, lon] della posizione dell'hub

    public HubStatusPayload(String hubId, Map<String, ChargerStatus> chargers) {
        this.hubId = hubId;
        this.chargers = chargers;
    }
}
