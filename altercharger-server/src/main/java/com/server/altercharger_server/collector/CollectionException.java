package com.server.altercharger_server.collector;

/** Deliberately contains no upstream body, request URL, or authentication key. */
public class CollectionException extends RuntimeException {
    private final Reason reason;

    public CollectionException(String message) {
        super(message);
        this.reason = Reason.COLLECTION_FAILED;
    }

    public CollectionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    /** Only these fixed values may enter collector failure logs; exception messages never do. */
    public enum Reason {
        COLLECTION_FAILED, UPSTREAM_HTTP_ERROR, UPSTREAM_REQUEST_FAILED, UPSTREAM_RESPONSE_INVALID,
        PAGE_NUMBER_MISMATCH, METADATA_TOTAL_CHANGED, EMPTY_PAGE_BEFORE_TOTAL, ITEM_COUNT_MISMATCH,
        PAGE_LIMIT_REACHED, BUDGET_EXHAUSTED, UNEXPECTED_FAILURE
    }
}
