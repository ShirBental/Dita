package app.dita.projections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal JSON-over-HTTP helper. Errors carry the status and a bounded, sanitized server reason only: never
 * credentials, request bodies or message text (see {@link #reason}).
 */
final class Http {
    static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String authHeader;

    Http(String authHeader) { this.authHeader = authHeader; }

    record Response(int status, JsonNode body) {
        /** Sanitized server reason for diagnostics (empty if none). */
        String reason() { return Http.reason(body); }
    }

    static final int MAX_REASON = 200;
    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("(\"[^\"]*\"|'[^']*')");
    private static final java.util.regex.Pattern SAFE_TOKEN = java.util.regex.Pattern.compile("[\"']?[A-Za-z0-9_.:\\-]{1,40}[\"']?");

    /**
     * The server's error text, made safe to log: taken only from the known error fields (Vespa document/v1
     * {@code message}, Vespa query {@code root.errors[0].message}, Neo4j {@code errors[0].code}); quoted runs that
     * are not short identifiers (e.g. echoed field values, which may contain message text) become {@code "…"};
     * control characters are removed; the result is cut to {@value #MAX_REASON} characters.
     */
    static String reason(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) return "";
        String raw;
        if (body.isTextual()) raw = "non-JSON response";                         // never echo a raw page
        else if (body.hasNonNull("message")) raw = body.path("message").asText();
        else if (body.path("root").path("errors").isArray() && body.path("root").path("errors").size() > 0)
            raw = body.path("root").path("errors").get(0).path("message").asText("");
        else if (body.path("errors").isArray() && body.path("errors").size() > 0)
            raw = body.path("errors").get(0).path("code").asText("");
        else return "";
        return sanitize(raw);
    }

    static String sanitize(String raw) {
        if (raw == null) return "";
        java.util.regex.Matcher m = QUOTED.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(SAFE_TOKEN.matcher(m.group()).matches() ? m.group() : "\"…\""));
        m.appendTail(sb);
        String out = sb.toString().replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return out.length() <= MAX_REASON ? out : out.substring(0, MAX_REASON) + "…";
    }

    Response send(String method, String url, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("Accept", "application/json");
            if (authHeader != null) b.header("Authorization", authHeader);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node;
            if (r.body() == null || r.body().isBlank()) node = JSON.nullNode();
            else {
                try { node = JSON.readTree(r.body()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException notJson) { node = JSON.getNodeFactory().textNode("non-JSON"); }
            }
            return new Response(r.statusCode(), node);
        } catch (Exception e) {
            throw new ProjectionException(method + " " + stripQuery(url) + " failed: " + e.getClass().getSimpleName());
        }
    }

    static String stripQuery(String url) { int i = url.indexOf('?'); return i < 0 ? url : url.substring(0, i); }

    static final class ProjectionException extends RuntimeException {
        ProjectionException(String m) { super(m, null, false, false); }
    }
}
