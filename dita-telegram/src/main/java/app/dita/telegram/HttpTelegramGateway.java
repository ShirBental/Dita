package app.dita.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bot API over HTTPS (long polling: no inbound port, updates are authenticated by the bot token channel).
 * The token appears in the URL path, so every error is rethrown with a redacted message and no cause (N-9).
 * NOT EXERCISED against the live API in this environment (network to api.telegram.org is blocked here).
 */
public final class HttpTelegramGateway implements TelegramGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HttpTelegramGateway(String botToken) {
        if (botToken == null || botToken.isBlank()) throw new IllegalArgumentException("bot token required");
        this.base = "https://api.telegram.org/bot" + botToken + "/";
    }

    private JsonNode call(String method, ObjectNode body, Duration timeout) throws TelegramApiException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + method)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = JSON.readTree(res.body());
            if (!node.path("ok").asBoolean(false)) {
                throw new TelegramApiException(method + " failed: HTTP " + res.statusCode() + " "
                        + redact(node.path("description").asText("")));
            }
            return node.path("result");
        } catch (TelegramApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TelegramApiException(method + " failed: " + e.getClass().getSimpleName() + " " + redact(String.valueOf(e.getMessage())));
        }
    }

    String redact(String s) { return s.replaceAll("bot\\d+:[A-Za-z0-9_-]+", "bot<redacted>"); }

    @Override public List<JsonNode> getUpdates(long offset, int timeoutSeconds, int limit) throws Exception {
        ObjectNode b = JSON.createObjectNode().put("offset", offset).put("timeout", timeoutSeconds).put("limit", limit);
        ArrayNode allowed = b.putArray("allowed_updates");
        for (String u : List.of("message", "edited_message", "callback_query", "my_chat_member")) allowed.add(u);
        List<JsonNode> out = new ArrayList<>();
        call("getUpdates", b, Duration.ofSeconds(timeoutSeconds + 10L)).forEach(out::add);
        return out;
    }

    @Override public long sendMessage(long chatId, String text, Long replyTo, List<Button> buttons) throws Exception {
        ObjectNode b = JSON.createObjectNode().put("chat_id", chatId).put("text", text);   // no parse_mode, deliberately
        if (replyTo != null) b.putObject("reply_parameters").put("message_id", replyTo).put("allow_sending_without_reply", true);
        if (buttons != null && !buttons.isEmpty()) {
            ArrayNode row = b.putObject("reply_markup").putArray("inline_keyboard").addArray();
            for (Button bt : buttons) row.addObject().put("text", bt.text()).put("callback_data", bt.callbackData());
        }
        return call("sendMessage", b, Duration.ofSeconds(15)).path("message_id").asLong();
    }

    @Override public void answerCallbackQuery(String id, String text) throws Exception {
        call("answerCallbackQuery", JSON.createObjectNode().put("callback_query_id", id).put("text", text), Duration.ofSeconds(10));
    }

    @Override public Set<Long> chatAdministrators(long chatId) throws Exception {
        Set<Long> ids = new HashSet<>();
        call("getChatAdministrators", JSON.createObjectNode().put("chat_id", chatId), Duration.ofSeconds(10))
                .forEach(m -> { if (!m.path("user").path("is_bot").asBoolean()) ids.add(m.path("user").path("id").asLong()); });
        return ids;
    }

    /** Carries no cause, so a stack trace can never include the token-bearing URL. */
    public static final class TelegramApiException extends Exception {
        public TelegramApiException(String m) { super(m, null, false, false); }
    }
}
