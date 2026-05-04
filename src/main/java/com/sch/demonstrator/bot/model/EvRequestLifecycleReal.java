package com.sch.demonstrator.bot.model;

import com.sch.demonstrator.bot.util.Utils;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@ToString
public class EvRequestLifecycleReal extends EvRequestLifecycle {

    private final long arrivalTimeReal;
    private long startChargingReal;
    private long endChargingReal;

    public EvRequestLifecycleReal(String requestType,
                                  UUID requestId,
                                  long arrivalTimeInternal,
                                  double socArrival,
                                  String hubId) {

        super(requestType, requestId, arrivalTimeInternal, socArrival, hubId);
        this.arrivalTimeReal = Instant.now().getEpochSecond();
    }

    @Override
    public void foundQueue(boolean foundQueue) {
        super.setFoundQueue(foundQueue);
    }

    @Override
    public void setEntersQueueAndMaxWaitTime(boolean entersQueue, long maxWaitTime) {
        super.setEnteredQueue(entersQueue);
        super.setMaxQueueTimeAccepted(maxWaitTime);
    }

    @Override
    public void vehicleAbandonedQueue(long abandonedQueueAt) {
        super.setAbandonedQueue(true);
        long delta = abandonedQueueAt - this.arrivalTimeReal;

        super.setAbandonedQueueAtFormatted(Utils.formatTime(super.getArrivalTimeInternal() + delta));
    }

    @Override
    public void setChargerData(String chargerId, String plugType) {
        super.setChargerId(chargerId);
        super.setPlugType(plugType);
    }

    @Override
    public void setStartChargingAndWaitTime(long startChargingAt) {
        this.startChargingReal = startChargingAt;
        long waitTime = this.startChargingReal - this.arrivalTimeReal;

        super.setWaitTimeBeforeCharging(waitTime);
        super.setStartChargingInternal(super.getArrivalTimeInternal() + waitTime);
        super.setStartChargingFormatted(Utils.formatTime(super.getStartChargingInternal()));
    }

    @Override
    public void setChargeEndAndDuration(long endChargingAt) {
        this.endChargingReal = endChargingAt;
        long duration = this.endChargingReal - this.startChargingReal;

        super.setChargeDuration(duration);
        super.setEndChargingInternal(super.getStartChargingInternal() + duration);
        super.setEndChargingFormatted(Utils.formatTime(super.getEndChargingInternal()));
    }
}