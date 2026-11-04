package app.dita.eval.cost;

import app.dita.eval.cost.BreakEvenCalculator.AnswerClass;
import app.dita.eval.cost.BreakEvenCalculator.Arm;
import app.dita.eval.cost.BreakEvenCalculator.BreakEven;
import app.dita.eval.cost.BreakEvenCalculator.Cpca;
import app.dita.eval.cost.BreakEvenCalculator.CpcaStatus;
import app.dita.eval.cost.BreakEvenCalculator.Extraction;
import app.dita.eval.cost.BreakEvenCalculator.GridRow;
import app.dita.eval.cost.BreakEvenCalculator.Inputs;
import app.dita.eval.cost.BreakEvenCalculator.Maintenance;
import app.dita.eval.cost.BreakEvenCalculator.QualityStatus;
import app.dita.eval.cost.BreakEvenCalculator.Result;
import app.dita.eval.cost.BreakEvenCalculator.SensitivityRow;
import app.dita.eval.cost.BreakEvenCalculator.SteadyState;
import app.dita.eval.cost.BreakEvenCalculator.SteadyStateResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hand-computed break-even cases. Test prices: P_in = $1/M (1e-6 per token), P_out = $4/M (4e-6 per token). All test
 * inputs are synthetic (labelled ESTIMATED or SIMULATED), never measurements.
 *
 * Base extraction EX: N=25, b=10, H_x=100, t_m=50, overlap=0, V+R=1000 per batch, f=0, cap 2000.
 *   batches = ceil(25/10) = 3 (sizes 10, 10, 5)
 *   batch(10): input 100 + 10*50 = 600 -> 0.0006; output 1000*4e-6 = 0.004; total 0.0046
 *   batch(5):  input 100 +  5*50 = 350 -> 0.00035 + 0.004 = 0.00435
 *   E0 = 2*0.0046 + 0.00435 = 0.01355; c̄_b = 0.01355/3 = 0.0045166667; c_x = 0.01355/25 = 0.000542
 * Arms: C (uses extraction) in 1000 / out 1000 -> 0.001 + 0.004 = 0.005
 *       B in 3000 / out 1000 -> 0.003 + 0.004 = 0.007
 *       A in 6000 / out 1000 -> 0.006 + 0.004 = 0.010
 */
class BreakEvenCalculatorTest {
    static final double TOL = 1e-12;
    static final PriceTable P = new PriceTable(new BigDecimal("1.00"), new BigDecimal("4.00"), null, "test prices (synthetic)");
    static final Extraction EX = new Extraction(25, 10, 100, 50, 0, 1000, 0, 2000, Provenance.ESTIMATED);
    static final Arm C = Arm.simple("C", true, 1000, 1000, Provenance.ESTIMATED);
    static final Arm B = Arm.simple("B", false, 3000, 1000, Provenance.ESTIMATED);
    static final Arm A = Arm.simple("A", false, 6000, 1000, Provenance.ESTIMATED);

    static Inputs base() {
        return new Inputs(P, EX, Maintenance.NONE, List.of(C, B, A), "C", List.of("B", "A"), 0.05, null, null, null, Provenance.ESTIMATED);
    }

    @Test void extractionBatchModel() {
        assertEquals(3, EX.batches());
        assertEquals(0.01355, EX.e0(P), TOL);
        assertEquals(0.01355 / 3, EX.meanBatchCost(P), TOL);
        assertEquals(0.000542, EX.perMessageCost(P), TOL);
        assertEquals(0, EX.withMessages(0).e0(P), TOL);
    }

    @Test void truncationInflatesEachBatchByFOverOneMinusFTimesWorstCase() {
        // f = 0.2 -> f/(1-f) = 0.25.
        // worst(10) = 0.0006 + 2000*4e-6 = 0.0086 -> +0.00215 per full batch
        // worst(5)  = 0.00035 + 0.008   = 0.00835 -> +0.0020875
        // E0 = 0.01355 + 2*0.00215 + 0.0020875 = 0.0199375
        assertEquals(0.0199375, EX.withTruncation(0.2).e0(P), TOL);
        assertThrows(IllegalArgumentException.class, () -> EX.withTruncation(1.0));
    }

    @Test void answerCostsAndTotalsPerArm() {
        Inputs in = base();
        assertEquals(0.005, C.meanAnswerCost(P), TOL);
        assertEquals(0.007, B.meanAnswerCost(P), TOL);
        assertEquals(0.010, A.meanAnswerCost(P), TOL);
        // Q=10: C = 0.01355 + 10*0.005 = 0.06355; B = 0.07; A = 0.10
        assertEquals(0.06355, BreakEvenCalculator.totalCost(in, C, 10), TOL);
        assertEquals(0.07, BreakEvenCalculator.totalCost(in, B, 10), TOL);
        assertEquals(0.10, BreakEvenCalculator.totalCost(in, A, 10), TOL);
    }

    @Test void gridHasEveryQAndAmortisedE0() {
        Result r = BreakEvenCalculator.compute(base());
        assertEquals(List.of(1, 5, 10, 25, 50, 100), r.grid().stream().map(GridRow::q).toList());
        GridRow q25 = r.grid().get(3);
        // E0/Q = 0.01355/25 = 0.000542; Δ_req = (E0 + 0)/25 = 0.000542
        assertEquals(0.000542, q25.amortisedE0PerQ().value(), TOL);
        assertEquals(0.000542, q25.requiredSavingPerQ().value(), TOL);
        // Q=100: C = 0.01355 + 0.5 = 0.51355
        assertEquals(0.51355, r.grid().get(5).totalCost().get("C").value(), TOL);
        assertEquals(Provenance.ESTIMATED, q25.totalCost().get("C").provenance());
    }

    @Test void analyticAndGridBreakEven() {
        Inputs in = base();
        // C vs B: Q* = 0.01355 / (0.007 - 0.005) = 6.775. Grid: Q=5: C 0.03855 > B 0.035; Q=10: 0.06355 <= 0.07 -> 10
        BreakEven cb = BreakEvenCalculator.breakEven(in, "C", "B");
        assertEquals(6.775, cb.analyticQ(), 1e-9);
        assertEquals(10, cb.gridQ());
        // C vs A: Q* = 0.01355 / 0.005 = 2.71. Grid: Q=1: 0.01855 > 0.01; Q=5: 0.03855 <= 0.05 -> 5
        BreakEven ca = BreakEvenCalculator.breakEven(in, "C", "A");
        assertEquals(2.71, ca.analyticQ(), 1e-9);
        assertEquals(5, ca.gridQ());
    }

    @Test void qualityQualifiedRule() {
        // delta = 0.05: quality_C >= quality_B - 0.05
        Inputs ok = base().mapArm("C", a -> a.withQuality(0.76, null)).mapArm("B", a -> a.withQuality(0.80, null));
        BreakEven q = BreakEvenCalculator.breakEven(ok, "C", "B");   // 0.76 >= 0.75
        assertEquals(QualityStatus.QUALIFIED, q.quality());
        assertTrue(q.qualityQualified());
        assertEquals("quality-qualified break-even at Q=10", q.verdict());

        Inputs edge = base().mapArm("C", a -> a.withQuality(0.75, null)).mapArm("B", a -> a.withQuality(0.80, null));
        assertTrue(BreakEvenCalculator.breakEven(edge, "C", "B").qualityQualified());   // exactly at the margin

        Inputs bad = base().mapArm("C", a -> a.withQuality(0.70, null)).mapArm("B", a -> a.withQuality(0.80, null));
        BreakEven f = BreakEvenCalculator.breakEven(bad, "C", "B");    // 0.70 < 0.75
        assertEquals(QualityStatus.FAILS_QUALITY, f.quality());
        assertFalse(f.qualityQualified());
        assertTrue(f.verdict().startsWith("no quality-qualified break-even; cost-only crossover at Q=10"), f.verdict());

        BreakEven u = BreakEvenCalculator.breakEven(base(), "C", "B");   // no quality figures at all
        assertEquals(QualityStatus.QUALITY_UNKNOWN, u.quality());
        assertFalse(u.qualityQualified());
        assertEquals("no quality-qualified break-even; cost-only crossover at Q=10 (quality unknown)", u.verdict());
    }

    @Test void noCrossoverWhenCandidatePerQuestionCostIsNotLower() {
        // C in 3000 / out 1100 -> 0.003 + 0.0044 = 0.0074 > B's 0.007: no analytic Q*, no grid Q*
        Inputs in = base().withArms(List.of(Arm.simple("C", true, 3000, 1100, Provenance.ESTIMATED), B, A));
        BreakEven be = BreakEvenCalculator.breakEven(in, "C", "B");
        assertNull(be.analyticQ());
        assertNull(be.gridQ());
        assertTrue(be.verdict().contains("no cost crossover for Q <= 100"));
        assertTrue(be.verdict().contains("none exists"));
        // Equal per-question cost (in 3000 / out 1000 = 0.007) with E0 > 0: still none
        Inputs eq = base().withArms(List.of(Arm.simple("C", true, 3000, 1000, Provenance.ESTIMATED), B, A));
        assertNull(BreakEvenCalculator.breakEven(eq, "C", "B").analyticQ());
    }

    @Test void crossoverBeyondTheGridIsAnalyticOnly() {
        // C in 2990 / out 1000 -> 0.00699; saving 0.00001/question; Q* = 0.01355/0.00001 = 1355 > 100
        Inputs in = base().withArms(List.of(Arm.simple("C", true, 2990, 1000, Provenance.ESTIMATED), B, A));
        BreakEven be = BreakEvenCalculator.breakEven(in, "C", "B");
        assertEquals(1355, be.analyticQ(), 1e-6);
        assertNull(be.gridQ());
        assertFalse(be.verdict().contains("none exists"));
    }

    @Test void cpcaUsesKAndNeverShowsZeroForKZero() {
        Inputs in = base().mapArm("B", a -> a.withQuality(0.8, 0.8)).mapArm("C", a -> a.withQuality(0.0, 0.0));
        Result r = BreakEvenCalculator.compute(in);
        GridRow q10 = r.grid().get(2);
        // B: total 0.07, K = 0.8*10 = 8 -> 0.00875
        assertEquals(CpcaStatus.OK, q10.cpca().get("B").status());
        assertEquals(0.00875, q10.cpca().get("B").usd(), TOL);
        // C: K = 0 -> infinite, never 0
        Cpca c = q10.cpca().get("C");
        assertEquals(CpcaStatus.INFINITE_NO_QUALIFYING_ANSWERS, c.status());
        assertNull(c.usd());
        assertEquals("infinite / no qualifying answers", c.display());
        // A: not scored
        assertEquals(CpcaStatus.NOT_COMPUTABLE_ANSWERS_NOT_SCORED, q10.cpca().get("A").status());
        // Observed counts: 0.5 / 0 -> infinite; 0.5 / 4 = 0.125
        assertEquals(CpcaStatus.INFINITE_NO_QUALIFYING_ANSWERS, BreakEvenCalculator.cpcaForCount(0.5, 0, Provenance.SIMULATED).status());
        assertEquals(0.125, BreakEvenCalculator.cpcaForCount(0.5, 4, Provenance.SIMULATED).usd(), TOL);
    }

    @Test void maintenanceAndDeletionPolicy() {
        double cb = 0.01355 / 3;
        // new 12 at b_inc 10 -> 2 batches; 1 correction -> 1; 1 edit -> 1; 2 early deletions with u_E=1 -> 2; 3 late -> 0
        Maintenance m1 = new Maintenance(12, 10, 1, 1, 2, 1, 3);
        assertEquals(6, m1.batches());
        assertEquals(6 * cb, m1.cost(cb), TOL);                  // 0.0271
        assertEquals(0.0271, m1.cost(cb), 1e-12);
        // u_E = 0: early deletions are not rebuilt -> 4 batches = 0.0180666...
        Maintenance m0 = new Maintenance(12, 10, 1, 1, 2, 0, 3);
        assertEquals(4, m0.batches());
        assertEquals(4 * cb, m0.cost(cb), TOL);
        // late deletions alone cost $0 API (flag and exclude)
        assertEquals(0, new Maintenance(0, 10, 0, 0, 0, 1, 5).cost(cb), TOL);
        assertThrows(IllegalArgumentException.class, () -> new Maintenance(0, 10, 0, 0, 1, 2, 0));

        // In the totals: Q* = (0.01355 + 0.0271)/0.002 = 20.325; grid: Q=10 0.09065 > 0.07, Q=25 0.16565 <= 0.175 -> 25
        Inputs in1 = base().withMaintenance(m1);
        assertEquals(0.09065, BreakEvenCalculator.totalCost(in1, C, 10), TOL);
        BreakEven be1 = BreakEvenCalculator.breakEven(in1, "C", "B");
        assertEquals(20.325, be1.analyticQ(), 1e-9);
        assertEquals(25, be1.gridQ());
        // u_E = 0: Q* = (0.01355 + 4*0.01355/3)/0.002 = 15.808333...
        assertEquals((0.01355 + 4 * cb) / 0.002, BreakEvenCalculator.breakEven(base().withMaintenance(m0), "C", "B").analyticQ(), 1e-9);
        // maintenance never applies to arms without extraction
        assertEquals(0.07, BreakEvenCalculator.totalCost(in1, B, 10), TOL);
    }

    @Test void classMixtureForUnevenBenefit() {
        // replace 0.3: 500/600 -> 0.0005 + 0.0024 = 0.0029
        // augment 0.2: 1500/1000 -> 0.0015 + 0.004 = 0.0055
        // fallback 0.5: 1000/1000 -> 0.005
        // E[c] = 0.3*0.0029 + 0.2*0.0055 + 0.5*0.005 = 0.00087 + 0.0011 + 0.0025 = 0.00447
        Arm mix = new Arm("C", true, List.of(new AnswerClass("replace", 0.3, 500, 600), new AnswerClass("augment", 0.2, 1500, 1000),
                new AnswerClass("fallback", 0.5, 1000, 1000)), 0, 0, null, null, null, Provenance.ESTIMATED);
        assertEquals(0.00447, mix.meanAnswerCost(P), TOL);
        assertThrows(IllegalArgumentException.class, () -> new Arm("X", false, List.of(new AnswerClass("replace", 0.3, 1, 1),
                new AnswerClass("fallback", 0.6, 1, 1)), 0, 0, null, null, null, Provenance.ESTIMATED));
        // s_r appears in the sweep only for mixtures; +0.1 moves share from fallback: 0.4*0.0029 + 0.2*0.0055 + 0.4*0.005 = 0.00426
        Inputs in = base().withArms(List.of(mix, B, A));
        SensitivityRow sr = BreakEvenCalculator.sensitivity(in, "B").stream().filter(r -> r.parameter().startsWith("s_r")).findFirst().orElseThrow();
        assertEquals(0.01355 / (0.007 - 0.00426), sr.qStarHigh(), 1e-9);
        assertEquals(0.01355 / (0.007 - 0.00468), sr.qStarLow(), 1e-9);   // 0.2*0.0029 + 0.2*0.0055 + 0.6*0.005 = 0.00468
    }

    @Test void truncatedAnswersArePaidInFullNotRetried() {
        // B with f_a = 0.1, cap 2048: worst = 0.003 + 2048*4e-6 = 0.011192; mean = 0.9*0.007 + 0.1*0.011192 = 0.0074192
        assertEquals(0.0074192, B.withTruncation(0.1, 2048).meanAnswerCost(P), TOL);
    }

    @Test void infrastructureIsNeverFoldedInUnlessPriced() {
        Inputs unpriced = base().mapArm("C", a -> a.withInfra(10.0));          // infraMonths null -> not folded in
        Result r = BreakEvenCalculator.compute(unpriced);
        assertEquals(0.01355, r.fixedCost().get("C").value(), TOL);
        assertTrue(r.unpricedInfraArms().containsAll(List.of("C", "B", "A")));
        Inputs priced = new Inputs(P, EX, Maintenance.NONE, List.of(C.withInfra(10.0), B, A), "C", List.of("B"), 0.05, 2.0, null, null, null);
        Result rp = BreakEvenCalculator.compute(priced);
        assertEquals(20.01355, rp.fixedCost().get("C").value(), TOL);        // 0.01355 + 10 $/month * 2 months
        assertEquals(List.of("B", "A"), rp.unpricedInfraArms());
    }

    @Test void steadyStateCondition() {
        // lhs = q*(c_B - c_C) = 10*0.002 = 0.02; rhs = λ*c_x*(1+ρw) = 300*0.000542*1.1 = 0.17886 -> C does not keep its lead
        SteadyStateResult s = BreakEvenCalculator.steadyState(base().withSteadyState(new SteadyState(300, 10, 0.1)), "B");
        assertEquals(0.02, s.lhsQuestionSavingPerDay(), TOL);
        assertEquals(0.17886, s.rhsExtractionCostPerDay(), TOL);
        assertFalse(s.candidateKeepsLead());
        // λ=10, q=100, ρw=0: lhs 0.2 > rhs 0.00542
        assertTrue(BreakEvenCalculator.steadyState(base().withSteadyState(new SteadyState(10, 100, 0)), "B").candidateKeepsLead());
    }

    @Test void oneAtATimeSensitivity() {
        Inputs in = base().withSteadyState(new SteadyState(300, 10, 0.1));
        List<SensitivityRow> rows = BreakEvenCalculator.sensitivity(in, "B");
        SensitivityRow f = row(rows, "extraction truncation rate f");
        assertEquals(6.775, f.qStarBase(), 1e-9);
        assertEquals(6.775, f.qStarLow(), 1e-9);
        assertEquals(0.0199375 / 0.002, f.qStarHigh(), 1e-9);              // 9.96875
        SensitivityRow b = row(rows, "extraction batch size b");
        // b=5: 5 batches x (0.00035 + 0.004) = 0.02175 -> 10.875; b=20: (0.0011 + 0.004) + 0.00435 = 0.00945 -> 4.725
        assertEquals(10.875, b.qStarLow(), 1e-9);
        assertEquals(4.725, b.qStarHigh(), 1e-9);
        SensitivityRow all = row(rows, "answer output tokens (all arms)");
        assertEquals(6.775, all.qStarLow(), 1e-9);                          // equal output on both arms cancels
        assertEquals(6.775, all.qStarHigh(), 1e-9);
        SensitivityRow cand = row(rows, "answer output tokens (candidate only; arm difference)");
        // x0.8: C = 0.001 + 0.0032 = 0.0042 -> 0.01355/0.0028 = 4.8392857; x1.2: 0.0058 -> 0.01355/0.0012 = 11.291667
        assertEquals(0.01355 / 0.0028, cand.qStarLow(), 1e-9);
        assertEquals(0.01355 / 0.0012, cand.qStarHigh(), 1e-9);
        SensitivityRow cpt = row(rows, "chars per token (input only)");
        // inputs x0.8: B 0.0064, C 0.0048; extraction H=80, t=40: 0.00448*2 + 0.00428 = 0.01324 -> 0.01324/0.0016 = 8.275
        assertEquals(8.275, cpt.qStarLow(), 1e-9);
        // diff at Q=25 (base): C 0.01355 + 0.125 = 0.13855, B 0.175 -> -0.03645
        assertEquals(-0.03645, f.diffAtRefBase(), TOL);
        SensitivityRow q = row(rows, "q/lambda");
        // q x0.5: 5*0.002 - 0.17886 = -0.16886 ; q x2: 20*0.002 - 0.17886 = -0.13886
        assertEquals(-0.16886, q.steadyMarginLow(), TOL);
        assertEquals(-0.13886, q.steadyMarginHigh(), TOL);
    }

    @Test void deterministicAndProvenanceIsTheWeakestInput() {
        Inputs in = base().mapArm("B", a -> new Arm(a.name(), false, a.classes(), 0, 0, null, null, null, Provenance.MEASURED));
        Result r1 = BreakEvenCalculator.compute(in), r2 = BreakEvenCalculator.compute(in);
        assertEquals(r1, r2);
        assertEquals(Provenance.ESTIMATED, r1.breakEvens().get(0).provenance());
        assertEquals(Provenance.MEASURED, r1.meanAnswerCost().get("B").provenance());
        assertEquals(Provenance.SIMULATED, Provenance.weakest(Provenance.MEASURED, Provenance.SIMULATED));
        assertEquals(Provenance.MEASURED_OFFLINE, Provenance.weakest(Provenance.MEASURED, Provenance.MEASURED_OFFLINE));
    }

    private static SensitivityRow row(List<SensitivityRow> rows, String name) {
        return rows.stream().filter(r -> r.parameter().equals(name)).findFirst().orElseThrow();
    }
}
