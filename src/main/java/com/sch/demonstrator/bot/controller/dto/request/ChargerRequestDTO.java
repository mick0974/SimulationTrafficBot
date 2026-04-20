package com.sch.demonstrator.bot.controller.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChargerRequestDTO(
        @NotBlank(message = "chargerType is required")
        String chargerType,
        //@NotBlank(message = "maxVehiclePowerKw is required")
        double maxVehiclePowerKw
) { }
