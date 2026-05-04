package com.sch.demonstrator.bot.model;

import com.sch.demonstrator.bot.util.Utils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
public abstract class EvRequestLifecycle {
    private String requestType;
    private UUID requestId;
    private Long arrivalTimeInternal;
    private Double socArrival;
    private String hubId;

    private Boolean foundQueue;
    private Boolean enteredQueue;
    private Boolean abandonedQueue;
    private Long maxQueueTimeAccepted;

    private String chargerId;
    private String plugType;

    private Long startChargingInternal;
    private Long waitTimeBeforeCharging;
    private Long chargeDuration;
    private Long endChargingInternal;

    private String abandonedQueueAtFormatted;
    private String arrivalTimeFormatted;
    private String startChargingFormatted;
    private String endChargingFormatted;

    public EvRequestLifecycle(String requestType, UUID requestId, long arrivalTimeInternal, double socArrival, String hubId) {
        this.requestType = requestType;
        this.requestId = requestId;
        this.arrivalTimeInternal = arrivalTimeInternal;
        this.socArrival = socArrival;
        this.hubId = hubId;
        this.arrivalTimeFormatted = Utils.formatTime(arrivalTimeInternal);
    }

    public abstract void foundQueue(boolean foundQueue);
    public abstract void setEntersQueueAndMaxWaitTime(boolean entersQueue, long maxWaitTime);
    public abstract void vehicleAbandonedQueue(long abandonedQueueAt);
    public abstract void setChargerData(String chargerId, String plugType);
    public abstract void setStartChargingAndWaitTime(long startChargingAt);
    public abstract void setChargeEndAndDuration(long endChargingAt);

    @Override
    public String toString() {
        return "EvRequestLifecycle{" +
                "requestType='" + requestType + '\'' +
                ", requestId=" + requestId +
                ", arrivalTimeInternal=" + arrivalTimeInternal +
                ", socArrival=" + socArrival +
                ", hubId='" + hubId + '\'' +
                ", foundQueue=" + foundQueue +
                ", enteredQueue=" + enteredQueue +
                ", abandonedQueue=" + abandonedQueue +
                ", maxQueueTimeAccepted=" + maxQueueTimeAccepted +
                ", chargerId='" + chargerId + '\'' +
                ", plugType='" + plugType + '\'' +
                ", startChargingInternal=" + startChargingInternal +
                ", waitTimeBeforeCharging=" + waitTimeBeforeCharging +
                ", chargeDuration=" + chargeDuration +
                ", endChargingInternal=" + endChargingInternal +
                ", abandonedQueueAtFormatted='" + abandonedQueueAtFormatted + '\'' +
                ", arrivalTimeFormatted='" + arrivalTimeFormatted + '\'' +
                ", startChargingFormatted='" + startChargingFormatted + '\'' +
                ", endChargingFormatted='" + endChargingFormatted + '\'' +
                '}';
    }
}