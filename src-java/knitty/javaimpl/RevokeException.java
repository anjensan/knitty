package knitty.javaimpl;

import java.util.concurrent.CancellationException;

public class RevokeException extends CancellationException {

    public static final RevokeException DEFERRED_REVOKED = new RevokeException("deferred is revoked");
    public static final RevokeException YANK_FINISHED = new RevokeException("yank is already finished");

    public RevokeException(String message) {
        this(message, null);
    }

    public RevokeException(Throwable cause) {
        this(null, cause);
    }

    public RevokeException(String message, Throwable cause) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
