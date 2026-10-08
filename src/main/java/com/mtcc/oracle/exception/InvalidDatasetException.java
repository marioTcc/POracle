package com.mtcc.oracle.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidDatasetException extends RuntimeException {

    public InvalidDatasetException(final String reason) {
        super(reason);
    }
}
