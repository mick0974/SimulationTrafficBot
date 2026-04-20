package com.sch.demonstrator.bot.websocket.dto;

import com.sch.demonstrator.bot.websocket.dto.payload.TimeStepPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.ToString;


@AllArgsConstructor
@Schema(description = "Messaggio aggiornamento via WebSocket ha un campo payload generico da adattare in base al messaggio")
@ToString
public class WebSocketUpdate {

    // Getter e setter
    @Schema(description = "Tipo messaggio")
    private String type;

    @Schema(description = "Messaggio di stato opzionale")
    private String statusMessage;

    @Schema(description = "Payload personalizzabile")
    private TimeStepPayload payload;
}
