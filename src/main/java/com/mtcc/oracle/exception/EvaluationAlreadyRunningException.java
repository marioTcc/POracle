package com.mtcc.oracle.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class EvaluationAlreadyRunningException extends RuntimeException {

    public EvaluationAlreadyRunningException() {
        super("An evaluation is already running");
    }
}
