package app.dita.core.config;

import java.time.Duration;

/** Event dispatch to projections: retryable, at-least-once, idempotent consumers, dead-lettering. */
public record DispatchSettings(int batchSize, int workerThreads, int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    public static final DispatchSettings DEFAULTS = new DispatchSettings(100, 2, 5, Duration.ofSeconds(1), Duration.ofMinutes(1));

    public DispatchSettings {
        LinkSettings.require(batchSize >= 1 && batchSize <= 10_000, "batchSize must be in [1,10000]");
        LinkSettings.require(workerThreads >= 1 && workerThreads <= 32, "workerThreads must be in [1,32]");
        LinkSettings.require(maxAttempts >= 1 && maxAttempts <= 100, "maxAttempts must be in [1,100]");
        LinkSettings.require(initialBackoff != null && !initialBackoff.isNegative() && maxBackoff != null
                && maxBackoff.compareTo(initialBackoff) >= 0, "require 0 <= initialBackoff <= maxBackoff");
    }

    public Duration backoff(int attempt) {
        long ms = initialBackoff.toMillis() * (1L << Math.min(20, Math.max(0, attempt - 1)));
        return Duration.ofMillis(Math.min(ms, maxBackoff.toMillis()));
    }
}
