package com.sch.demonstrator.bot.controller;

import com.sch.demonstrator.bot.controller.dto.request.ChargerRequestDTO;
import com.sch.demonstrator.bot.controller.dto.request.ChargingProgressDTO;
import com.sch.demonstrator.bot.controller.dto.request.CommandDTO;
import com.sch.demonstrator.bot.controller.dto.response.ChargerAssigned;
import com.sch.demonstrator.bot.controller.dto.response.ChargerRequestAccepted;
import com.sch.demonstrator.bot.service.ChargingManager;
import com.sch.demonstrator.bot.model.AdasEVRequest;
import com.sch.demonstrator.bot.service.processing.HubManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Profile({"prod", "debug"})
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class Controller {

    private final HubManagementService hubManagementService;
    private final ChargingManager chargingManager;

    @PostMapping("/ChargerState")
    public ResponseEntity<Void> setChargerState(@Valid @RequestBody CommandDTO commandDTO) {
        log.info("[POST /ChargerState] received request: {}", commandDTO);
        if (commandDTO.getIsActive())
            hubManagementService.activateCharger(commandDTO.getChargerId());
        else
            hubManagementService.deactivateCharger(commandDTO.getChargerId());

        return ResponseEntity.ok().build();
    }

    @PostMapping("/adas/hub/{hubId}/assign-charger")
    public ResponseEntity<ChargerRequestAccepted> assignCharger(@PathVariable String hubId, @Valid @RequestBody ChargerRequestDTO dto) {
        log.info("[POST /adas/hub/{}/assign-charger] Received request to assign charger", hubId);
        UUID uuid = UUID.randomUUID();
        AdasEVRequest request = AdasEVRequest.builder()
                .id(uuid)
                .hubId(hubId)
                .chargerType(dto.chargerType())
                .maxVehiclePowerKw(dto.maxVehiclePowerKw())
                .build();
        chargingManager.processAdasRequest(request);

        log.debug("[POST /adas/hub/{}/assign-charger] UUID assigned to request: {}", hubId, uuid);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ChargerRequestAccepted(uuid));
    }

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
    public ResponseEntity<Void> chargeVehicle(@PathVariable UUID requestId, @Valid @RequestBody ChargingProgressDTO dto) {
        log.info("[POST /adas/hub/check-assignment] Received charging progress for request {} with {} KW", requestId, dto.getPowerKw());
        chargingManager.updateAssignedChargerPower(requestId, dto.getPowerKw());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/adas/hub/free-charger/{requestId}")
    public ResponseEntity<Void> freeCharger(@PathVariable UUID requestId) {
        log.info("[POST /adas/hub/free-charger] Received request from {} to free charger", requestId);
        chargingManager.freeAssignedCharger(requestId);
        return ResponseEntity.ok().build();
    }
}
