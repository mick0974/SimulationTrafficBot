package com.sch.demonstrator.bot.controller;

import com.sch.demonstrator.bot.controller.dto.response.RequestNotYetAssigned;
import com.sch.demonstrator.bot.service.exception.AdasRequestNotAssignedException;
import com.sch.demonstrator.bot.service.exception.ChargerInUseException;
import com.sch.demonstrator.bot.service.exception.ChargerNotFoundException;
import com.sch.demonstrator.bot.service.exception.ChargerOperationInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;


@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ChargerNotFoundException.class)
    public ResponseEntity<Void> handleChargerNotFound(ChargerNotFoundException e) {
        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ChargerInUseException.class)
    public ResponseEntity<Void> handleChargerInUse(ChargerInUseException e) {
        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ChargerOperationInvalidException.class)
    public ResponseEntity<Void> handleChargerOperationInvalid(ChargerOperationInvalidException e) {
        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AdasRequestNotAssignedException.class)
    public ResponseEntity<RequestNotYetAssigned> handleAdasRequestNotAssigned(AdasRequestNotAssignedException e) {
        log.debug("Error handling adas request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RequestNotYetAssigned(e.getMessage()));
    }
}
