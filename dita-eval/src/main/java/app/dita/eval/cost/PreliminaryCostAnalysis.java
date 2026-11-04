package app.dita.eval.cost;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.context.ContextBuilder;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;
import app.dita.eval.cost.BreakEvenCalculator.Arm;
import app.dita.eval.cost.BreakEvenCalculator.BreakEven;
import app.dita.eval.cost.BreakEvenCalculator.Extraction;
import app.dita.eval.cost.BreakEvenCalculator.Inputs;
import app.dita.eval.cost.BreakEvenCalculator.Maintenance;
import app.dita.eval.cost.BreakEvenCalculator.Result;
import app.dita.eval.cost.BreakEvenCalculator.SensitivityRow;
import app.dita.eval.cost.BreakEvenCalculator.SteadyState;
import app.dita.eval.cost.BreakEvenCalculator.SteadyStateResult;
import app.dita.eval.cost.TokenEstimator.FixedPrompt;
import app.dita.eval.cost.TokenEstimator.TokenRange;
import app.dita.eval.cost.UsageRecord.CallKind;
import app.dita.eval.gate.Manifest;
import app.dita.eval.runner.Prompt;
import app.dita.eval.runner.RunnerFileAccess;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TASK-039 preliminary cost analysis. OFFLINE: no model call, no network. Writes {@code results/cost-prelim.json},
 * {@code results/cost-prelim.md} and {@code charts/cost-vs-questions.svg} (the SVG lives outside results/ because the
 * Stage 0 SyntheticDataGate rejects non-text files in results/).
 * <p>Inputs: the numbers-only recorded usage (MEASURED; strict loader), the Stage 0 runner contexts and scores
 * (MEASURED_OFFLINE), the synthetic dev prompts and corpora (for each context's Hebrew share), and {@link
 * app.dita.eval.answer.AnswerPrompt} (for S). No answer call has been made, so every per-question cost is ESTIMATED
 * input only, and answer output cost is an assumption band.
 */
public final class PreliminaryCostAnalysis {
    public static final List<String> COST_ARMS = List.of("A-full", "A-budget", "B-weak control", "C-today");
    public static final List<String> RECALL_ARMS = List.of("A-full", "A-budget", "B-weak control", "C-today", "C-graph-off",
            "B-weak no-window (diag)", "C-today +/-2 (diag)");
    public static final int BATCH = 10;
    public static final double V_LO = 150, V_HI = 700, RV_LO = 1, RV_HI = 3.5;
    public static final double BAND_LO = 0.5, BAND_HI = 2.0;
    public static final double RHO_W = 0.1;
    public static final int[] Q = BreakEvenCalculator.Q_GRID;
    static final String CHART_REL = "charts/cost-vs-questions.svg";
    static final String TITLE_TAG = "ESTIMATED – no answer call measured";

    private static final ObjectMapper OUT = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private PreliminaryCostAnalysis() {}

    /** Per-arm estimated input for the dev questions (means over items). */
    record ArmInput(String arm, int n, double ctxCharsMean, double hebrewShareMean, TokenRange ctxTokens, TokenRange inputTokens) {}

    record CorpusSize(String label, List<Integer> groupSizes) {
        int total() { return groupSizes.stream().mapToInt(Integer::intValue).sum(); }
    }

    public record Outputs(String json, String markdown, String svg) {}

    // =================================================================================================== analysis

    public static Outputs analyse(BenchPaths p) throws IOException {
        PriceTable prices = PriceTable.DEFAULT;
        Path usageFile = p.bench().resolve("usage/recorded-usage.jsonl");
        Path unledgeredFile = p.bench().resolve("usage/unledgered-usage.jsonl");
        verifyUsageManifest(p);
        List<UsageRecord> usage = UsageJsonl.load(usageFile);              // runs 2-5, exactly the tasks.md cost ledger
        List<UsageRecord> unledgered = UsageJsonl.load(unledgeredFile);    // real calls found in reports but absent from that ledger
        if (unledgered.size() != 1) throw new IllegalStateException("expected exactly one unledgered usage record");
        UsageRecord run5 = byLabel(usage, "muse-live-run-5"), run4 = byLabel(usage, "muse-live-run-4");

        // ---- measured ledger (historical imports; not reservations)
        CostLedger hist = CostLedger.inMemory("cost-prelim-historical", BigDecimal.ONE, prices);
        for (UsageRecord u : usage) hist.importHistorical(u.runLabel(), u);
        Map<CallKind, BigDecimal> byKind = hist.actualByCallKind().getOrDefault(Provenance.MEASURED, Map.of());
        BigDecimal measuredTotal = hist.actualTotal(Provenance.MEASURED);

        double c5 = prices.actualUsd(run5).doubleValue();
        double c4 = prices.actualUsd(run4).doubleValue();
        double cx = c5 / BATCH;
        double reasoningShare5 = prices.outPerToken() * run5.reasoningTokens() / c5;
        double outputShare5 = prices.outPerToken() * run5.completionTokens() / c5;
        double worst5 = prices.worstCaseUsd(run5.promptTokens(), run5.maxCompletionTokens()).doubleValue();

        // ---- extraction model scaled from run 5 (ESTIMATED): H_x cannot be separated, so all 924 tokens are per-message
        Extraction base = new Extraction(0, BATCH, 0, run5.promptTokens() / (double) BATCH, 0, run5.completionTokens(), 0,
                run5.maxCompletionTokens(), Provenance.ESTIMATED);

        RunnerFileAccess files = new RunnerFileAccess(p);
        List<String> devCorpora = List.of("parents-b", "building-he", "club-mixed", "family-en");
        Map<String, Corpus> corpora = new LinkedHashMap<>();
        for (String id : devCorpora) corpora.put(id, files.corpus(id));
        List<CorpusSize> sizes = new ArrayList<>();
        for (String id : devCorpora) sizes.add(new CorpusSize(id, List.of(corpora.get(id).messages().size())));
        sizes.add(new CorpusSize("dev corpora total (4 groups)", devCorpora.stream().map(id -> corpora.get(id).messages().size()).toList()));
        sizes.add(new CorpusSize("scenario 60 msgs (Stage 1c)", List.of(60)));
        sizes.add(new CorpusSize("scenario 100 msgs", List.of(100)));
        sizes.add(new CorpusSize("scenario 3000 msgs (large regime)", List.of(3000)));

        // ---- per-item input estimate (ESTIMATED from MEASURED_OFFLINE chars)
        FixedPrompt s = TokenEstimator.fixedPrompt();
        Map<String, Prompt> prompts = new LinkedHashMap<>();
        for (Prompt pr : files.prompts("dev")) prompts.put(pr.qid(), pr);
        Path ctxFile = p.results().resolve("stage0-contexts-dev.jsonl");
        Path scoreFile = p.results().resolve("stage0-retrieval-dev.json");
        Map<String, List<double[]>> perArm = new LinkedHashMap<>();   // arm -> [ctxChars, heShare, ctxLo, ctxHi, inLo, inHi]
        int checked = 0;
        for (String line : Files.readString(ctxFile, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            JsonNode r = Json.parse(line);
            String arm = r.get("arm").asText();
            if (!COST_ARMS.contains(arm)) continue;
            Prompt pr = prompts.get(r.get("qid").asText());
            if (pr == null) throw new IllegalStateException("context row for unknown qid");
            List<String> ids = Json.stringList(r.get("context_ids"));
            ContextBuilder.Built b = ContextBuilder.ofIds(corpora.get(pr.corpusId()), pr.asOf(), new LinkedHashSet<>(ids));
            if (b.chars() != r.get("context_chars").asInt() || !b.ids().equals(ids))
                throw new IllegalStateException("Stage 0 context no longer reproduces from corpora (stale results): " + pr.qid() + "/" + arm);
            checked++;
            List<String> texts = b.messages().stream().map(CorpusMessage::text).toList();
            int textChars = texts.stream().mapToInt(String::length).sum();
            int he = texts.stream().mapToInt(TokenEstimator::hebrewChars).sum();
            TokenRange ctx = TokenEstimator.context(b.json(), texts);
            TokenRange q = TokenEstimator.prose(pr.question()).plus(TokenEstimator.envelope(pr.asOf().toString().length()));
            TokenRange in = s.tokens().plus(q).plus(ctx);
            perArm.computeIfAbsent(arm, k -> new ArrayList<>()).add(new double[]{b.chars(), textChars == 0 ? 0 : he / (double) textChars,
                    ctx.lo(), ctx.hi(), in.lo(), in.hi()});
        }
        Map<String, ArmInput> armIn = new LinkedHashMap<>();
        for (String a : COST_ARMS) {
            List<double[]> rows = perArm.get(a);
            if (rows == null || rows.size() != prompts.size()) throw new IllegalStateException("missing Stage 0 rows for " + a);
            armIn.put(a, new ArmInput(a, rows.size(), mean(rows, 0), mean(rows, 1), new TokenRange(mean(rows, 2), mean(rows, 3)),
                    new TokenRange(mean(rows, 4), mean(rows, 5))));
        }
        JsonNode scores = Json.parse(Files.readString(scoreFile, StandardCharsets.UTF_8));
        for (String a : COST_ARMS) {   // cross-check against the scored aggregate
            double ref = scores.path("arms").path(a).path("context_chars_mean").asDouble();
            if (Math.abs(ref - armIn.get(a).ctxCharsMean()) > 0.051) throw new IllegalStateException("context chars disagree with Stage 0 scores for " + a);
        }

        double outLo = V_LO * (1 + RV_LO), outHi = V_HI * (1 + RV_HI), outMid = (outLo + outHi) / 2;
        double outUsdLo = outLo * prices.outPerToken(), outUsdHi = outHi * prices.outPerToken();
        ArmInput aB = armIn.get("B-weak control"), aC = armIn.get("C-today"), aA = armIn.get("A-full");
        double saveLo = (aB.inputTokens().lo() - aC.inputTokens().lo()) * prices.inPerToken();
        double saveHi = (aB.inputTokens().hi() - aC.inputTokens().hi()) * prices.inPerToken();
        double saveAcLo = (aA.inputTokens().lo() - aC.inputTokens().lo()) * prices.inPerToken();
        double saveAcHi = (aA.inputTokens().hi() - aC.inputTokens().hi()) * prices.inPerToken();

        // ---- E0 and Δ_req per size
        Map<String, double[]> e0 = new LinkedHashMap<>();   // label -> [central, lo, hi, f02, batches]
        for (CorpusSize cs : sizes) {
            double c = 0, f = 0; int nb = 0;
            for (int n : cs.groupSizes()) {
                Extraction e = base.withMessages(n);
                c += e.e0(prices); f += e.withTruncation(0.2).e0(prices); nb += e.batches();
            }
            e0.put(cs.label(), new double[]{c, c * BAND_LO, c * BAND_HI, f, nb});
        }

        // ---- calculator demonstration: 60-message scenario, C-rep proxy vs B-weak and A-full, quality unknown
        Inputs demo = new Inputs(prices, base.withMessages(60), Maintenance.NONE, List.of(
                Arm.simple("C-rep (proxy)", true, aC.inputTokens().mid(), outMid, Provenance.ESTIMATED),
                Arm.simple("B-weak control", false, aB.inputTokens().mid(), outMid, Provenance.ESTIMATED),
                Arm.simple("A-full", false, aA.inputTokens().mid(), outMid, Provenance.ESTIMATED)),
                "C-rep (proxy)", List.of("B-weak control", "A-full"), BreakEvenCalculator.DEFAULT_DELTA, null,
                new SteadyState(300, 10, RHO_W), null, Provenance.ESTIMATED);
        Result demoR = BreakEvenCalculator.compute(demo);
        List<double[]> steadyGrid = new ArrayList<>();   // λ, q, lhs, rhs, holds(0/1)
        for (double lambda : new double[]{50, 300, 2000})
            for (double q : new double[]{1, 10, 100}) {
                SteadyStateResult r = BreakEvenCalculator.steadyState(demo.withSteadyState(new SteadyState(lambda, q, RHO_W)), "B-weak control");
                steadyGrid.add(new double[]{lambda, q, r.lhsQuestionSavingPerDay(), r.rhsExtractionCostPerDay(), r.candidateKeepsLead() ? 1 : 0});
            }

        Ctx x = new Ctx(prices, usage, unledgered.get(0), run5, run4, c5, c4, cx, reasoningShare5, outputShare5, worst5, byKind, measuredTotal, s, armIn,
                outLo, outHi, outMid, outUsdLo, outUsdHi, saveLo, saveHi, saveAcLo, saveAcHi, sizes, e0, demoR, steadyGrid, scores,
                checked, Map.of("usage/recorded-usage.jsonl", Manifest.sha256(usageFile),
                        "usage/unledgered-usage.jsonl", Manifest.sha256(unledgeredFile),
                        "results/stage0-contexts-dev.jsonl", Manifest.sha256(ctxFile),
                        "results/stage0-retrieval-dev.json", Manifest.sha256(scoreFile)));
        return new Outputs(renderJson(x), renderMarkdown(x), renderSvg(x));
    }

    record Ctx(PriceTable prices, List<UsageRecord> usage, UsageRecord unledgered, UsageRecord run5, UsageRecord run4, double c5, double c4, double cx,
               double reasoningShare5, double outputShare5, double worst5, Map<CallKind, BigDecimal> byKind, BigDecimal measuredTotal,
               FixedPrompt s, Map<String, ArmInput> armIn, double outLo, double outHi, double outMid, double outUsdLo, double outUsdHi,
               double saveLo, double saveHi, double saveAcLo, double saveAcHi, List<CorpusSize> sizes, Map<String, double[]> e0,
               Result demo, List<double[]> steadyGrid, JsonNode scores, int contextsReproduced, Map<String, String> inputHashes) {}

    // =================================================================================================== JSON

    static String renderJson(Ctx x) throws IOException {
        ObjectNode root = Json.object();
        root.put("title", "TASK-039 preliminary cost analysis (offline)");
        root.put("status", "ESTIMATED - no answer call measured. Extraction figures come from one live call (n=1).");
        root.put("not_a_result", "No per-question answer cost, quality or CPCA has been measured. Nothing here says which arm costs less in practice.");
        ObjectNode pr = root.putObject("prices");
        pr.put("in_per_million", x.prices().inPerMillion()).put("out_per_million", x.prices().outPerMillion());
        pr.putNull("cached_in_per_million");
        pr.put("source", x.prices().source());
        ObjectNode ih = root.putObject("input_sha256");
        new java.util.TreeMap<>(x.inputHashes()).forEach(ih::put);
        root.put("stage0_contexts_reproduced_from_corpora", x.contextsReproduced());

        ObjectNode m = root.putObject("measured_extraction");
        m.set("run5_prompt_tokens", num(x.run5().promptTokens(), Provenance.MEASURED));
        m.set("run5_completion_tokens", num(x.run5().completionTokens(), Provenance.MEASURED));
        m.set("run5_reasoning_tokens", num(x.run5().reasoningTokens(), Provenance.MEASURED));
        m.set("run5_visible_tokens", num(x.run5().visibleTokens(), Provenance.MEASURED));
        m.set("run5_cost_usd", num(x.c5(), Provenance.MEASURED));
        m.set("run5_worst_case_usd", num(x.worst5(), Provenance.MEASURED));
        m.set("run5_latency_ms", num(x.run5().latencyMs(), Provenance.MEASURED));
        m.set("run5_reasoning_share_of_cost", num(x.reasoningShare5(), Provenance.MEASURED));
        m.set("run5_output_share_of_cost", num(x.outputShare5(), Provenance.MEASURED));
        m.set("c_x_usd_per_message", num(x.cx(), Provenance.MEASURED));
        m.put("c_x_note", "n=1, b=10, one synthetic corpus; derived from a single call, no variance");
        m.set("c_x_scenario_band_usd", range(x.cx() * BAND_LO, x.cx() * BAND_HI, Provenance.ESTIMATED));
        m.set("run4_cost_usd_zero_usable_output", num(x.c4(), Provenance.MEASURED));
        m.put("run4_finish_reason", x.run4().finishReason());
        ObjectNode tr = m.putObject("truncation_inflation_per_batch_usd");
        for (double f : new double[]{0.05, 0.1, 0.2}) tr.set("f=" + f, num(f / (1 - f) * x.c4(), Provenance.ESTIMATED));
        ObjectNode led = root.putObject("measured_ledger");
        for (Map.Entry<CallKind, BigDecimal> e : x.byKind().entrySet()) led.set(e.getKey().name().toLowerCase(Locale.ROOT) + "_usd", num(e.getValue().doubleValue(), Provenance.MEASURED));
        led.set("total_usd", num(x.measuredTotal().doubleValue(), Provenance.MEASURED));
        led.put("records", x.usage().size());
        led.put("note", "Runs 2-5 only, matching the tasks.md cost ledger. Run 1 has no recorded usage and is omitted.");
        ObjectNode un = root.putObject("unledgered_measured_call");
        un.put("file", "usage/unledgered-usage.jsonl");
        un.put("call_kind", x.unledgered().callKind().name());
        un.set("max_completion_tokens", num(x.unledgered().maxCompletionTokens(), Provenance.MEASURED));
        un.set("prompt_tokens", num(x.unledgered().promptTokens(), Provenance.MEASURED));
        un.set("completion_tokens", num(x.unledgered().completionTokens(), Provenance.MEASURED));
        un.set("cost_usd", num(x.prices().actualUsd(x.unledgered()).doubleValue(), Provenance.MEASURED));
        un.put("note", "A real connectivity call recorded in the dita-muse connectivity report but absent from the tasks.md cost ledger. Kept separate so the ledger file matches the ledger; not added to the ledger total.");

        ObjectNode sj = root.putObject("fixed_prompt_S");
        sj.set("chars", num(x.s().totalChars(), Provenance.MEASURED_OFFLINE));
        sj.set("prose_chars", num(x.s().proseChars(), Provenance.MEASURED_OFFLINE));
        sj.set("schema_chars", num(x.s().schemaChars(), Provenance.MEASURED_OFFLINE));
        sj.set("tokens", range(x.s().tokens().lo(), x.s().tokens().hi(), Provenance.ESTIMATED));

        ArrayNode e0 = root.putArray("extraction_E0");
        for (CorpusSize cs : x.sizes()) {
            double[] v = x.e0().get(cs.label());
            ObjectNode o = e0.addObject();
            o.put("corpus", cs.label());
            o.put("messages", cs.total());
            o.put("batches_b10", (int) v[4]);
            o.set("e0_usd", num(v[0], Provenance.ESTIMATED));
            o.set("e0_band_usd", range(v[1], v[2], Provenance.ESTIMATED));
            o.set("e0_usd_at_f_0_2", num(v[3], Provenance.ESTIMATED));
            ObjectNode dr = o.putObject("delta_req_usd_per_question");
            for (int q : Q) {
                ObjectNode d = dr.putObject("Q=" + q);
                d.set("central", num(v[0] / q, Provenance.ESTIMATED));
                d.set("band", range(v[1] / q, v[2] / q, Provenance.ESTIMATED));
            }
        }
        root.put("maintenance_M", "0 in this preliminary analysis (static corpus); the calculator supports new/correction/edit/deletion events");

        ObjectNode ai = root.putObject("estimated_input_per_question");
        for (ArmInput a : x.armIn().values()) {
            ObjectNode o = ai.putObject(a.arm());
            o.put("n", a.n());
            o.set("context_chars_mean", num(a.ctxCharsMean(), Provenance.MEASURED_OFFLINE));
            o.set("context_hebrew_share_mean", num(a.hebrewShareMean(), Provenance.MEASURED_OFFLINE));
            o.set("context_tokens", range(a.ctxTokens().lo(), a.ctxTokens().hi(), Provenance.ESTIMATED));
            o.set("input_tokens_incl_S_and_question", range(a.inputTokens().lo(), a.inputTokens().hi(), Provenance.ESTIMATED));
            o.set("input_usd", range(a.inputTokens().lo() * x.prices().inPerToken(), a.inputTokens().hi() * x.prices().inPerToken(), Provenance.ESTIMATED));
        }
        ObjectNode ob = root.putObject("answer_output_UNKNOWN_band");
        ob.put("not_a_measurement", true);
        ob.put("assumption", "V in [150, 700] visible tokens, R/V in [1, 3.5]; identical for every arm because no answer call exists");
        ob.set("output_tokens", range(x.outLo(), x.outHi(), Provenance.ESTIMATED));
        ob.set("output_usd", range(x.outUsdLo(), x.outUsdHi(), Provenance.ESTIMATED));
        ObjectNode sv = root.putObject("estimated_input_saving_per_question");
        sv.set("B-weak_minus_C-today_usd", range(x.saveLo(), x.saveHi(), Provenance.ESTIMATED));
        sv.set("A-full_minus_C-today_usd", range(x.saveAcLo(), x.saveAcHi(), Provenance.ESTIMATED));
        sv.put("caveat", "Input only. C-today needs no extraction. Using it as the C-rep context assumes notes add no tokens (optimistic for C-rep).");
        ArrayNode be = root.putArray("delta_req_vs_input_saving");
        for (CorpusSize cs : x.sizes()) {
            double[] v = x.e0().get(cs.label());
            ObjectNode o = be.addObject();
            o.put("corpus", cs.label());
            o.set("q_where_delta_req_equals_saving", range(v[1] / x.saveHi(), v[2] / x.saveLo(), Provenance.ESTIMATED));
            o.set("q_central", num(v[0] / ((x.saveLo() + x.saveHi()) / 2), Provenance.ESTIMATED));
            Integer g = firstGridQ(v[0], (x.saveLo() + x.saveHi()) / 2);
            if (g == null) o.putNull("first_grid_q_central_delta_req_below_saving"); else o.put("first_grid_q_central_delta_req_below_saving", g);
        }

        ObjectNode d = root.putObject("calculator_demo_60_msgs");
        d.put("scenario", "C-rep proxy (C-today input, notes not counted) vs B-weak control and A-full; output tokens equal for all arms (band midpoint); quality unknown; M=0; infrastructure unpriced");
        d.set("e0_usd", num(x.demo().e0().value(), x.demo().e0().provenance()));
        ObjectNode mac = d.putObject("mean_answer_cost_usd");
        x.demo().meanAnswerCost().forEach((k, v) -> mac.set(k, num(v.value(), v.provenance())));
        ArrayNode gr = d.putArray("grid");
        for (BreakEvenCalculator.GridRow r : x.demo().grid()) {
            ObjectNode o = gr.addObject();
            o.put("Q", r.q());
            ObjectNode t = o.putObject("total_cost_usd");
            r.totalCost().forEach((k, v) -> t.set(k, num(v.value(), v.provenance())));
            o.set("amortised_e0_per_q_usd", num(r.amortisedE0PerQ().value(), r.amortisedE0PerQ().provenance()));
            ObjectNode c = o.putObject("cpca");
            r.cpca().forEach((k, v) -> c.put(k, v.display()));
        }
        ArrayNode bes = d.putArray("break_even");
        for (BreakEven b : x.demo().breakEvens()) {
            ObjectNode o = bes.addObject();
            o.put("candidate", b.candidate()).put("baseline", b.baseline());
            if (b.analyticQ() == null) o.putNull("analytic_q"); else o.set("analytic_q", num(b.analyticQ(), b.provenance()));
            if (b.gridQ() == null) o.putNull("grid_q"); else o.put("grid_q", b.gridQ());
            o.put("quality", b.quality().name()).put("verdict", b.verdict()).put("provenance", b.provenance().name());
        }
        ArrayNode ss = d.putArray("steady_state_vs_B");
        for (double[] r : x.steadyGrid()) {
            ObjectNode o = ss.addObject();
            o.put("lambda_msgs_per_day", (int) r[0]).put("q_per_day", (int) r[1]).put("rho_w", RHO_W);
            o.set("lhs_q_times_saving_usd", num(r[2], Provenance.ESTIMATED));
            o.set("rhs_lambda_cx_1_plus_rho_w_usd", num(r[3], Provenance.ESTIMATED));
            o.put("candidate_keeps_lead", r[4] > 0);
        }
        ArrayNode sens = d.putArray("sensitivity_vs_B");
        for (SensitivityRow r : x.demo().sensitivity()) {
            ObjectNode o = sens.addObject();
            o.put("parameter", r.parameter()).put("low", r.lowSetting()).put("high", r.highSetting());
            putQ(o, "q_star_base", r.qStarBase()); putQ(o, "q_star_low", r.qStarLow()); putQ(o, "q_star_high", r.qStarHigh());
            o.set("diff_at_q25_low_usd", num(r.diffAtRefLow(), Provenance.ESTIMATED));
            o.set("diff_at_q25_high_usd", num(r.diffAtRefHigh(), Provenance.ESTIMATED));
            if (r.steadyMarginLow() != null) o.set("steady_margin_low_usd_per_day", num(r.steadyMarginLow(), Provenance.ESTIMATED));
            if (r.steadyMarginHigh() != null) o.set("steady_margin_high_usd_per_day", num(r.steadyMarginHigh(), Provenance.ESTIMATED));
        }

        ObjectNode qg = root.putObject("quality_guardrail");
        qg.put("provenance", Provenance.MEASURED_OFFLINE.name());
        qg.put("what", "Stage 0 retrieval-only context recall (dev n=35, synthetic, in-memory). Not answer quality.");
        for (String a : RECALL_ARMS) {
            JsonNode s = x.scores().path("arms").path(a);
            ObjectNode o = qg.putObject(a);
            o.set("context_recall", num(s.path("context_recall_mean").asDouble(), Provenance.MEASURED_OFFLINE));
            o.set("all_gold_within_t_ctx", num(s.path("all_gold_within_budget_rate").asDouble(), Provenance.MEASURED_OFFLINE));
        }
        qg.put("cpca", "not computable: no answer has been produced or scored, so K is unknown for every arm");
        return OUT.writeValueAsString(root) + "\n";
    }

    // =================================================================================================== Markdown

    static String renderMarkdown(Ctx x) {
        StringBuilder b = new StringBuilder();
        Map<String, ArmInput> ai = x.armIn();
        ArmInput aA = ai.get("A-full"), aAb = ai.get("A-budget"), aB = ai.get("B-weak control"), aC = ai.get("C-today");
        double pin = x.prices().inPerToken();
        double[] dev = x.e0().get("dev corpora total (4 groups)");
        b.append("# TASK-039: preliminary cost analysis (offline, ").append(TITLE_TAG).append(")\n\n");
        b.append("**Status: ESTIMATED. No answer call has ever been made, so no per-question answer cost, answer quality or CPCA is measured. ")
                .append("Extraction figures come from one live call (run 5, n=1). Nothing below says which arm costs less in practice; ")
                .append("it reports what the numbers show under the stated assumptions.**\n\n");
        b.append("Prices: $").append(x.prices().inPerMillion()).append("/M input, $").append(x.prices().outPerMillion())
                .append("/M output incl. reasoning (").append(x.prices().source()).append("). Cached-input price: unknown (cached tokens charged at the input price). ")
                .append("Infrastructure: unpriced (no $/month supplied), never folded into dollars. Maintenance M = 0 here (static corpus).\n\n");
        b.append("Provenance labels: MEASURED (live API usage), MEASURED_OFFLINE (local, no model), ESTIMATED (derived under stated assumptions). ")
                .append("Nothing here is REPLAYED or SIMULATED.\n\n");

        b.append("## 1. Measured extraction (run 5, n=1) and truncation waste (run 4)\n\n");
        b.append("| Figure | Value | Provenance |\n|---|---|---|\n");
        row(b, "Run 5 prompt / completion tokens", x.run5().promptTokens() + " / " + x.run5().completionTokens() + " (reasoning " + x.run5().reasoningTokens() + ", visible " + x.run5().visibleTokens() + ")", "MEASURED");
        row(b, "Run 5 cost (10 synthetic msgs, b=10, effort low)", exactUsd(x.prices().actualUsd(x.run5())) + " (ledger $0.013251)", "MEASURED");
        row(b, "Run 5 latency", x.run5().latencyMs() + " ms", "MEASURED");
        row(b, "Run 5 reasoning share / output share of cost", pct(x.reasoningShare5()) + " / " + pct(x.outputShare5()), "MEASURED");
        row(b, "c_x, extraction cost per message", usd(x.cx()) + " (n=1, no variance)", "MEASURED");
        row(b, "c_x scenario band (0.5x to 2x, not a CI)", usd(x.cx() * BAND_LO) + " to " + usd(x.cx() * BAND_HI), "ESTIMATED");
        row(b, "Run 4 cost, finish=length, zero usable output", exactUsd(x.prices().actualUsd(x.run4())), "MEASURED");
        row(b, "Truncation inflation per batch at f = 0.05 / 0.1 / 0.2 (f/(1-f) x run-4 worst case)",
                usd(0.05 / 0.95 * x.c4()) + " / " + usd(0.1 / 0.9 * x.c4()) + " / " + usd(0.2 / 0.8 * x.c4()), "ESTIMATED");
        row(b, "Ledgered live usage, runs 2-5 (" + x.usage().size() + " records; connectivity / extraction)",
                usd(x.byKind().getOrDefault(CallKind.CONNECTIVITY, BigDecimal.ZERO).doubleValue()) + " / "
                        + usd(x.byKind().getOrDefault(CallKind.EXTRACTION, BigDecimal.ZERO).doubleValue()) + " (total " + usd(x.measuredTotal().doubleValue()) + ")", "MEASURED");
        row(b, "Unledgered connectivity call (cap " + x.unledgered().maxCompletionTokens() + ", " + x.unledgered().promptTokens() + " / "
                + x.unledgered().completionTokens() + " tokens), not in the total above", usd(x.prices().actualUsd(x.unledgered()).doubleValue()), "MEASURED");
        b.append("\nRecorded live usage covers runs 2 to 5, matching the tasks.md cost ledger (usage/recorded-usage.jsonl). Run 1 has no recorded usage and is omitted. ")
                .append("One further real connectivity call appears in the dita-muse connectivity report but not in the tasks.md ledger; it is kept in usage/unledgered-usage.jsonl and reported on its own row.\n\n");

        b.append("## 2. Extraction E0 (ESTIMATED, scaled from run 5)\n\n");
        b.append("Batch model, b = 10: each batch is charged ").append(fmt(x.run5().promptTokens() / (double) BATCH)).append(" input tokens per message and ")
                .append(x.run5().completionTokens()).append(" output tokens per batch (run 5 cannot separate fixed overhead from per-message input, so H_x = 0 and a partial batch pays full output). ")
                .append("Each group is batched separately. The band is 0.5x to 2x of the central value. The f = 0.2 column adds (f/(1-f)) x the worst-case batch (4096 output tokens).\n\n");
        b.append("| Corpus | Messages | Batches | E0 central | E0 band | E0 at f = 0.2 | Provenance |\n|---|---|---|---|---|---|---|\n");
        for (CorpusSize cs : x.sizes()) {
            double[] v = x.e0().get(cs.label());
            b.append("| ").append(cs.label()).append(" | ").append(cs.total()).append(" | ").append((int) v[4]).append(" | ").append(usd(v[0]))
                    .append(" | ").append(usd(v[1])).append(" to ").append(usd(v[2])).append(" | ").append(usd(v[3])).append(" | ESTIMATED |\n");
        }
        b.append("\nDecoy groups are never queried and are not counted.\n\n");

        b.append("## 3. Required per-question saving, Delta_req(Q) = (E0 + M) / Q (ESTIMATED, M = 0)\n\n");
        b.append("| Corpus |");
        for (int q : Q) b.append(" Q=").append(q).append(" |");
        b.append("\n|---|");
        for (int ignored : Q) b.append("---|");
        b.append("\n");
        for (CorpusSize cs : x.sizes()) {
            double[] v = x.e0().get(cs.label());
            b.append("| ").append(cs.label()).append(" |");
            for (int q : Q) b.append(" ").append(usd(v[0] / q)).append(" (").append(usd(v[1] / q)).append(" to ").append(usd(v[2] / q)).append(") |");
            b.append("\n");
        }

        b.append("\n## 4. Estimated input-only cost per question (ESTIMATED from MEASURED_OFFLINE context sizes)\n\n");
        b.append("S (fixed system prompt + schema + empty user template) = ").append(x.s().totalChars()).append(" chars (")
                .append(x.s().proseChars()).append(" prose + ").append(x.s().schemaChars()).append(" schema JSON), MEASURED_OFFLINE from AnswerPrompt; ")
                .append(fmt(x.s().tokens().lo())).append(" to ").append(fmt(x.s().tokens().hi())).append(" tokens, ESTIMATED. ")
                .append("Chars per token: Hebrew 1.5 to 3.0, other prose 3.5 to 4.5, JSON envelope 3 to 4, applied to each item by its own Hebrew share. ")
                .append("Question and as_of are included. Server chat-template tokens are unknown and not counted. All ")
                .append(x.contextsReproduced()).append(" (question, arm) contexts were rebuilt from the corpora and matched the Stage 0 char counts exactly.\n\n");
        b.append("| Arm | Context chars mean | Hebrew share of message text | Context tokens | Input tokens (S + q + context) | Input $ per question | Provenance |\n|---|---|---|---|---|---|---|\n");
        for (ArmInput a : ai.values()) {
            b.append("| ").append(a.arm()).append(" | ").append(fmt(a.ctxCharsMean())).append(" | ").append(pct(a.hebrewShareMean())).append(" | ")
                    .append(rng(a.ctxTokens())).append(" | ").append(rng(a.inputTokens())).append(" | ")
                    .append(usd(a.inputTokens().lo() * pin)).append(" to ").append(usd(a.inputTokens().hi() * pin))
                    .append(" | chars MEASURED_OFFLINE; tokens and $ ESTIMATED |\n");
        }
        b.append("\n**Answer output cost is UNKNOWN and NOT a measurement.** Assumption band: V in [150, 700] visible tokens, R/V in [1, 3.5], so ")
                .append(fmt(x.outLo())).append(" to ").append(fmt(x.outHi())).append(" output tokens = ").append(usd(x.outUsdLo())).append(" to ").append(usd(x.outUsdHi()))
                .append(" per answer, for every arm. The width of this band (").append(usd(x.outUsdHi() - x.outUsdLo()))
                .append(") is larger than every input difference in the table above, so input-only comparisons cannot settle the sign of a total-cost difference.\n\n");

        b.append("## 5. Comparison table (A / B / C)\n\n");
        b.append("| Arm | Context chars mean | Input $ per question | Output $ per question | Extraction E0 (dev corpora) | Context recall | CPCA | Provenance |\n|---|---|---|---|---|---|---|---|\n");
        cmp(b, x, aA, "none", "chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band");
        cmp(b, x, aAb, "none", "chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band");
        cmp(b, x, aB, "none", "chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band (B-weak is a weak control, not real B)");
        cmp(b, x, aC, "none", "chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band");
        b.append("| C-rep (proxy, not built) | as C-today + notes (notes not counted) | ").append(usd(aC.inputTokens().lo() * pin)).append(" to ").append(usd(aC.inputTokens().hi() * pin))
                .append(" + note tokens | ").append(usd(x.outUsdLo())).append(" to ").append(usd(x.outUsdHi())).append(" (UNKNOWN) | ").append(usd(dev[0]))
                .append(" (").append(usd(dev[1])).append(" to ").append(usd(dev[2])).append(") | not measured | not computable | all ESTIMATED; no notes are replayed in this analysis |\n");

        b.append("\n## 6. Delta_req vs estimated input saving (ESTIMATED; input only; C-rep context assumed equal to C-today's)\n\n");
        b.append("Estimated input saving per question: B-weak minus C-today = ").append(usd(x.saveLo())).append(" to ").append(usd(x.saveHi()))
                .append("; A-full minus C-today = ").append(usd(x.saveAcLo())).append(" to ").append(usd(x.saveAcHi())).append(". ")
                .append("C-today itself needs no extraction, so for C-today this saving has no E0 to recover. The rows below ask how many questions a C-rep whose context were as small as C-today's would need before its input saving over B-weak repaid E0.\n\n");
        b.append("| Corpus | E0 central | Q where Delta_req = saving (band) | Q at central values | First grid Q with Delta_req(central) <= saving(mid) | Provenance |\n|---|---|---|---|---|---|\n");
        for (CorpusSize cs : x.sizes()) {
            double[] v = x.e0().get(cs.label());
            double mid = (x.saveLo() + x.saveHi()) / 2;
            Integer g = firstGridQ(v[0], mid);
            b.append("| ").append(cs.label()).append(" | ").append(usd(v[0])).append(" | ").append(fmt(v[1] / x.saveHi())).append(" to ").append(fmt(v[2] / x.saveLo()))
                    .append(" | ").append(fmt(v[0] / mid)).append(" | ").append(g == null ? "none for Q <= 100" : "Q=" + g).append(" | ESTIMATED |\n");
        }
        b.append("\nWhat the numbers show: Delta_req(Q) falls as 1/Q while the estimated input saving is a constant per question, so on input alone a crossover exists for every corpus size, at a Q that grows linearly with corpus size. ")
                .append("This ignores (a) note tokens that C-rep would add, (b) the output band, which is wider than the saving, and (c) quality: C-today's recall is lower than B-weak's (section 8).\n\n");

        b.append("## 7. Calculator demonstration (60-message scenario, ESTIMATED)\n\n");
        b.append("C-rep proxy (C-today input) vs B-weak control and A-full; output tokens set equal for all arms at the band midpoint (")
                .append(fmt(x.outMid())).append("), so output cancels in Q*; quality unknown; M = 0; infrastructure unpriced.\n\n");
        b.append("| Comparison | Analytic Q* | Grid Q* | Verdict | Provenance |\n|---|---|---|---|---|\n");
        for (BreakEven be : x.demo().breakEvens())
            b.append("| ").append(be.candidate()).append(" vs ").append(be.baseline()).append(" | ").append(be.analyticQ() == null ? "none" : fmt(be.analyticQ()))
                    .append(" | ").append(be.gridQ() == null ? "none" : String.valueOf(be.gridQ())).append(" | ").append(be.verdict()).append(" | ").append(be.provenance()).append(" |\n");
        b.append("\nSteady state vs B-weak, q x (c_B - c_C) > lambda x c_x x (1 + rho_w), rho_w = ").append(RHO_W).append(", c_x central:\n\n");
        b.append("| lambda (msgs/day) | q (questions/day) | q x saving per day | lambda x c_x x (1 + rho_w) | C keeps lead | Provenance |\n|---|---|---|---|---|---|\n");
        for (double[] r : x.steadyGrid())
            b.append("| ").append((int) r[0]).append(" | ").append((int) r[1]).append(" | ").append(usd(r[2])).append(" | ").append(usd(r[3])).append(" | ")
                    .append(r[4] > 0 ? "yes" : "no").append(" | ESTIMATED |\n");
        b.append("\nOne-at-a-time sensitivity of analytic Q* (C-rep proxy vs B-weak); base Q* = ")
                .append(x.demo().sensitivity().isEmpty() || x.demo().sensitivity().get(0).qStarBase() == null ? "none" : fmt(x.demo().sensitivity().get(0).qStarBase())).append(":\n\n");
        b.append("| Parameter | Low setting | Q* low | High setting | Q* high | Total C - Total B at Q=25, low / high | Steady-state margin per day (lambda=300, q=10), low / high |\n|---|---|---|---|---|---|---|\n");
        for (SensitivityRow r : x.demo().sensitivity())
            b.append("| ").append(r.parameter()).append(" | ").append(r.lowSetting()).append(" | ").append(r.qStarLow() == null ? "none" : fmt(r.qStarLow()))
                    .append(" | ").append(r.highSetting()).append(" | ").append(r.qStarHigh() == null ? "none" : fmt(r.qStarHigh())).append(" | ")
                    .append(usd(r.diffAtRefLow())).append(" / ").append(usd(r.diffAtRefHigh())).append(" | ")
                    .append(r.steadyMarginLow() == null ? "n/a" : usd(r.steadyMarginLow())).append(" / ")
                    .append(r.steadyMarginHigh() == null ? "n/a" : usd(r.steadyMarginHigh())).append(" |\n");
        b.append("\nWith output equal across arms, output-token rows leave Q* unchanged. That is an artefact of the assumption, not a finding: COST-DESIGN ranks answer reasoning tokens and their difference between arms as the dominant unknown. ")
                .append("A 20% change in the candidate's output tokens alone (row 2) moves the result more than any input-side parameter.\n\n");

        b.append("## 8. Quality guardrail (MEASURED_OFFLINE, retrieval only)\n\n");
        b.append("| Arm | Context recall | AllGold within T_ctx | Provenance |\n|---|---|---|---|\n");
        for (String a : RECALL_ARMS) {
            JsonNode s = x.scores().path("arms").path(a);
            b.append("| ").append(a).append(" | ").append(String.format(Locale.ROOT, "%.3f", s.path("context_recall_mean").asDouble())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.3f", s.path("all_gold_within_budget_rate").asDouble())).append(" | MEASURED_OFFLINE |\n");
        }
        b.append("\nThis is whether gold evidence reaches the context, not whether an answer is correct (dev n=35, synthetic, written by C's builder; B-weak is not real B). ")
                .append("**No CPCA can be computed until answers are produced and scored**: K is unknown for every arm. A lower input cost at lower recall is not a saving; CPCA decides.\n\n");

        b.append("## 9. Measured vs estimated breakdown\n\n");
        b.append("| Quantity | Status | Source |\n|---|---|---|\n");
        row(b, "Extraction usage and cost (one call, b=10)", "MEASURED (n=1)", "usage/recorded-usage.jsonl run 5");
        row(b, "Truncated extraction cost (one call)", "MEASURED (n=1)", "usage/recorded-usage.jsonl run 4");
        row(b, "c_x per message", "MEASURED (n=1); band ESTIMATED", "run 5 / 10 messages");
        row(b, "E0 for other corpus sizes, batch effects, fixed overhead", "ESTIMATED", "linear scaling of run 5");
        row(b, "Live spend, runs 2-5 (ledger) and one unledgered connectivity call", "MEASURED", "usage/recorded-usage.jsonl, usage/unledgered-usage.jsonl");
        row(b, "Context chars per arm, retrieval recall", "MEASURED_OFFLINE", "results/stage0-retrieval-dev.json");
        row(b, "S (system prompt + schema) chars", "MEASURED_OFFLINE", "AnswerPrompt");
        row(b, "Input tokens per question", "ESTIMATED (range)", "chars per token assumptions");
        row(b, "Answer output and reasoning tokens", "UNKNOWN (assumption band)", "no answer call exists");
        row(b, "Answer cost per question, total cost per arm", "NOT MEASURED", "needs a live answer run");
        row(b, "Answer quality, K, CPCA", "NOT MEASURED", "needs scored answers");
        row(b, "Maintenance M, infrastructure $", "NOT MODELLED here (0 / unpriced)", "calculator supports both");

        b.append("\n## 10. Limits and fairness notes\n\n");
        b.append("- One extraction call (n=1): no variance, one batch size, one effort level; reasoning may grow faster than linearly with batch size.\n");
        b.append("- The same output band is applied to every arm. If notes change reasoning tokens (Delta_R), that difference, not input size, decides cost; it is unmeasured.\n");
        b.append("- The C-rep proxy uses C-today's context and does not count note tokens, which favours C-rep.\n");
        b.append("- B-weak is a char-n-gram weak control, not the real arm B; the dev corpora were written by C's builder.\n");
        b.append("- Input-only figures overstate differences between arms because output is about 91% of the measured extraction cost.\n");
        b.append("- Chart: ").append(CHART_REL).append(" (title and legend say ").append(TITLE_TAG).append(").\n");
        b.append("\nSource: results/cost-prelim.json. Generated offline by app.dita.eval.cost.PreliminaryCostAnalysis.\n");
        return b.toString();
    }

    private static void cmp(StringBuilder b, Ctx x, ArmInput a, String e0, String prov) {
        double pin = x.prices().inPerToken();
        b.append("| ").append(a.arm()).append(" | ").append(fmt(a.ctxCharsMean())).append(" | ").append(usd(a.inputTokens().lo() * pin)).append(" to ")
                .append(usd(a.inputTokens().hi() * pin)).append(" | ").append(usd(x.outUsdLo())).append(" to ").append(usd(x.outUsdHi())).append(" (UNKNOWN) | ")
                .append(e0).append(" | ").append(String.format(Locale.ROOT, "%.3f", x.scores().path("arms").path(a.arm()).path("context_recall_mean").asDouble()))
                .append(" | not computable | ").append(prov).append(" |\n");
    }

    // =================================================================================================== SVG

    static String renderSvg(Ctx x) {
        double pin = x.prices().inPerToken();
        double[] dev = x.e0().get("dev corpora total (4 groups)");
        Map<String, ArmInput> ai = x.armIn();
        StringBuilder s = new StringBuilder();
        int W = 980, H = 860;
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(W).append("\" height=\"").append(H)
                .append("\" viewBox=\"0 0 ").append(W).append(' ').append(H).append("\" font-family=\"sans-serif\" font-size=\"12\">\n");
        s.append("<title>Cost vs questions: ").append(TITLE_TAG).append("</title>\n");
        s.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        s.append(text(20, 26, "Cost vs number of questions Q (dev corpora, 151 msgs) – " + TITLE_TAG, 15, "bold"));
        s.append(text(20, 44, "Prices $1.25/M in, $4.25/M out (user-supplied, unverified). Output per answer is an assumption band (V 150-700, R/V 1-3.5), identical for all arms. E0 scaled from one extraction call (n=1).", 11, null));

        // ---- panel 1: total cost bands
        double x0 = 90, x1 = 700, y0 = 90, y1 = 400;
        double ymax1 = niceCeil(Math.max(dev[2] + 100 * (ai.get("C-today").inputTokens().hi() * pin + x.outUsdHi()),
                100 * (ai.get("A-full").inputTokens().hi() * pin + x.outUsdHi())));
        axes(s, x0, x1, y0, y1, "Q (questions)", "total cost, USD");
        for (int i = 0; i <= 5; i++) {
            double v = ymax1 * i / 5, y = y1 - (y1 - y0) * i / 5;
            s.append(line(x0 - 4, y, x1, y, "#ddd", 1, null)).append(text(x0 - 8, y + 4, trimNum(v), 11, null, "end"));
        }
        xTicks(s, x0, x1, y1);
        String[][] arms = {{"A-full", "#1f77b4"}, {"B-weak control", "#2ca02c"}, {"C-today", "#ff7f0e"}};
        for (String[] a : arms) {
            TokenRange in = ai.get(a[0]).inputTokens();
            s.append(band(x0, x1, y0, y1, ymax1, 0, 0, in.lo() * pin + x.outUsdLo(), in.hi() * pin + x.outUsdHi(), a[1]));
        }
        TokenRange ci = ai.get("C-today").inputTokens();
        s.append(band(x0, x1, y0, y1, ymax1, dev[1], dev[2], ci.lo() * pin + x.outUsdLo(), ci.hi() * pin + x.outUsdHi(), "#d62728"));
        for (String[] a : arms) {   // input-only lines (mid), dashed
            double c = ai.get(a[0]).inputTokens().mid() * pin;
            s.append(line(x0, y1, x0 + (x1 - x0), y1 - (y1 - y0) * (100 * c) / ymax1, a[1], 2, "6,4"));
        }
        double lx = 715, ly = 100;
        s.append(text(lx, ly, "Legend (" + TITLE_TAG + ")", 11, "bold"));
        s.append(legend(lx, ly + 18, "#1f77b4", "A-full band: Q x (input range + output band)", false));
        s.append(legend(lx, ly + 36, "#2ca02c", "B-weak control band (weak control, not real B)", false));
        s.append(legend(lx, ly + 54, "#ff7f0e", "C-today band", false));
        s.append(legend(lx, ly + 72, "#d62728", "C-rep proxy band: E0 (0.5x-2x) + Q x c_ans", false));
        s.append(legend(lx, ly + 90, "#555", "dashed: input-only cost, Q x input (mid)", true));
        s.append(text(lx, ly + 116, "C-rep proxy = C-today context, notes not", 11, null));
        s.append(text(lx, ly + 130, "counted (optimistic for C-rep).", 11, null));
        s.append(text(lx, ly + 150, "Bands overlap: the output band dominates", 11, null));
        s.append(text(lx, ly + 164, "and is identical for every arm, so this", 11, null));
        s.append(text(lx, ly + 178, "chart cannot rank the arms.", 11, null));

        // ---- panel 2: Δ_req vs saving (log y)
        double py0 = 490, py1 = 800;
        double lmin = -5, lmax = 0;   // 1e-5 .. 1 USD
        s.append(text(20, 470, "Required per-question saving Delta_req(Q) = (E0 + M)/Q vs estimated input-only saving per question – " + TITLE_TAG, 13, "bold"));
        axes(s, x0, x1, py0, py1, "Q (questions)", "USD per question (log)");
        for (int e = (int) lmin; e <= lmax; e++) {
            double y = py1 - (py1 - py0) * (e - lmin) / (lmax - lmin);
            s.append(line(x0 - 4, y, x1, y, "#ddd", 1, null)).append(text(x0 - 8, y + 4, "1e" + e, 11, null, "end"));
        }
        xTicks(s, x0, x1, py1);
        // saving bands (horizontal)
        s.append(hband(x0, x1, py0, py1, lmin, lmax, x.saveLo(), x.saveHi(), "#2ca02c"));
        s.append(hband(x0, x1, py0, py1, lmin, lmax, x.saveAcLo(), x.saveAcHi(), "#1f77b4"));
        // Δ_req band for dev total, and central lines for sizes
        StringBuilder poly = new StringBuilder();
        for (int q = 1; q <= 100; q++) poly.append(pt(qx(x0, x1, q), ly(py0, py1, lmin, lmax, dev[2] / q))).append(' ');
        for (int q = 100; q >= 1; q--) poly.append(pt(qx(x0, x1, q), ly(py0, py1, lmin, lmax, dev[1] / q))).append(' ');
        s.append("<polygon points=\"").append(poly.toString().trim()).append("\" fill=\"#d62728\" fill-opacity=\"0.15\" stroke=\"none\"/>\n");
        String[][] sizes = {{"parents-b", "#9467bd"}, {"building-he", "#8c564b"}, {"scenario 60 msgs (Stage 1c)", "#e377c2"}, {"dev corpora total (4 groups)", "#d62728"}};
        for (String[] z : sizes) {
            double e0 = x.e0().get(z[0])[0];
            StringBuilder pl = new StringBuilder();
            for (int q = 1; q <= 100; q++) pl.append(pt(qx(x0, x1, q), ly(py0, py1, lmin, lmax, e0 / q))).append(' ');
            s.append("<polyline points=\"").append(pl.toString().trim()).append("\" fill=\"none\" stroke=\"").append(z[1]).append("\" stroke-width=\"2\"/>\n");
        }
        double lx2 = 715, ly2 = 500;
        s.append(text(lx2, ly2, "Legend (" + TITLE_TAG + ")", 11, "bold"));
        s.append(legend(lx2, ly2 + 18, "#2ca02c", "input saving B-weak - C-today (band)", false));
        s.append(legend(lx2, ly2 + 36, "#1f77b4", "input saving A-full - C-today (band)", false));
        s.append(legend(lx2, ly2 + 54, "#d62728", "Delta_req, dev total 151 msgs (+0.5x-2x)", false));
        s.append(legend(lx2, ly2 + 72, "#e377c2", "Delta_req, 60-msg scenario", false));
        s.append(legend(lx2, ly2 + 90, "#8c564b", "Delta_req, building-he (42 msgs)", false));
        s.append(legend(lx2, ly2 + 108, "#9467bd", "Delta_req, parents-b (25 msgs)", false));
        s.append(text(lx2, ly2 + 134, "Input only. Output differences are", 11, null));
        s.append(text(lx2, ly2 + 148, "unknown; the output band is wider", 11, null));
        s.append(text(lx2, ly2 + 162, "than either saving band. M = 0.", 11, null));
        s.append(text(lx2, ly2 + 182, "No quality term: no CPCA exists yet.", 11, null));
        s.append("</svg>\n");
        return s.toString();
    }

    private static double qx(double x0, double x1, double q) { return x0 + (x1 - x0) * q / 100.0; }
    private static double ly(double y0, double y1, double lmin, double lmax, double v) {
        double l = Math.log10(Math.max(v, Math.pow(10, lmin)));
        l = Math.min(lmax, l);
        return y1 - (y1 - y0) * (l - lmin) / (lmax - lmin);
    }

    private static String band(double x0, double x1, double y0, double y1, double ymax, double f0lo, double f0hi, double slopeLo, double slopeHi, String color) {
        StringBuilder p = new StringBuilder();
        p.append(pt(x0, yv(y0, y1, ymax, f0hi))).append(' ').append(pt(x1, yv(y0, y1, ymax, f0hi + 100 * slopeHi))).append(' ')
                .append(pt(x1, yv(y0, y1, ymax, f0lo + 100 * slopeLo))).append(' ').append(pt(x0, yv(y0, y1, ymax, f0lo)));
        return "<polygon points=\"" + p + "\" fill=\"" + color + "\" fill-opacity=\"0.18\" stroke=\"" + color + "\" stroke-width=\"1\"/>\n";
    }

    private static String hband(double x0, double x1, double y0, double y1, double lmin, double lmax, double lo, double hi, String color) {
        double ya = ly(y0, y1, lmin, lmax, hi), yb = ly(y0, y1, lmin, lmax, lo);
        return "<rect x=\"" + f1(x0) + "\" y=\"" + f1(ya) + "\" width=\"" + f1(x1 - x0) + "\" height=\"" + f1(Math.max(1, yb - ya))
                + "\" fill=\"" + color + "\" fill-opacity=\"0.25\" stroke=\"" + color + "\"/>\n";
    }

    private static double yv(double y0, double y1, double ymax, double v) { return y1 - (y1 - y0) * Math.min(v, ymax) / ymax; }

    private static void axes(StringBuilder s, double x0, double x1, double y0, double y1, String xl, String yl) {
        s.append(line(x0, y1, x1, y1, "#000", 1, null)).append(line(x0, y0, x0, y1, "#000", 1, null));
        s.append(text((x0 + x1) / 2, y1 + 36, xl, 12, null, "middle"));
        s.append("<text x=\"20\" y=\"").append(f1((y0 + y1) / 2)).append("\" font-size=\"12\" transform=\"rotate(-90 20 ")
                .append(f1((y0 + y1) / 2)).append(")\" text-anchor=\"middle\">").append(esc(yl)).append("</text>\n");
    }

    private static void xTicks(StringBuilder s, double x0, double x1, double y1) {
        for (int q : Q) {
            double x = qx(x0, x1, q);
            s.append(line(x, y1, x, y1 + 5, "#000", 1, null)).append(text(x, y1 + 18, String.valueOf(q), 11, null, "middle"));
        }
    }

    private static String legend(double x, double y, String color, String label, boolean dashed) {
        return (dashed ? line(x, y - 4, x + 22, y - 4, color, 2, "6,4")
                : "<rect x=\"" + f1(x) + "\" y=\"" + f1(y - 10) + "\" width=\"22\" height=\"10\" fill=\"" + color + "\" fill-opacity=\"0.35\" stroke=\"" + color + "\"/>\n")
                + text(x + 28, y, label, 11, null);
    }

    private static String line(double xa, double ya, double xb, double yb, String color, double w, String dash) {
        return "<line x1=\"" + f1(xa) + "\" y1=\"" + f1(ya) + "\" x2=\"" + f1(xb) + "\" y2=\"" + f1(yb) + "\" stroke=\"" + color
                + "\" stroke-width=\"" + trimNum(w) + "\"" + (dash == null ? "" : " stroke-dasharray=\"" + dash + "\"") + "/>\n";
    }

    private static String text(double x, double y, String t, int size, String weight) { return text(x, y, t, size, weight, null); }
    private static String text(double x, double y, String t, int size, String weight, String anchor) {
        return "<text x=\"" + f1(x) + "\" y=\"" + f1(y) + "\" font-size=\"" + size + "\"" + (weight == null ? "" : " font-weight=\"" + weight + "\"")
                + (anchor == null ? "" : " text-anchor=\"" + anchor + "\"") + ">" + esc(t) + "</text>\n";
    }

    private static String pt(double x, double y) { return f1(x) + "," + f1(y); }
    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static double niceCeil(double v) {
        double e = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) if (m * e >= v) return m * e;
        return 10 * e;
    }

    // =================================================================================================== helpers

    static Integer firstGridQ(double e0, double saving) {
        for (int q : Q) if (e0 / q <= saving) return q;
        return null;
    }

    private static UsageRecord byLabel(List<UsageRecord> us, String label) {
        return us.stream().filter(u -> u.runLabel().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException("recorded usage lacks " + label));
    }

    static void verifyUsageManifest(BenchPaths p) throws IOException {
        Path dir = p.bench().resolve("usage");
        List<String> errors = new ArrayList<>();
        Map<String, String> m = Manifest.parse(Files.readString(dir.resolve("MANIFEST.sha256"), StandardCharsets.UTF_8), errors);
        if (!errors.isEmpty()) throw new IllegalStateException("usage manifest malformed");
        for (String f : List.of("recorded-usage.jsonl", "unledgered-usage.jsonl")) {
            String h = m.get(f);
            if (h == null || !h.equals(Manifest.sha256(dir.resolve(f)))) throw new IllegalStateException("usage file does not match usage/MANIFEST.sha256: " + f);
        }
    }

    private static double mean(List<double[]> rows, int i) { return rows.stream().mapToDouble(r -> r[i]).average().orElseThrow(); }

    /** JSON number rounded to 8 decimal places (also keeps every digit run short enough for the PII scan). */
    static ObjectNode num(double v, Provenance p) {
        ObjectNode o = Json.object();
        o.put("value", r8(v));
        o.put("provenance", p.name());
        return o;
    }

    static ObjectNode range(double lo, double hi, Provenance p) {
        ObjectNode o = Json.object();
        o.put("lo", r8(lo)).put("hi", r8(hi));
        o.put("provenance", p.name());
        return o;
    }

    private static void putQ(ObjectNode o, String f, Double v) { if (v == null) o.putNull(f); else o.put(f, r8(v)); }

    static BigDecimal r8(double v) {
        BigDecimal b = BigDecimal.valueOf(v);
        if (Math.abs(v) >= 1) b = b.setScale(4, RoundingMode.HALF_UP);
        else {
            b = b.round(new MathContext(6, RoundingMode.HALF_UP));
            if (b.scale() > 8) b = b.setScale(8, RoundingMode.HALF_UP);
        }
        b = b.stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0) : b;
    }

    static String usd(double v) {
        if (v == 0) return "$0";
        String sign = v < 0 ? "-" : "";
        double a = Math.abs(v);
        String body = a >= 0.01 ? String.format(Locale.ROOT, "%.4f", a) : BigDecimal.valueOf(a).round(new MathContext(3, RoundingMode.HALF_UP)).toPlainString();
        return sign + "$" + body;
    }

    /** A MEASURED dollar amount at full precision, so it can be checked against the cost ledger. */
    static String exactUsd(BigDecimal v) { return "$" + v.stripTrailingZeros().toPlainString(); }

    static String fmt(double v) {
        if (Math.abs(v) >= 100) return String.format(Locale.ROOT, "%.0f", v);
        if (Math.abs(v) >= 10) return String.format(Locale.ROOT, "%.1f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String rng(TokenRange t) { return fmt(t.lo()) + " to " + fmt(t.hi()); }
    private static String pct(double v) { return String.format(Locale.ROOT, "%.1f%%", 100 * v); }
    private static String trimNum(double v) { return BigDecimal.valueOf(v).round(new MathContext(3)).stripTrailingZeros().toPlainString(); }
    private static void row(StringBuilder b, String a, String v, String p) { b.append("| ").append(a).append(" | ").append(v).append(" | ").append(p).append(" |\n"); }

    // =================================================================================================== entry point

    /** Writes the three outputs, then runs the N-58 scan over all cost outputs. Exit code 1 on any finding. */
    public static void main(String[] args) throws IOException {
        BenchPaths p = BenchPaths.locate();
        Outputs o = analyse(p);
        Path json = p.results().resolve("cost-prelim.json"), md = p.results().resolve("cost-prelim.md"), svg = p.bench().resolve(CHART_REL);
        Files.createDirectories(svg.getParent());
        Files.writeString(json, o.json(), StandardCharsets.UTF_8);
        Files.writeString(md, o.markdown(), StandardCharsets.UTF_8);
        Files.writeString(svg, o.svg(), StandardCharsets.UTF_8);
        System.out.println("wrote " + p.rel(json) + ", " + p.rel(md) + ", " + p.rel(svg));
        List<CostOutputScan.Finding> f = CostOutputScan.run(p);
        System.out.println("CostOutputScan: " + (f.isEmpty() ? "PASS" : "FAIL") + ", findings=" + f.size());
        f.forEach(x -> System.out.println("  " + x));
        if (!f.isEmpty()) System.exit(1);
    }
}
