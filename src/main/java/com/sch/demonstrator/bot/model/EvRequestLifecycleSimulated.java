package com.sch.demonstrator.bot.model;

import com.sch.demonstrator.bot.util.Utils;
import lombok.Getter;

import java.util.UUID;

@Getter
public class EvRequestLifecycleSimulated extends EvRequestLifecycle {

    private Boolean endedChargingEarlier;
    public EvRequestLifecycleSimulated(String requestType, UUID requestId, long arrivalTimeInternal,
                                       double socArrival, String hubId, String requestedChargerType) {

        super(requestType, requestId, arrivalTimeInternal, socArrival, hubId, requestedChargerType);
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
    public void setChargerData(String chargerId, String assignedChargerType) {
        super.setChargerId(chargerId);
        super.setAssignedChargerType(assignedChargerType);
        endedChargingEarlier =
                !getRequestedChargerType().equals("AC")
                && assignedChargerType.equals("AC");
    }

    @Override
    public void setStartChargingAndWaitTime(long startChargingAt) {
        long waitTime = startChargingAt - super.getArrivalTimeInternal();

        super.setWaitTimeBeforeCharging(waitTime);
        super.setStartChargingInternal(startChargingAt);
        super.setStartChargingFormatted(Utils.formatTime(startChargingAt));
        super.setWaitTimeHourFormatted(Utils.formatTime(waitTime));
    }

    @Override
    public void setChargeEndAndDuration(long endChargingAt) {
        long duration = endChargingAt - super.getStartChargingInternal();

        super.setChargeDuration(duration);
        super.setEndChargingInternal(endChargingAt);
        super.setEndChargingFormatted(Utils.formatTime(endChargingAt));
        super.setChargeDurationHourFormatted(Utils.formatTime(duration));
    }

    @Override
    public String toString() {
        return super.toString();
    }
}