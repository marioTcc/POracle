package com.mtcc.project.extractor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidIngestionSourceException extends RuntimeException {

    public InvalidIngestionSourceException(final String message) {
        super(message);
    }
}
