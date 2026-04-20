package com.sch.demonstrator.bot.websocket.dto.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;

@Schema(description = "payload aggiornamento vehicle con stato e link via WebSocket")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class VehicleStatus {

    private String vehicleId;             // riferimento al veicolo
    private double soc;                   // stato di carica in %
    private double kmDriven;              // chilometri percorsi
    private double currentEnergyJoules;   // energia corrente in Joule
    @JsonProperty("State")
    private String State;                  // stato del veicolo
    private ArrayList<Double> position;   // posizione del veicolo [x, y]
}
