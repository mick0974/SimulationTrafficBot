package com.sch.demonstrator.bot.event;

public record ChargingEndedEvent (
        String hubId,
        String chargerId
) { }
