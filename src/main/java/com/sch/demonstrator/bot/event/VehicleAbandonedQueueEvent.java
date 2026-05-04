package com.sch.demonstrator.bot.event;

import com.sch.demonstrator.bot.model.EVRequest;

public record VehicleAbandonedQueueEvent(
        EVRequest evRequest
) {
}
