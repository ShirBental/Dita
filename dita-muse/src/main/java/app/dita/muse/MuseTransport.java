package app.dita.muse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** HTTP seam (fakeable in tests). The default implementation refuses redirects so the Authorization header never moves hosts. */
public interface MuseTransport {
    record Reply(int status, String body, Map<String, String> headers) {}

    /** N-39: response bodies are capped. */
    int MAX_BODY_BYTES = 1_000_000;

    Reply post(URI url, Map<String, String> headers, String body, Duration timeout) throws Exception;

    static MuseTransport http() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        return (url, headers, body, timeout) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            try {
                HttpResponse<java.io.InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (java.io.InputStream in = r.body()) { bytes = in.readNBytes(MAX_BODY_BYTES + 1); }
                if (bytes.length > MAX_BODY_BYTES) throw new MuseException(MuseException.Kind.RESPONSE_FORMAT, r.statusCode(), "response exceeds " + MAX_BODY_BYTES + " bytes");
                String reqId = r.headers().firstValue("x-request-id").orElse("");
                return new Reply(r.statusCode(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Map.of("x-request-id", reqId));
            } catch (HttpTimeoutException e) {
                throw new MuseException(MuseException.Kind.TIMEOUT, 0, "request timed out (not retried: may already be billed)");
            }
        };
    }
}
