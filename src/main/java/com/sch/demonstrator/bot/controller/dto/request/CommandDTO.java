package com.sch.demonstrator.bot.controller.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@ToString
public class CommandDTO {

    @NotBlank(message = "ChargerId is required")
    private String chargerId;

    @NotNull(message = "isActive is required")
    private Boolean isActive;
}
