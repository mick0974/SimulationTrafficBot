package com.sch.demonstrator.bot.event;

import com.sch.demonstrator.bot.model.BackgroundEVRequest;

public record AssignChargerEvent(
        BackgroundEVRequest requestToSatisfy
) {

}
