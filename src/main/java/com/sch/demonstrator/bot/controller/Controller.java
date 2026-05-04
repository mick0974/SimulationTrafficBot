package com.sch.demonstrator.bot.controller;

import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.controller.dto.request.ChargerRequestDTO;
import com.sch.demonstrator.bot.controller.dto.request.ChargingProgressDTO;
import com.sch.demonstrator.bot.controller.dto.request.CommandDTO;
import com.sch.demonstrator.bot.controller.dto.response.ChargerAssigned;
import com.sch.demonstrator.bot.controller.dto.response.ChargerRequestAccepted;
import com.sch.demonstrator.bot.model.AdasEVRequest;
import com.sch.demonstrator.bot.service.ChargingManager;
import com.sch.demonstrator.bot.service.processing.HubManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Profile({"prod", "debug"})
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class Controller {

    private final HubManagementService hubManagementService;
    private final ChargingManager chargingManager;
    private final BotProperties props;

    // #################################################
    // Endpoint HubZone
    // #################################################

    @PostMapping("/ChargerState")
    public ResponseEntity<Void> setChargerState(@Valid @RequestBody CommandDTO commandDTO) {
        log.info("[POST /ChargerState] received request: {}", commandDTO);
        if (commandDTO.getIsActive())
            hubManagementService.activateCharger(commandDTO.getChargerId());
        else
            hubManagementService.deactivateCharger(commandDTO.getChargerId());

        return ResponseEntity.ok().build();
    }

    // #################################################
    // Endpoint AdasUI
    // #################################################

    @Operation(
            summary = "Richiede l'assegnazione di un connettore per un veicolo gestito tramite AdasUI.",
            description = "Invia una richiesta di assegnazione connettore per un veicolo presso un hub specifico. " +
                    "La determinazione e assegnazione avviene asincronamente rispetto alla richiesta."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Richiesta accettata. La risposta contiene l'identificativo " +
                    "univoco assegnato alla richiesta",
                    content = @Content(schema = @Schema(implementation = ChargerRequestAccepted.class))),
    })
    @PostMapping("/adas/hub/{hubId}/assign-charger")
    public ResponseEntity<ChargerRequestAccepted> assignCharger(@PathVariable String hubId, @Valid @RequestBody ChargerRequestDTO dto) {
        log.info("[POST /adas/hub/{}/assign-charger] Received request to assign charger", hubId);
        UUID uuid = UUID.randomUUID();
        long currentInternalTime = props.getStartTimeInternal() +
                Instant.now().getEpochSecond() - props.getStartTimeReal().getEpochSecond();
        AdasEVRequest request = AdasEVRequest.builder()
                .id(uuid)
                .hubId(hubId)
                .chargerType(dto.chargerType())
                .maxVehiclePowerKw(dto.maxVehiclePowerKw())
                .startSoc(dto.startSoc())
                .startTimeSeconds(currentInternalTime)
                .build();
        chargingManager.processAdasRequest(request);

        log.debug("[POST /adas/hub/{}/assign-charger] UUID assigned to request: {}", hubId, uuid);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ChargerRequestAccepted(uuid));
    }

    @Operation(
            summary = "Verifica se alla richiesta specificata è stato assegnato un connettore.",
            description = "Endpoint di polling per verificare se una richiesta è stata assegnata a un connettore."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connettore assegnato. La risposta contiene i dati del  " +
                    "connettore necessari per eseguire il modello di ricarica",
                    content = @Content(schema = @Schema(implementation = ChargerAssigned.class))),
            @ApiResponse(responseCode = "404", description = "Richiesta inesistente o non ancora assegnata")
    })
    @GetMapping("/adas/hub/check-assignment/{requestId}")
    public ResponseEntity<ChargerAssigned> assignCharger(@PathVariable UUID requestId) {
        log.info("[POST /adas/hub/check-assignment] Received polling to check if request {} has been processed", requestId);
        ChargerAssigned response = chargingManager.getAdasAssignedCharger(requestId);
        if (response == null) {
            log.debug("[POST /adas/hub/check-assignment] Request {} has yet to be assigned", requestId);
            return ResponseEntity.notFound().build();
        } else {
            log.debug("[POST /adas/hub/check-assignment] Request {} has been assigned to charger {} has been processed", requestId, response.chargerId());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/adas/hub/charge-vehicle/{requestId}")
    @Operation(
            summary = "Aggiorna la potenza erogata dal connettore assegnato alla richiesta specificata",
            description = "Endpoint che permette all'AdasUI di aggiornare nel tempo l'erogazione di potenza da parte del " +
                    "connettore assegnato secondo il modello di carica implementato"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Aggiornamento ricevuto"),
            @ApiResponse(responseCode = "400", description = "Richiesta inesistente o non ancora assegnata")
    })
    public ResponseEntity<Void> chargeVehicle(@PathVariable UUID requestId, @Valid @RequestBody ChargingProgressDTO dto) {
        log.info("[POST /adas/hub/check-assignment] Received charging progress for request {} with {} KW", requestId, dto.getPowerKw());
        chargingManager.updateAssignedChargerPower(requestId, dto.getPowerKw());
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Libera il connettore assegnato alla richiesta specificata",
            description = "Endpoint che permette all'AdasUI di informare il sistema che la ricarica del veicolo è terminata " +
                    "e il connettore può essere liberato"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connettore liberato"),
            @ApiResponse(responseCode = "400", description = "Richiesta inesistente o non ancora assegnata")
    })
    @PostMapping("/adas/hub/free-charger/{requestId}")
    public ResponseEntity<Void> freeCharger(@PathVariable UUID requestId) {
        log.info("[POST /adas/hub/free-charger] Received request from {} to free charger", requestId);
        chargingManager.freeAssignedCharger(requestId);
        return ResponseEntity.ok().build();
    }
}
