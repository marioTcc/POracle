package com.mtcc.project.extractor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class IngestionAlreadyRunningException extends RuntimeException {

    public IngestionAlreadyRunningException() {
        super("An ingestion is already running");
    }
}
