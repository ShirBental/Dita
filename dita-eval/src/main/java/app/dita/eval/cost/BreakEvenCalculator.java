package app.dita.eval.cost;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Pure, deterministic break-even calculator (COST-DESIGN §2–3). No I/O, no clock, no randomness. Every emitted number
 * carries a {@link Provenance}: the weakest label among the inputs it depends on (N-57).
 * <ul>
 *   <li>E0(N, b) = Σ_batches [P_in·(H_x + Σt_m + overlap) + P_out·(V+R)], each batch inflated by
 *       (f/(1−f))·worst-case, worst-case = P_in·(batch input) + P_out·max_completion_tokens.</li>
 *   <li>M = ⌈Δnew/b_inc⌉·c̄_b + Δcorrection·c̄_b + Δedit·c̄_b + Δdel_E·u_E·c̄_b + Δdel_L·0, where c̄_b = E0 / batches.
 *       Each correction, edit and rebuilding early deletion is charged one re-extracted batch.</li>
 *   <li>c̄_X = Σ_classes s·(P_in·in + P_out·out); with answer truncation rate f_a: (1−f_a)·c̄ + f_a·(P_in·in + P_out·cap)
 *       (a truncated answer is paid in full and not retried).</li>
 *   <li>TotalCost_X(Q) = [X uses extraction ? E0 + M : 0] + Q·c̄_X + [infra priced ? infra·months : 0]. Infrastructure
 *       with a null $/month is unpriced and never folded into dollars.</li>
 *   <li>CPCA_X(Q) = TotalCost_X(Q) / K, K = correct_rate·Q. K = 0 gives "infinite / no qualifying answers", never 0.</li>
 *   <li>Q* = (F_C − F_X)/(c̄_X − c̄_C) only if c̄_X &gt; c̄_C; the grid Q* is the smallest grid Q with Total_C ≤ Total_X.
 *       A break-even is quality-qualified only if quality_C ≥ quality_X − δ; otherwise a cost-only crossover is labelled.</li>
 *   <li>Steady state: C keeps its lead only if q·(c̄_B − c̄_C) &gt; λ·c_x·(1+ρw).</li>
 * </ul>
 */
public final class BreakEvenCalculator {
    public static final int[] Q_GRID = {1, 5, 10, 25, 50, 100};
    public static final double DEFAULT_DELTA = 0.05;
    public static final int SENSITIVITY_REFERENCE_Q = 25;
    static final double EPS = 1e-12;

    private BreakEvenCalculator() {}

    // ------------------------------------------------------------------ inputs

    /** Extraction batch model. {@code truncationRate} f in [0, 1). */
    public record Extraction(int messages, int batchSize, double fixedOverheadTokens, double tokensPerMessage,
                             double overlapTokens, double outputTokensPerBatch, double truncationRate,
                             int maxCompletionTokens, Provenance provenance) {
        public Extraction {
            if (messages < 0 || batchSize <= 0) throw new IllegalArgumentException("messages >= 0 and batch size > 0");
            if (fixedOverheadTokens < 0 || tokensPerMessage < 0 || overlapTokens < 0 || outputTokensPerBatch < 0)
                throw new IllegalArgumentException("token figures must be >= 0");
            if (truncationRate < 0 || truncationRate >= 1) throw new IllegalArgumentException("truncation rate must be in [0, 1)");
            if (maxCompletionTokens <= 0) throw new IllegalArgumentException("max_completion_tokens must be > 0");
            Objects.requireNonNull(provenance);
        }
        public static final Extraction NONE = new Extraction(0, 1, 0, 0, 0, 0, 0, 1, Provenance.ESTIMATED);

        public int batches() { return (messages + batchSize - 1) / batchSize; }

        double batchInput(int m) { return fixedOverheadTokens + m * tokensPerMessage + overlapTokens; }

        double batchCost(PriceTable p, int m) {
            double base = p.inPerToken() * batchInput(m) + p.outPerToken() * outputTokensPerBatch;
            double worst = p.inPerToken() * batchInput(m) + p.outPerToken() * maxCompletionTokens;
            return base + truncationRate / (1 - truncationRate) * worst;
        }

        public double e0(PriceTable p) {
            double sum = 0;
            int left = messages;
            while (left > 0) { int m = Math.min(batchSize, left); sum += batchCost(p, m); left -= m; }
            return sum;
        }

        /** c̄_b: mean cost of one batch; a full batch if there are no messages. */
        public double meanBatchCost(PriceTable p) { return batches() == 0 ? batchCost(p, batchSize) : e0(p) / batches(); }

        /** c_x: extraction cost per message. */
        public double perMessageCost(PriceTable p) { return messages == 0 ? batchCost(p, batchSize) / batchSize : e0(p) / messages; }

        public Extraction withMessages(int n) { return new Extraction(n, batchSize, fixedOverheadTokens, tokensPerMessage, overlapTokens, outputTokensPerBatch, truncationRate, maxCompletionTokens, provenance); }
        public Extraction withBatchSize(int b) { return new Extraction(messages, b, fixedOverheadTokens, tokensPerMessage, overlapTokens, outputTokensPerBatch, truncationRate, maxCompletionTokens, provenance); }
        public Extraction withOverhead(double h) { return new Extraction(messages, batchSize, h, tokensPerMessage, overlapTokens, outputTokensPerBatch, truncationRate, maxCompletionTokens, provenance); }
        public Extraction withOutput(double o) { return new Extraction(messages, batchSize, fixedOverheadTokens, tokensPerMessage, overlapTokens, o, truncationRate, maxCompletionTokens, provenance); }
        public Extraction withTruncation(double f) { return new Extraction(messages, batchSize, fixedOverheadTokens, tokensPerMessage, overlapTokens, outputTokensPerBatch, f, maxCompletionTokens, provenance); }
        public Extraction withInputScale(double k) { return new Extraction(messages, batchSize, fixedOverheadTokens * k, tokensPerMessage * k, overlapTokens * k, outputTokensPerBatch, truncationRate, maxCompletionTokens, provenance); }
    }

    /** Maintenance events over the horizon. {@code rebuildOnEarlyDeletion} is the policy u_E ∈ {0, 1}. */
    public record Maintenance(int newMessages, int incrementalBatchSize, int corrections, int edits, int earlyDeletions,
                              int rebuildOnEarlyDeletion, int lateDeletions) {
        public Maintenance {
            if (newMessages < 0 || corrections < 0 || edits < 0 || earlyDeletions < 0 || lateDeletions < 0) throw new IllegalArgumentException("counts must be >= 0");
            if (incrementalBatchSize <= 0) throw new IllegalArgumentException("incremental batch size must be > 0");
            if (rebuildOnEarlyDeletion != 0 && rebuildOnEarlyDeletion != 1) throw new IllegalArgumentException("u_E must be 0 or 1");
        }
        public static final Maintenance NONE = new Maintenance(0, 1, 0, 0, 0, 0, 0);

        /** Number of re-extracted batches. Late deletions are flag-and-exclude ($0 API). */
        public int batches() {
            return (newMessages + incrementalBatchSize - 1) / incrementalBatchSize + corrections + edits + earlyDeletions * rebuildOnEarlyDeletion;
        }
        public double cost(double meanBatchCost) { return batches() * meanBatchCost; }
    }

    /** One answer class (replace / augment / fallback, or a single class with share 1). */
    public record AnswerClass(String name, double share, double inputTokens, double outputTokens) {
        public AnswerClass {
            Objects.requireNonNull(name);
            if (share < 0 || share > 1 || inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("bad answer class");
        }
        double cost(PriceTable p) { return p.inPerToken() * inputTokens + p.outPerToken() * outputTokens; }
    }

    /**
     * An arm. {@code qualityScore} (e.g. composite correctness) and {@code correctRate} (share of answers counted in K)
     * are null until answers are scored. {@code infraUsdPerMonth} null = unpriced.
     */
    public record Arm(String name, boolean usesExtraction, List<AnswerClass> classes, double answerTruncationRate,
                      int answerMaxCompletionTokens, Double qualityScore, Double correctRate, Double infraUsdPerMonth,
                      Provenance provenance) {
        public Arm {
            Objects.requireNonNull(name); Objects.requireNonNull(provenance);
            classes = List.copyOf(classes);
            if (classes.isEmpty()) throw new IllegalArgumentException("arm needs at least one answer class");
            double s = classes.stream().mapToDouble(AnswerClass::share).sum();
            if (Math.abs(s - 1) > 1e-9) throw new IllegalArgumentException("answer class shares must sum to 1 (got " + s + ")");
            if (answerTruncationRate < 0 || answerTruncationRate >= 1) throw new IllegalArgumentException("answer truncation rate must be in [0, 1)");
            if (answerTruncationRate > 0 && answerMaxCompletionTokens <= 0) throw new IllegalArgumentException("answer cap needed when truncation > 0");
            if (correctRate != null && (correctRate < 0 || correctRate > 1)) throw new IllegalArgumentException("correct rate must be in [0, 1]");
            if (infraUsdPerMonth != null && infraUsdPerMonth < 0) throw new IllegalArgumentException("infra $/month must be >= 0");
        }

        public static Arm simple(String name, boolean usesExtraction, double inputTokens, double outputTokens, Provenance p) {
            return new Arm(name, usesExtraction, List.of(new AnswerClass("all", 1, inputTokens, outputTokens)), 0, 0, null, null, null, p);
        }

        public double meanAnswerCost(PriceTable p) {
            double c = 0, worst = 0;
            for (AnswerClass k : classes) {
                c += k.share() * k.cost(p);
                worst += k.share() * (p.inPerToken() * k.inputTokens() + p.outPerToken() * answerMaxCompletionTokens);
            }
            return answerTruncationRate == 0 ? c : (1 - answerTruncationRate) * c + answerTruncationRate * worst;
        }

        public Arm withQuality(Double quality, Double rate) { return new Arm(name, usesExtraction, classes, answerTruncationRate, answerMaxCompletionTokens, quality, rate, infraUsdPerMonth, provenance); }
        public Arm withInfra(Double usdPerMonth) { return new Arm(name, usesExtraction, classes, answerTruncationRate, answerMaxCompletionTokens, qualityScore, correctRate, usdPerMonth, provenance); }
        public Arm withTruncation(double f, int cap) { return new Arm(name, usesExtraction, classes, f, cap, qualityScore, correctRate, infraUsdPerMonth, provenance); }
        public Arm mapClasses(UnaryOperator<AnswerClass> f) { return new Arm(name, usesExtraction, classes.stream().map(f).toList(), answerTruncationRate, answerMaxCompletionTokens, qualityScore, correctRate, infraUsdPerMonth, provenance); }
        public Arm withClasses(List<AnswerClass> cs) { return new Arm(name, usesExtraction, cs, answerTruncationRate, answerMaxCompletionTokens, qualityScore, correctRate, infraUsdPerMonth, provenance); }
    }

    /** λ messages/day, q questions/day, ρw re-extraction factor. */
    public record SteadyState(double messagesPerDay, double questionsPerDay, double reextractionFactor) {
        public SteadyState { if (messagesPerDay < 0 || questionsPerDay < 0 || reextractionFactor < 0) throw new IllegalArgumentException("steady-state inputs must be >= 0"); }
    }

    /**
     * All calculator inputs. {@code candidate} is the C arm; {@code baselines} are compared against it (B first, then A).
     * {@code infraMonths} null means infrastructure is not folded in at all.
     */
    public record Inputs(PriceTable prices, Extraction extraction, Maintenance maintenance, List<Arm> arms, String candidate,
                         List<String> baselines, double delta, Double infraMonths, SteadyState steadyState, int[] qGrid,
                         Provenance maintenanceProvenance) {
        public Inputs {
            Objects.requireNonNull(prices); Objects.requireNonNull(extraction); Objects.requireNonNull(maintenance);
            arms = List.copyOf(arms); baselines = List.copyOf(baselines);
            qGrid = qGrid == null ? Q_GRID.clone() : qGrid.clone();
            if (maintenanceProvenance == null) maintenanceProvenance = Provenance.ESTIMATED;
            if (arms.stream().map(Arm::name).distinct().count() != arms.size()) throw new IllegalArgumentException("duplicate arm names");
            if (arms.stream().noneMatch(a -> a.name().equals(candidate))) throw new IllegalArgumentException("candidate arm missing");
            for (String b : baselines) if (arms.stream().noneMatch(a -> a.name().equals(b))) throw new IllegalArgumentException("baseline arm missing: " + b);
            for (int q : qGrid) if (q <= 0) throw new IllegalArgumentException("Q must be > 0");
            if (delta < 0) throw new IllegalArgumentException("delta must be >= 0");
        }

        public Arm arm(String name) { return arms.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow(); }

        public Inputs withExtraction(Extraction e) { return new Inputs(prices, e, maintenance, arms, candidate, baselines, delta, infraMonths, steadyState, qGrid, maintenanceProvenance); }
        public Inputs withMaintenance(Maintenance m) { return new Inputs(prices, extraction, m, arms, candidate, baselines, delta, infraMonths, steadyState, qGrid, maintenanceProvenance); }
        public Inputs withArms(List<Arm> a) { return new Inputs(prices, extraction, maintenance, a, candidate, baselines, delta, infraMonths, steadyState, qGrid, maintenanceProvenance); }
        public Inputs withSteadyState(SteadyState s) { return new Inputs(prices, extraction, maintenance, arms, candidate, baselines, delta, infraMonths, s, qGrid, maintenanceProvenance); }
        public Inputs mapArms(UnaryOperator<Arm> f) { return withArms(arms.stream().map(f).toList()); }
        public Inputs mapArm(String name, UnaryOperator<Arm> f) { return withArms(arms.stream().map(a -> a.name().equals(name) ? f.apply(a) : a).toList()); }
    }

    // ------------------------------------------------------------------ outputs

    public record Num(double value, Provenance provenance) {}

    public enum CpcaStatus { OK, INFINITE_NO_QUALIFYING_ANSWERS, NOT_COMPUTABLE_ANSWERS_NOT_SCORED }

    public record Cpca(Double usd, CpcaStatus status, Provenance provenance) {
        public String display() {
            return switch (status) {
                case OK -> String.valueOf(usd);
                case INFINITE_NO_QUALIFYING_ANSWERS -> "infinite / no qualifying answers";
                case NOT_COMPUTABLE_ANSWERS_NOT_SCORED -> "not computable (answers not scored)";
            };
        }
    }

    public record GridRow(int q, Map<String, Num> totalCost, Num amortisedE0PerQ, Num requiredSavingPerQ, Map<String, Cpca> cpca) {}

    public enum QualityStatus { QUALIFIED, FAILS_QUALITY, QUALITY_UNKNOWN }

    /** {@code analyticQ} null = no break-even exists (c̄_X ≤ c̄_C); {@code gridQ} null = no crossover inside the grid. */
    public record BreakEven(String candidate, String baseline, Double analyticQ, Integer gridQ, QualityStatus quality,
                            boolean qualityQualified, String verdict, Provenance provenance) {}

    public record SteadyStateResult(double lhsQuestionSavingPerDay, double rhsExtractionCostPerDay, boolean candidateKeepsLead,
                                    Provenance provenance) {}

    public record SensitivityRow(String parameter, String lowSetting, String highSetting, Double qStarBase, Double qStarLow,
                                 Double qStarHigh, double diffAtRefBase, double diffAtRefLow, double diffAtRefHigh,
                                 Double steadyMarginLow, Double steadyMarginHigh) {}

    public record Result(Num e0, Num maintenance, Num meanBatchCost, Num perMessageExtraction, Map<String, Num> meanAnswerCost,
                         Map<String, Num> fixedCost, List<String> unpricedInfraArms, List<GridRow> grid,
                         List<BreakEven> breakEvens, SteadyStateResult steadyState, List<SensitivityRow> sensitivity) {}

    // ------------------------------------------------------------------ computation

    public static double totalCost(Inputs in, Arm a, int q) { return fixed(in, a) + q * a.meanAnswerCost(in.prices()); }

    static double fixed(Inputs in, Arm a) {
        double f = 0;
        if (a.usesExtraction()) {
            double e0 = in.extraction().e0(in.prices());
            f += e0 + in.maintenance().cost(in.extraction().meanBatchCost(in.prices()));
        }
        if (in.infraMonths() != null && a.infraUsdPerMonth() != null) f += a.infraUsdPerMonth() * in.infraMonths();
        return f;
    }

    static Provenance armProvenance(Inputs in, Arm a) {
        Provenance p = a.provenance();
        if (a.usesExtraction()) p = Provenance.weakest(Provenance.weakest(p, in.extraction().provenance()), in.maintenanceProvenance());
        return p;
    }

    public static Cpca cpca(double totalCost, Double correctRate, int q, Provenance p) {
        if (correctRate == null) return new Cpca(null, CpcaStatus.NOT_COMPUTABLE_ANSWERS_NOT_SCORED, p);
        return cpcaForCount(totalCost, correctRate * q, p);
    }

    /** CPCA for an observed count K of qualifying answers. K = 0 is never shown as 0. */
    public static Cpca cpcaForCount(double totalCost, double k, Provenance p) {
        if (k < 0) throw new IllegalArgumentException("K must be >= 0");
        if (k < EPS) return new Cpca(null, CpcaStatus.INFINITE_NO_QUALIFYING_ANSWERS, p);
        return new Cpca(totalCost / k, CpcaStatus.OK, p);
    }

    /** Analytic Q*: (F_C − F_X)/(c̄_X − c̄_C) if c̄_X &gt; c̄_C (clamped at 0), else null (none). */
    public static Double analyticQStar(Inputs in, Arm c, Arm x) {
        double cc = c.meanAnswerCost(in.prices()), cx = x.meanAnswerCost(in.prices());
        double df = fixed(in, c) - fixed(in, x);
        if (cx - cc > EPS) return Math.max(0, df / (cx - cc));
        if (Math.abs(cx - cc) <= EPS && df <= EPS) return 0.0;
        return null;
    }

    public static Integer gridQStar(Inputs in, Arm c, Arm x) {
        for (int q : in.qGrid()) if (totalCost(in, c, q) <= totalCost(in, x, q) + EPS) return q;
        return null;
    }

    public static BreakEven breakEven(Inputs in, String candidate, String baseline) {
        Arm c = in.arm(candidate), x = in.arm(baseline);
        Double analytic = analyticQStar(in, c, x);
        Integer grid = gridQStar(in, c, x);
        QualityStatus qs;
        if (c.qualityScore() == null || x.qualityScore() == null) qs = QualityStatus.QUALITY_UNKNOWN;
        else qs = c.qualityScore() >= x.qualityScore() - in.delta() - EPS ? QualityStatus.QUALIFIED : QualityStatus.FAILS_QUALITY;
        boolean qualified = qs == QualityStatus.QUALIFIED && grid != null;
        int maxQ = Arrays.stream(in.qGrid()).max().orElse(0);
        String verdict;
        if (qualified) verdict = "quality-qualified break-even at Q=" + grid;
        else if (grid != null) verdict = "no quality-qualified break-even; cost-only crossover at Q=" + grid
                + (qs == QualityStatus.QUALITY_UNKNOWN ? " (quality unknown)" : " (quality_" + candidate + " < quality_" + baseline + " - delta)");
        else verdict = "no quality-qualified break-even; no cost crossover for Q <= " + maxQ
                + (analytic == null ? " (none exists: candidate's per-question cost is not lower)" : "");
        return new BreakEven(candidate, baseline, analytic, grid, qs, qualified, verdict,
                Provenance.weakest(armProvenance(in, c), armProvenance(in, x)));
    }

    public static SteadyStateResult steadyState(Inputs in, String baseline) {
        if (in.steadyState() == null) return null;
        Arm c = in.arm(in.candidate()), b = in.arm(baseline);
        double lhs = in.steadyState().questionsPerDay() * (b.meanAnswerCost(in.prices()) - c.meanAnswerCost(in.prices()));
        double rhs = in.steadyState().messagesPerDay() * in.extraction().perMessageCost(in.prices()) * (1 + in.steadyState().reextractionFactor());
        return new SteadyStateResult(lhs, rhs, lhs > rhs + EPS,
                Provenance.weakest(Provenance.weakest(c.provenance(), b.provenance()), in.extraction().provenance()));
    }

    public static Result compute(Inputs in) { return compute(in, true); }

    static Result compute(Inputs in, boolean withSensitivity) {
        PriceTable p = in.prices();
        Provenance ep = in.extraction().provenance();
        double e0 = in.extraction().e0(p);
        double cb = in.extraction().meanBatchCost(p);
        double m = in.maintenance().cost(cb);
        Map<String, Num> mean = new LinkedHashMap<>(), fixed = new LinkedHashMap<>();
        List<String> unpriced = new ArrayList<>();
        for (Arm a : in.arms()) {
            mean.put(a.name(), new Num(a.meanAnswerCost(p), a.provenance()));
            fixed.put(a.name(), new Num(fixed(in, a), armProvenance(in, a)));
            if (a.infraUsdPerMonth() == null || in.infraMonths() == null) unpriced.add(a.name());
        }
        List<GridRow> grid = new ArrayList<>();
        Provenance fixedProv = Provenance.weakest(ep, in.maintenanceProvenance());
        for (int q : in.qGrid()) {
            Map<String, Num> tot = new LinkedHashMap<>();
            Map<String, Cpca> cp = new LinkedHashMap<>();
            for (Arm a : in.arms()) {
                double t = totalCost(in, a, q);
                Provenance ap = armProvenance(in, a);
                tot.put(a.name(), new Num(t, ap));
                cp.put(a.name(), cpca(t, a.correctRate(), q, ap));
            }
            grid.add(new GridRow(q, tot, new Num(e0 / q, ep), new Num((e0 + m) / q, fixedProv), cp));
        }
        List<BreakEven> bes = new ArrayList<>();
        for (String b : in.baselines()) bes.add(breakEven(in, in.candidate(), b));
        SteadyStateResult ss = in.baselines().isEmpty() ? null : steadyState(in, in.baselines().get(0));
        List<SensitivityRow> sens = withSensitivity && !in.baselines().isEmpty() ? sensitivity(in, in.baselines().get(0)) : List.of();
        return new Result(new Num(e0, ep), new Num(m, fixedProv), new Num(cb, ep), new Num(in.extraction().perMessageCost(p), ep),
                mean, fixed, unpriced, grid, bes, ss, sens);
    }

    // ------------------------------------------------------------------ sensitivity

    /** A one-at-a-time parameter perturbation. */
    public record Perturbation(String parameter, String lowSetting, UnaryOperator<Inputs> low, String highSetting, UnaryOperator<Inputs> high) {}

    /** The default sweep, in COST-DESIGN §3's estimated order of dominance. */
    public static List<Perturbation> defaultPerturbations(Inputs in) {
        String c = in.candidate();
        List<Perturbation> out = new ArrayList<>();
        out.add(new Perturbation("answer output tokens (all arms)", "x0.5", i -> i.mapArms(a -> a.mapClasses(k -> scaleOut(k, 0.5))),
                "x2", i -> i.mapArms(a -> a.mapClasses(k -> scaleOut(k, 2)))));
        out.add(new Perturbation("answer output tokens (candidate only; arm difference)", "x0.8", i -> i.mapArm(c, a -> a.mapClasses(k -> scaleOut(k, 0.8))),
                "x1.2", i -> i.mapArm(c, a -> a.mapClasses(k -> scaleOut(k, 1.2)))));
        out.add(new Perturbation("extraction output tokens (reasoning)", "x0.5", i -> i.withExtraction(i.extraction().withOutput(i.extraction().outputTokensPerBatch() * 0.5)),
                "x2", i -> i.withExtraction(i.extraction().withOutput(i.extraction().outputTokensPerBatch() * 2))));
        out.add(new Perturbation("extraction truncation rate f", "f=0", i -> i.withExtraction(i.extraction().withTruncation(0)),
                "f=0.2", i -> i.withExtraction(i.extraction().withTruncation(0.2))));
        out.add(new Perturbation("extraction batch size b", "b/2", i -> i.withExtraction(i.extraction().withBatchSize(Math.max(1, i.extraction().batchSize() / 2))),
                "2b", i -> i.withExtraction(i.extraction().withBatchSize(i.extraction().batchSize() * 2))));
        out.add(new Perturbation("extraction fixed overhead H_x", "H_x=0", i -> i.withExtraction(i.extraction().withOverhead(0)),
                "H_x+500 tokens", i -> i.withExtraction(i.extraction().withOverhead(i.extraction().fixedOverheadTokens() + 500))));
        if (in.steadyState() != null) {
            out.add(new Perturbation("q/lambda", "x0.5", i -> i.withSteadyState(scaleQ(i.steadyState(), 0.5)),
                    "x2", i -> i.withSteadyState(scaleQ(i.steadyState(), 2))));
        }
        Arm ca = in.arm(c);
        boolean mixture = ca.classes().stream().anyMatch(k -> k.name().equals("replace")) && ca.classes().stream().anyMatch(k -> k.name().equals("fallback"));
        if (mixture) {
            out.add(new Perturbation("s_r (replace share, moved from/to fallback)", "s_r-0.1", i -> i.mapArm(c, a -> shiftReplace(a, -0.1)),
                    "s_r+0.1", i -> i.mapArm(c, a -> shiftReplace(a, 0.1))));
        }
        out.add(new Perturbation("chars per token (input only)", "x1.25 (fewer tokens)", i -> scaleInputs(i, 0.8),
                "x0.8 (more tokens)", i -> scaleInputs(i, 1.25)));
        return out;
    }

    public static List<SensitivityRow> sensitivity(Inputs in, String baseline) { return sensitivity(in, baseline, defaultPerturbations(in)); }

    public static List<SensitivityRow> sensitivity(Inputs in, String baseline, List<Perturbation> ps) {
        Double qBase = analyticQStar(in, in.arm(in.candidate()), in.arm(baseline));
        double dBase = diffAtRef(in, baseline);
        List<SensitivityRow> rows = new ArrayList<>();
        for (Perturbation p : ps) {
            Inputs lo = p.low().apply(in), hi = p.high().apply(in);
            SteadyStateResult sl = steadyState(lo, baseline), sh = steadyState(hi, baseline);
            rows.add(new SensitivityRow(p.parameter(), p.lowSetting(), p.highSetting(), qBase,
                    analyticQStar(lo, lo.arm(lo.candidate()), lo.arm(baseline)), analyticQStar(hi, hi.arm(hi.candidate()), hi.arm(baseline)),
                    dBase, diffAtRef(lo, baseline), diffAtRef(hi, baseline),
                    sl == null ? null : sl.lhsQuestionSavingPerDay() - sl.rhsExtractionCostPerDay(),
                    sh == null ? null : sh.lhsQuestionSavingPerDay() - sh.rhsExtractionCostPerDay()));
        }
        return rows;
    }

    /** TotalCost_C − TotalCost_baseline at Q = {@value #SENSITIVITY_REFERENCE_Q} (negative = candidate lower). */
    static double diffAtRef(Inputs in, String baseline) {
        return totalCost(in, in.arm(in.candidate()), SENSITIVITY_REFERENCE_Q) - totalCost(in, in.arm(baseline), SENSITIVITY_REFERENCE_Q);
    }

    private static AnswerClass scaleOut(AnswerClass k, double f) { return new AnswerClass(k.name(), k.share(), k.inputTokens(), k.outputTokens() * f); }
    private static AnswerClass scaleIn(AnswerClass k, double f) { return new AnswerClass(k.name(), k.share(), k.inputTokens() * f, k.outputTokens()); }
    private static SteadyState scaleQ(SteadyState s, double f) { return new SteadyState(s.messagesPerDay(), s.questionsPerDay() * f, s.reextractionFactor()); }

    private static Inputs scaleInputs(Inputs i, double f) {
        return i.mapArms(a -> a.mapClasses(k -> scaleIn(k, f))).withExtraction(i.extraction().withInputScale(f));
    }

    private static Arm shiftReplace(Arm a, double d) {
        double sr = 0, sf = 0;
        for (AnswerClass k : a.classes()) { if (k.name().equals("replace")) sr = k.share(); if (k.name().equals("fallback")) sf = k.share(); }
        double dd = d > 0 ? Math.min(d, sf) : -Math.min(-d, sr);
        List<AnswerClass> out = new ArrayList<>();
        for (AnswerClass k : a.classes()) {
            if (k.name().equals("replace")) out.add(new AnswerClass(k.name(), k.share() + dd, k.inputTokens(), k.outputTokens()));
            else if (k.name().equals("fallback")) out.add(new AnswerClass(k.name(), k.share() - dd, k.inputTokens(), k.outputTokens()));
            else out.add(k);
        }
        return a.withClasses(out);
    }
}
