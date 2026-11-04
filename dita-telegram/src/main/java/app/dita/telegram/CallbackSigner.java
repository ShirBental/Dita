package app.dita.telegram;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Callback data is client-controlled, so approval buttons carry an HMAC over (chat, plan, revision, decision).
 * Format {@code a:<plan>:<rev>:<A|R>:<sig>} fits Telegram's 64-byte callback_data limit. Identity is never in the
 * data: it comes from callback_query.from.id.
 */
public final class CallbackSigner {
    public record Approval(String planId, int revision, boolean approve) {}

    private final byte[] secret;

    public CallbackSigner(byte[] secret) {
        if (secret.length < 32) throw new IllegalArgumentException("callback secret must be >= 32 bytes");
        this.secret = secret.clone();
    }

    public String sign(long chatId, String planId, int revision, boolean approve) {
        if (!planId.matches("[A-Za-z0-9]{1,16}")) throw new IllegalArgumentException("plan id must be short alphanumeric");
        String body = "a:" + planId + ":" + revision + ":" + (approve ? "A" : "R");
        String data = body + ":" + mac(chatId + "|" + body);
        if (data.getBytes(StandardCharsets.UTF_8).length > 64) throw new IllegalStateException("callback data too long");
        return data;
    }

    public Optional<Approval> verify(long chatId, String data) {
        if (data == null) return Optional.empty();
        String[] p = data.split(":");
        if (p.length != 5 || !p[0].equals("a") || !p[1].matches("[A-Za-z0-9]{1,16}") || !p[2].matches("\\d{1,6}")
                || !(p[3].equals("A") || p[3].equals("R"))) return Optional.empty();
        String body = String.join(":", p[0], p[1], p[2], p[3]);
        byte[] expected = mac(chatId + "|" + body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, p[4].getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        return Optional.of(new Approval(p[1], Integer.parseInt(p[2]), p[3].equals("A")));
    }

    private String mac(String s) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = m.doFinal(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(full, 18));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
