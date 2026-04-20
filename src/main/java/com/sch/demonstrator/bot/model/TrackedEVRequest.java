package com.sch.demonstrator.bot.model;

import com.sch.demonstrator.bot.util.Utils;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@ToString
public class TrackedEVRequest implements Comparable<TrackedEVRequest> {
    private String requestType;
    private UUID requestId;
    private long arrivalTimeInternal;
    private long startChargingInternal;
    private long waitTime;
    private long chargeDuration;
    private long endChargingInternal;
    private double socArrival;

    private String hubId;
    private String chargerId;
    private String plugType;

    private String arrivalTimeFormatted;
    private String startChargingFormatted;
    private String endChargingFormatted;

    private final long arrivalTimeReal;
    private long startChargingReal;
    private long endChargingReal;

    public TrackedEVRequest(String requestType, UUID requestId, long arrivalTimeInternal, double socArrival, String hubId) {
        this.requestType = requestType;
        this.requestId = requestId;
        this.arrivalTimeInternal = arrivalTimeInternal;
        this.socArrival = socArrival;
        this.hubId = hubId;
        this.arrivalTimeFormatted = Utils.formatTime(arrivalTimeInternal);
        this.arrivalTimeReal = Instant.now().getEpochSecond();
    }

    public void setChargerData(String chargerId, String plugType) {
        this.chargerId = chargerId;
        this.plugType = plugType;
    }

    public void setStartChargingAndWaitTime(long startChargingReal) {
        this.startChargingReal = startChargingReal;
        this.waitTime = this.startChargingReal - this.arrivalTimeReal;
        this.startChargingInternal = this.arrivalTimeInternal + this.waitTime;
        this.startChargingFormatted = Utils.formatTime(this.startChargingInternal);
    }

    public void setChargeEndAndDuration(long endChargingReal) {
        this.endChargingReal = endChargingReal;
        this.chargeDuration = this.endChargingReal - this.startChargingReal;
        this.endChargingInternal = this.startChargingInternal + this.chargeDuration;
        this.endChargingFormatted = Utils.formatTime(this.endChargingInternal);
    }

    @Override
    public int compareTo(TrackedEVRequest o) {
        if (o == null)
            return 1;

        return Long.compare(this.endChargingInternal, o.endChargingInternal);
    }
}