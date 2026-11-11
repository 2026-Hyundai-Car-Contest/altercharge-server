package com.server.altercharger_server.collector;

/** Deliberately contains no upstream body, request URL, or authentication key. */
public class CollectionException extends RuntimeException {
    public CollectionException(String message) {
        super(message);
    }
}
