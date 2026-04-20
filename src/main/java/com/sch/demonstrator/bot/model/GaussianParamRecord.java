package com.sch.demonstrator.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class GaussianParamRecord {
    @JsonProperty("hub_id")
    private String hubId;

    @JsonProperty("hour")
    private double hour;

    @JsonProperty("mu_init")
    private double muInit;

    @JsonProperty("sigma_init")
    private double sigmaInit;
}
