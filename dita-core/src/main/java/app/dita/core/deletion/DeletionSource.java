package app.dita.core.deletion;

/**
 * How Dita learned of a deletion. PLATFORM_EVENT only where the platform verifiably delivers deletions;
 * FORGET_COMMAND is the documented fallback (Telegram: /forget as a reply to the message).
 */
public enum DeletionSource { PLATFORM_EVENT, FORGET_COMMAND }
