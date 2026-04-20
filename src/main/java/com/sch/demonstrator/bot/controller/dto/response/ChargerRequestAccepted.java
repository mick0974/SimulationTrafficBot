package com.sch.demonstrator.bot.controller.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@AllArgsConstructor
@Getter
public class ChargerRequestAccepted {
    private UUID requestId;
}
