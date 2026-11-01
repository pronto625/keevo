package com.keevo.shared.job;

/**
 * Story 16.4 — thrown by a job's work to end it {@code FAILED} with a French message shown as is on the console (no
 * SQL, schema name, identifier nor stack trace). Any other exception ends the job with a generic French message.
 */
public class JobFailedException extends RuntimeException {

    public JobFailedException(String frenchMessage) {
        super(frenchMessage);
    }
}
