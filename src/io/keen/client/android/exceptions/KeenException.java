package io.keen.client.android.exceptions;

/**
 * KeenException
 *
 * @author dkador
 * @since 1.0.0
 */
public abstract class KeenException extends Exception {
    KeenException(String detailMessage) {
        super(detailMessage);
    }
}
