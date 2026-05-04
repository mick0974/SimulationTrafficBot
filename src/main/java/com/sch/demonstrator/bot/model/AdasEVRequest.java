package com.sch.demonstrator.bot.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
@NoArgsConstructor
@SuperBuilder
@ToString
public class AdasEVRequest extends EVRequest {
    @Override
    public String getRequestType() {
        return "Adas";
    }
}
