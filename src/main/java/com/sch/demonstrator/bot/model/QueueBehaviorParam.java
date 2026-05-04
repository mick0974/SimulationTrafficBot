package com.sch.demonstrator.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class QueueBehaviorParam {
    @JsonProperty("queue_tolerance")
    private double queueTolerance;
    @JsonProperty("queue_frustration")
    private double queueFrustration;
    @JsonProperty("wait_tolerance_lambda")
    private double waitToleranceLambda;
}
