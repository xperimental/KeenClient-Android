package io.keen.client.android.exceptions;

/**
 * NoWriteKeyException
 *
 * @author dkador
 * @since 1.0.1
 */
public class NoWriteKeyException extends KeenException {
    public NoWriteKeyException(String detailMessage) {
        super(detailMessage);
    }
}
