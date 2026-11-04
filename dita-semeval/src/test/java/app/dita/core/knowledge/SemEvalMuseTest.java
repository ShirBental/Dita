package app.dita.core.knowledge;

import app.dita.muse.MuseClient;
import app.dita.muse.MuseException;
import app.dita.muse.MuseSettings;
import app.dita.muse.MuseTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * TASK-046 Muse arms A3 / A6 (EXPERIMENT ONLY; -P semeval). operator approved up to $10 of Muse charges (2026-11-01).
 * <ul>
 *   <li>PREVIEW (default): builds every batch request with the production MuseClient through a capturing transport (no network,
 *       no key), writes recordings/&lt;set&gt;/&lt;arm&gt;-requests.jsonl and &lt;arm&gt;-preview.json (sha256 of all request bodies,
 *       batch count, worst-case cost).</li>
 *   <li>LIVE: only through scripts/semeval-muse.sh. Refuses unless DITA_SEMEVAL_APPROVED equals "approved-" + the preview sha256,
 *       the regenerated requests are byte-identical to the preview, and the cost ledger allows the next call's WORST case
 *       (PREREGISTRATION §6: A6 ≤ $5.50, A3 ≤ $3.50, total stop at $9.50; null usage is charged at its worst case). Each reply
 *       is appended to &lt;arm&gt;-responses.jsonl immediately; a rerun resumes and never repeats a recorded batch.</li>
 * </ul>
 */
class SemEvalMuseTest {
    static final Path ROOT = SemEvalRunTest.ROOT, LEDGER = ROOT.resolve("ledger.json");
    static final double PRICE_IN = 1.25, PRICE_OUT = 4.25, TOTAL_STOP = 9.50;
    static final Map<String, Double> ARM_CAP = Map.of("A3", 3.50, "A6", 5.50);
    static final Map<String, Integer> MAX_TOKENS = Map.of("A3", 8000, "A6", 16000);
    static final int BATCH = 10;

    @Test void museArm() throws Exception {
        String mode = System.getProperty("dita.semeval.muse", "PREVIEW"), arm = System.getProperty("dita.semeval.arm", "A3"),
                set = System.getProperty("dita.semeval.set", "dev");
        Assumptions.assumeTrue(List.of("PREVIEW", "LIVE").contains(mode) && ARM_CAP.containsKey(arm), "bad mode/arm");
        Path dir = ROOT.resolve(set);
        Assumptions.assumeTrue(Files.isDirectory(dir), "no case set " + dir);
        List<JsonNode> cases = SemEval.load(dir);
        int limit = Integer.getInteger("dita.semeval.limit", cases.size());
        cases = cases.subList(0, Math.min(limit, cases.size()));
        List<List<JsonNode>> batches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i += BATCH) batches.add(cases.subList(i, Math.min(cases.size(), i + BATCH)));

        Path rec = ROOT.resolve("recordings").resolve(set);
        Files.createDirectories(rec);
        String system = arm.equals("A3") ? MusePrompts.SYSTEM_A3 : MusePrompts.SYSTEM_A6;
        ObjectNode schema = arm.equals("A3") ? MusePrompts.schemaA3() : MusePrompts.schemaA6();
        int maxTokens = MAX_TOKENS.get(arm);
        MuseSettings settings = new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), "muse-spark-1.3", "MODEL_API_KEY",
                Duration.ofSeconds(120), maxTokens, 200_000, batches.size(), MuseSettings.ResponseFormat.PROMPT_ONLY, MuseSettings.ReasoningEffort.LOW);

        if (mode.equals("PREVIEW")) {
            // capture the exact request bodies with the production client through a capturing transport: no network, no key.
            // The launcher runs PREVIEW in its OWN JVM, so these counted calls never consume the LIVE call cap (N-111).
            List<String> bodies = new ArrayList<>();
            MuseClient capture = new MuseClient(settings, (url, h, body, t) -> { bodies.add(body); return new MuseTransport.Reply(599, "{}", Map.of()); },
                    k -> "preview-not-a-key");
            for (List<JsonNode> b : batches) {
                String user = arm.equals("A3") ? MusePrompts.userA3(b) : MusePrompts.userA6(b);
                try { capture.complete(system, user, "semeval_" + arm.toLowerCase(), schema); } catch (MuseException expected) { }
            }
            List<String> shas = new ArrayList<>();
            for (String b : bodies) shas.add(sha256(b));
            String sha = sha256(String.join("\n", shas));
            double worstPerCall = 0;
            for (String b : bodies) worstPerCall = Math.max(worstPerCall, worst(b.getBytes(StandardCharsets.UTF_8).length, maxTokens));
            StringBuilder jl = new StringBuilder();
            for (int i = 0; i < bodies.size(); i++) {
                ObjectNode o = SemEval.JSON.createObjectNode().put("batch", i).put("sha256", shas.get(i)).put("bytes", bodies.get(i).getBytes(StandardCharsets.UTF_8).length);
                if (!set.equals("sealed")) o.put("body", bodies.get(i));                    // sealed bodies are never written in clear
                jl.append(o).append('\n');
            }
            Files.writeString(rec.resolve(arm + "-requests.jsonl"), jl.toString());
            ObjectNode m = SemEval.JSON.createObjectNode().put("arm", arm).put("set", set).put("promptVersion", arm.equals("A3") ? MusePrompts.VERSION_A3 : MusePrompts.VERSION_A6)
                    .put("cases", cases.size()).put("batches", bodies.size()).put("requestsSha256", sha).put("maxCompletionTokens", maxTokens)
                    .put("worstCaseUsdPerCall", round(worstPerCall)).put("worstCaseUsdTotal", round(worstPerCall * bodies.size()))
                    .put("totalRequestBytes", bodies.stream().mapToInt(b -> b.getBytes(StandardCharsets.UTF_8).length).sum());
            Files.writeString(rec.resolve(arm + "-preview.json"), SemEval.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            return;
        }

        // 2. LIVE. Nothing is rebuilt through a counted client. Natasha N-111..N-115:
        //  - the approval names the REVIEWED preview hash, passed in by the launcher (N-113), and the preview's per-batch hashes
        //    must hash to it;
        //  - a guarding transport hashes every OUTGOING body and refuses unless it equals that batch's reviewed hash (N-114);
        //  - the live call cap is the batch count, and no preview call shares this JVM (N-111);
        //  - the worst case is RESERVED in the ledger before each call and settled after; only calls that may have been billed are
        //    recorded as done (N-112); the spend floor is the sum of recorded costs, so deleting the ledger cannot reset it (N-115).
        JsonNode preview = SemEval.JSON.readTree(Files.readString(rec.resolve(arm + "-preview.json")));
        String approvedSha = preview.path("requestsSha256").asText();
        if (!("approved-" + approvedSha).equals(System.getenv("DITA_SEMEVAL_APPROVED"))) throw new IllegalStateException("no approval for this preview: nothing sent");
        List<String> reviewed = new ArrayList<>();
        List<Integer> reviewedBytes = new ArrayList<>();
        for (String l : Files.readAllLines(rec.resolve(arm + "-requests.jsonl"))) {
            if (l.isBlank()) continue;
            JsonNode o = SemEval.JSON.readTree(l);
            reviewed.add(o.path("sha256").asText()); reviewedBytes.add(o.path("bytes").asInt());
        }
        if (!sha256(String.join("\n", reviewed)).equals(approvedSha) || reviewed.size() != batches.size())
            throw new IllegalStateException("the per-batch request hashes do not match the approved preview: nothing sent");
        Path responses = rec.resolve(arm + "-responses.jsonl");
        java.util.Set<Integer> done = new java.util.HashSet<>();
        if (Files.exists(responses)) for (String l : Files.readAllLines(responses)) if (!l.isBlank()) done.add(SemEval.JSON.readTree(l).path("batch").asInt());
        int[] current = {-1};
        MuseTransport http = MuseTransport.http();
        MuseTransport guarded = (url, h, body, t) -> {
            if (current[0] < 0 || !sha256(body).equals(reviewed.get(current[0]))
                    || body.getBytes(StandardCharsets.UTF_8).length != reviewedBytes.get(current[0]))          // N-120
                throw new MuseException(MuseException.Kind.CONFIG, 0, "outgoing request differs from the reviewed preview: refused, nothing sent");
            return http.post(url, h, body, t);
        };
        MuseClient live = new MuseClient(settings, guarded, System::getenv);
        // N-119: one live run at a time, across JVMs
        try (var ch = java.nio.channels.FileChannel.open(ROOT.resolve("live.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = ch.tryLock()) {
        if (lock == null) throw new IllegalStateException("another live run holds eval/TASK-046/semeval/live.lock: nothing sent");
        for (int i = 0; i < batches.size(); i++) {
            if (done.contains(i)) continue;
            double next = worst(reviewedBytes.get(i), maxTokens);
            double spent = spentFloor(), armSpent = Math.max(ledger().path("arms").path(arm).asDouble(), recordedCost(arm));
            if (spent + next > TOTAL_STOP || armSpent + next > ARM_CAP.get(arm)) {
                System.out.println("BUDGET STOP before batch " + i + ": spent $" + round(spent) + " (arm $" + round(armSpent) + "), next worst case $" + round(next));
                break;
            }
            charge(arm, next);                                                       // reserve the worst case first
            List<JsonNode> b = batches.get(i);
            String user = arm.equals("A3") ? MusePrompts.userA3(b) : MusePrompts.userA6(b);
            ObjectNode line = SemEval.JSON.createObjectNode().put("batch", i).put("requestSha256", reviewed.get(i));
            // N-118: mark the batch PENDING (= done for resume) before anything can be sent; a crash never causes a resend
            ObjectNode pending = SemEval.JSON.createObjectNode().put("batch", i).put("requestSha256", reviewed.get(i)).put("pending", true).put("costUsd", 0.0);
            Files.writeString(responses, pending + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            current[0] = i;
            try {
                MuseClient.Result r = live.complete(system, user, "semeval_" + arm.toLowerCase(), schema);
                double cost = r.promptTokens() == null || r.completionTokens() == null ? next
                        : (r.promptTokens() * PRICE_IN + r.completionTokens() * PRICE_OUT) / 1e6;
                line.put("content", r.content()).put("promptTokens", r.promptTokens()).put("completionTokens", r.completionTokens())
                        .put("reasoningTokens", r.reasoningTokens()).put("latencyMs", r.latencyMs()).put("finishReason", r.finishReason()).put("costUsd", round(cost));
                Files.writeString(responses, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                charge(arm, cost - next);                                            // settle the reservation
            } catch (MuseException e) {
                switch (e.kind()) {
                    case TIMEOUT, TRANSPORT, SERVER, EMPTY_CONTENT, RESPONSE_FORMAT -> {   // may have been billed: keep the worst case
                        line.put("error", e.kind().name()).put("costUsd", round(next));
                        Files.writeString(responses, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                    default -> {                                                     // CONFIG/BUDGET: never sent; AUTH/CLIENT/RATE_LIMIT: rejected
                        charge(arm, -next);                                          // refund; NOT recorded as done
                        final int bi = i;
                        List<String> keep = Files.readAllLines(responses).stream().filter(l -> {
                            try { JsonNode o = SemEval.JSON.readTree(l); return !(o.path("pending").asBoolean(false) && o.path("batch").asInt() == bi); }
                            catch (Exception ex) { return true; }
                        }).toList();
                        Files.write(responses, keep);
                        System.out.println("STOP: " + e.kind() + " on batch " + i + " (not sent or rejected; not charged)");
                        return;
                    }
                }
            }
        }
        }
    }

    static double recordedCost(String arm) throws Exception {
        double sum = 0;
        try (var sets = Files.list(ROOT.resolve("recordings"))) {
            for (Path d : sets.toList()) {
                Path f = d.resolve(arm + "-responses.jsonl");
                if (Files.exists(f)) for (String l : Files.readAllLines(f)) if (!l.isBlank()) sum += SemEval.JSON.readTree(l).path("costUsd").asDouble();
            }
        }
        return sum;
    }

    /** N-115: never below what the recordings prove was spent, even if ledger.json was deleted. */
    static double spentFloor() throws Exception { return Math.max(ledger().path("totalUsd").asDouble(), recordedCost("A3") + recordedCost("A6")); }

    /** Worst case for one call: prompt tokens ≤ bytes/3 (conservative for English JSON) + the full output cap. */
    static double worst(int bodyBytes, int maxTokens) {
        return (bodyBytes / 3.0 * PRICE_IN + maxTokens * PRICE_OUT) / 1e6;
    }

    static ObjectNode ledger() throws Exception {
        if (!Files.exists(LEDGER)) { ObjectNode l = SemEval.JSON.createObjectNode().put("totalUsd", 0); l.putObject("arms"); return l; }
        return (ObjectNode) SemEval.JSON.readTree(Files.readString(LEDGER));
    }

    static synchronized void charge(String arm, double usd) throws Exception {
        ObjectNode l = ledger();
        l.put("totalUsd", round(l.path("totalUsd").asDouble() + usd));
        ObjectNode arms = l.has("arms") ? (ObjectNode) l.get("arms") : l.putObject("arms");
        arms.put(arm, round(arms.path(arm).asDouble() + usd));
        if (usd > 0) l.put("reservations", l.path("reservations").asInt() + 1);
        Path tmp = LEDGER.resolveSibling("ledger.json.tmp");                        // N-115: atomic replace
        Files.writeString(tmp, SemEval.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(l));
        Files.move(tmp, LEDGER, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    static double round(double v) { return Math.round(v * 1e6) / 1e6; }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
