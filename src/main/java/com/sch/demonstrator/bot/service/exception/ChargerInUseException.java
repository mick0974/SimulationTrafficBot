package com.sch.demonstrator.bot.service.exception;

public class ChargerInUseException extends RuntimeException {
    public ChargerInUseException(String message) {
        super(message);
    }
}
