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

    public EvRequestLifecycleReal(String requestType, UUID requestId, long arrivalTimeInternal,
                                  double socArrival, String hubId, String requestedChargerType) {
        super(requestType, requestId, arrivalTimeInternal, socArrival, hubId, requestedChargerType);
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
    public void setChargerData(String chargerId, String assignedChargerType) {
        super.setChargerId(chargerId);
        super.setAssignedChargerType(assignedChargerType);
    }

    @Override
    public void setStartChargingAndWaitTime(long startChargingAt) {
        this.startChargingReal = startChargingAt;
        long waitTime = this.startChargingReal - this.arrivalTimeReal;

        super.setWaitTimeBeforeCharging(waitTime);
        super.setStartChargingInternal(super.getArrivalTimeInternal() + waitTime);
        super.setStartChargingFormatted(Utils.formatTime(super.getStartChargingInternal()));
        super.setWaitTimeHourFormatted(Utils.formatTime(waitTime));
    }

    @Override
    public void setChargeEndAndDuration(long endChargingAt) {
        this.endChargingReal = endChargingAt;
        long duration = this.endChargingReal - this.startChargingReal;

        super.setChargeDuration(duration);
        super.setEndChargingInternal(super.getStartChargingInternal() + duration);
        super.setEndChargingFormatted(Utils.formatTime(super.getEndChargingInternal()));
        super.setChargeDurationHourFormatted(Utils.formatTime(duration));
    }
}