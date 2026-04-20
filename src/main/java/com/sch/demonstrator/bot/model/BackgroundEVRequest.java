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
public class BackgroundEVRequest extends EVRequest {
    private UUID id;
    private double vehicleCapacityKwh;
    private double startSoc;
    private double targetSoc;
    private long startTimeSeconds;

    // Debug
    private int hour;
    private String formattedTime;
}
