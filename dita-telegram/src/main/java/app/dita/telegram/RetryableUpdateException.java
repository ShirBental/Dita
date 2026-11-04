package app.dita.telegram;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * Shuri B1: an update failed for a TRANSIENT storage reason (lock timeout, deadlock, serialization failure, lost
 * connection). Its transaction rolled back, so nothing was half-written. The poller must NOT acknowledge it: the
 * offset stays put and the same update is handled again after backoff. Never carries message content.
 */
public final class RetryableUpdateException extends RuntimeException {
    private final long updateId;

    public RetryableUpdateException(long updateId, Throwable cause) {
        super("update " + updateId + " failed transiently: " + cause.getClass().getSimpleName(), null, false, false);
        this.updateId = updateId;
    }

    public long updateId() { return updateId; }

    /**
     * SQLSTATE classes 08 (connection), 40 (transaction rollback: deadlock 40P01, serialization 40001),
     * 53 (insufficient resources), 57P01-57P03 (shutdown / cannot connect now), 55P03 (lock_not_available,
     * e.g. the outbox lock_timeout), or a JDBC transient/recoverable exception anywhere in the cause chain.
     */
    public static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientException || t instanceof SQLRecoverableException) return true;
            if (t instanceof SQLException s && s.getSQLState() != null) {
                String st = s.getSQLState();
                if (st.startsWith("08") || st.startsWith("40") || st.startsWith("53") || st.equals("55P03")
                        || st.equals("57P01") || st.equals("57P02") || st.equals("57P03")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }
}
