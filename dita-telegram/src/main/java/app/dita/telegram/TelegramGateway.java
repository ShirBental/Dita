package app.dita.telegram;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/** Outbound Bot API calls. Replies are always sent WITHOUT parse_mode (N-7: no markup injection). */
public interface TelegramGateway {
    List<JsonNode> getUpdates(long offset, int timeoutSeconds, int limit) throws Exception;
    long sendMessage(long chatId, String text, Long replyToMessageId, List<Button> buttons) throws Exception;
    void answerCallbackQuery(String callbackQueryId, String text) throws Exception;
    /** Platform-verified admin user ids for a group (Bot API getChatAdministrators). */
    Set<Long> chatAdministrators(long chatId) throws Exception;

    record Button(String text, String callbackData) {}
}
