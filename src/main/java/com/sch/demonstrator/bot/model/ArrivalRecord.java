package com.sch.demonstrator.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
public class ArrivalRecord {
    @JsonProperty("hub_id")
    private String hubId;
    @JsonProperty("hour")
    private double hour;
    @JsonProperty("arrivals")
    private int arrivals;
}
