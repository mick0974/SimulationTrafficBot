package com.sch.demonstrator.bot.event;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record ChargingProgressEvent(
    String hubId,
    String chargerId,
    double newPowerKw
) { }
