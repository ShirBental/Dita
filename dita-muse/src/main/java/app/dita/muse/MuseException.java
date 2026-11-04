package app.dita.muse;

import java.util.Map;

/**
 * Muse call failures, by class:
 * <ul>
 *   <li>local: CONFIG, BUDGET (nothing was sent);</li>
 *   <li>transport: TRANSPORT, TIMEOUT (no HTTP response; httpStatus = 0);</li>
 *   <li>API error (non-2xx, httpStatus preserved, error envelope parsed): AUTH, RATE_LIMIT, SERVER, CLIENT;</li>
 *   <li>2xx but unusable (httpStatus preserved): EMPTY_CONTENT (well-formed, content null or empty, e.g.
 *       finish_reason=length on a reasoning model) or RESPONSE_FORMAT (not JSON, or no choices).</li>
 * </ul>
 * Messages and fields never contain the API key or response text: only status, error type/code, a redacted and
 * truncated API error message, finish_reason, usage numbers and a structure-only shape. No cause chain.
 */
public class MuseException extends RuntimeException {
    public enum Kind { CONFIG, BUDGET, TRANSPORT, TIMEOUT, AUTH, RATE_LIMIT, SERVER, CLIENT, EMPTY_CONTENT, RESPONSE_FORMAT }

    /** Diagnostics attached to API and 2xx failures; all values are non-sensitive by construction. */
    public record Details(String apiErrorType, String apiErrorCode, String finishReason, Map<String, Integer> usage, String shape) {
        public static final Details NONE = new Details(null, null, null, Map.of(), null);
    }

    private final Kind kind;
    private final int status;
    private final Details details;

    public MuseException(Kind kind, int status, String message) { this(kind, status, message, Details.NONE); }

    public MuseException(Kind kind, int status, String message, Details details) {
        super(kind + (status > 0 ? " HTTP " + status : "") + ": " + message, null, false, false);
        this.kind = kind; this.status = status; this.details = details == null ? Details.NONE : details;
    }

    public Kind kind() { return kind; }
    /** The real HTTP status, or 0 when no response was received (CONFIG, BUDGET, TRANSPORT, TIMEOUT). */
    public int status() { return status; }
    public Details details() { return details; }
}
