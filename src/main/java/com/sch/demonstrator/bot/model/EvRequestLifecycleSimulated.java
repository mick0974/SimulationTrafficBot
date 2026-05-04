package com.sch.demonstrator.bot.model;

import com.sch.demonstrator.bot.util.Utils;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
public class EvRequestLifecycleSimulated extends EvRequestLifecycle {

    public EvRequestLifecycleSimulated(String requestType,
                                       UUID requestId,
                                       long arrivalTimeInternal,
                                       double socArrival,
                                       String hubId) {

        super(requestType, requestId, arrivalTimeInternal, socArrival, hubId);
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
        super.setAbandonedQueueAtFormatted(Utils.formatTime(abandonedQueueAt));
    }

    @Override
    public void setChargerData(String chargerId, String plugType) {
        super.setChargerId(chargerId);
        super.setPlugType(plugType);
    }

    @Override
    public void setStartChargingAndWaitTime(long startChargingAt) {
        long waitTime = startChargingAt - super.getArrivalTimeInternal();

        super.setWaitTimeBeforeCharging(waitTime);
        super.setStartChargingInternal(startChargingAt);
        super.setStartChargingFormatted(
                Utils.formatTime(startChargingAt)
        );
    }

    @Override
    public void setChargeEndAndDuration(long endChargingAt) {
        long duration = endChargingAt - super.getStartChargingInternal();
        super.setChargeDuration(duration);
        super.setEndChargingInternal(endChargingAt);
        super.setEndChargingFormatted(
                Utils.formatTime(endChargingAt)
        );
    }

    @Override
    public String toString() {
        return super.toString();
    }
}