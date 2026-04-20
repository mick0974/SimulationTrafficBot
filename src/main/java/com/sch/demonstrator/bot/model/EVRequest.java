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
public class EVRequest {
    private double maxVehiclePowerKw;
    private String chargerType;
    private String hubId;
}
