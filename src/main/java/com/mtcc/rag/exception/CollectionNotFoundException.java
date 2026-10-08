package com.mtcc.rag.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CollectionNotFoundException extends RuntimeException {

    public CollectionNotFoundException(final String collectionName) {
        super("Collection not found: " + collectionName);
    }
}
