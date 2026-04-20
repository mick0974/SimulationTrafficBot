package com.sch.demonstrator.bot.websocket.dto.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.ToString;

@Schema(description = "Stato di un singolo charger in un hub")
@AllArgsConstructor
@ToString
public class ChargerStatus {

    private String chargerId;
    private boolean occupied;
    private boolean active;

    /*
    *  valore cumulativo di energia distribuita
    */
    private double energy = 0.0;

    /*
    *  valore di energia istantanea che sta venendo erogata
    */
    private double charging_energy;

    @Schema(description = "ID del veicolo elettrico collegato (obbligatorio se occupied=true)")
    private String evId = null;

    public ChargerStatus(String chargerId, boolean occupied, boolean active, double chargingEnergy) {
        this.chargerId = chargerId;
        this.occupied = occupied;
        this.active = active;
        this.charging_energy = chargingEnergy;
    }
}
