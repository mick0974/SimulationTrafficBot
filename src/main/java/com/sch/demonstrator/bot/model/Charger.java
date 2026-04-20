package com.sch.demonstrator.bot.model;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@Getter
@Setter
@ToString
public class Charger {
    private String hubId;
    private String chargerId;
    private String type;
    private double infrastructurePowerKw;
    private double chargingEnergy;
    private boolean active;
    private boolean occupied;

}
