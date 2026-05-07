package com.sch.demonstrator.bot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString
public class BackgroundEVRequest extends EVRequest {
    private double vehicleCapacityKwh;
    private double targetSoc;
    private double expectedChargerPower;

    // Debug
    private int hour;
    private String formattedTime;

    @Override
    public String getRequestType() {
        return "Background";
    }
}
