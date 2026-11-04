package app.dita.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Writes a live-run report. Never contains the key (asserted); prints only the report. */
final class LiveReport {
    static void write(String file, Map<String, Object> report) throws Exception {
        String json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(report);
        String key = System.getenv("MODEL_API_KEY");
        if (key != null && !key.isEmpty()) assertFalse(json.contains(key), "report must never contain the API key");
        Files.writeString(Path.of("target", file), json);
        System.out.println("MUSE LIVE REPORT " + file + "\n" + json);
    }

    static app.dita.muse.MuseSettings.ReasoningEffort effort() {
        return app.dita.muse.MuseSettings.ReasoningEffort.valueOf(System.getenv().getOrDefault("DITA_MUSE_REASONING_EFFORT", "LOW"));
    }

    /**
     * Token accounting. visible = completion - reasoning. Cost only if the approver supplied prices (USD per 1M tokens);
     * per the docs, reasoning tokens are billed as output tokens. Never guessed.
     */
    static void tokens(Map<String, Object> rep, Integer prompt, Integer completion, Integer reasoning) {
        rep.put("prompt_tokens", prompt);
        rep.put("completion_tokens", completion);
        rep.put("reasoning_tokens", reasoning);
        rep.put("visible_tokens", completion == null || reasoning == null ? null : completion - reasoning);
        String in = System.getenv("DITA_MUSE_PRICE_INPUT_PER_MTOK"), out = System.getenv("DITA_MUSE_PRICE_OUTPUT_PER_MTOK");
        if (in != null && out != null && prompt != null && completion != null) {
            double usd = prompt * Double.parseDouble(in) / 1e6 + completion * Double.parseDouble(out) / 1e6;
            rep.put("cost_usd", Math.round(usd * 1e6) / 1e6);
            rep.put("cost_basis", "prompt x $" + in + "/M + completion (incl. reasoning) x $" + out + "/M (standard-mode Muse Spark prices supplied by the user)");
        } else {
            rep.put("cost_usd", null);
            rep.put("cost_basis", "prices not supplied (set DITA_MUSE_PRICE_INPUT_PER_MTOK / _OUTPUT_PER_MTOK); billable output tokens = completion_tokens");
        }
    }

    static boolean approved(String flag) {
        return "yes".equals(System.getenv(flag)) && System.getenv("MODEL_API_KEY") != null;
    }

    private LiveReport() {}
}
