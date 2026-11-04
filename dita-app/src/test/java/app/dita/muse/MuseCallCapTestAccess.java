package app.dita.muse;

/**
 * Test-only access to MuseClient's process-wide call counter (N-39) from dita-app tests. The counter is static, so offline
 * tests that make many (fixture-served) Muse calls in the shared surefire JVM would exhaust the budget of later tests.
 * Never used by production code or by the gated LIVE trial (which runs in its own JVM).
 */
public final class MuseCallCapTestAccess {
    private MuseCallCapTestAccess() {}
    public static void reset() { MuseClient.resetProcessCountForTests(); }
}
