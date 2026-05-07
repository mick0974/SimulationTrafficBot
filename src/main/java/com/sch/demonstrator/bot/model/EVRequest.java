package com.sch.demonstrator.bot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;


@Getter
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString
public abstract class EVRequest {
    private UUID requestId;
    private double startSoc;
    private long startTimeSeconds;
    private String chargerType;
    private String hubId;
    private double maxVehiclePowerKw;

    public abstract String getRequestType();

    public String getLogId() {
        return "%s - %s".formatted(getRequestType(), requestId);
    }
}
