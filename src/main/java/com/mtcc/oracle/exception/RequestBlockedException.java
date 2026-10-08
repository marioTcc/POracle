package com.mtcc.oracle.exception;

public class RequestBlockedException extends RuntimeException {

    public RequestBlockedException(final String reason) {
        super(reason);
    }
}
