package com.athena.exception;

import java.io.IOException;

/**
 * Thrown while reading a request body that is larger than the configured
 * limit. It is an IOException because it surfaces from the request stream.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limitBytes) {
        super("Request body is larger than " + limitBytes + " bytes");
    }
}
