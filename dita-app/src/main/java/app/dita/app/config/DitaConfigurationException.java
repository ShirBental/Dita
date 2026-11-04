package app.dita.app.config;

/** Unsafe or unsupported configuration. Thrown during startup; the application does not start. */
public final class DitaConfigurationException extends RuntimeException {
    public DitaConfigurationException(String message) { super(message); }
    public DitaConfigurationException(String message, Throwable cause) { super(message, cause); }
}
