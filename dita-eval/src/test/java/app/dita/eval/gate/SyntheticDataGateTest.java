package app.dita.eval.gate;

import app.dita.eval.BenchPaths;
import app.dita.eval.TestBench;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** N-46. All fixtures are synthetic; phone-like strings use all-zero blocks. */
class SyntheticDataGateTest {
    @TempDir Path tmp;
    BenchPaths p;

    static final String MSG = "{\"id\":\"t1\",\"author\":\"ann\",\"sent_at\":\"2026-01-01T10:00:00Z\",\"reply_to\":null,\"forwarded\":false,\"edited\":false,\"text\":\"%s\"}\n";
    static final String GOLD = "{\"qid\":\"T1\",\"forbidden_strings\":[%s],\"_canary\":\"DITA-CANARY-0123456789ABCDEF\"}\n";

    @BeforeEach
    void setUp() throws Exception {
        p = BenchPaths.of(tmp, tmp.resolve("eval/value-benchmark"));
        corpus("hello from the garden club");
        gold("");
        TestBench.write(p.prompts().resolve("dev.jsonl"), "{\"qid\":\"T1\",\"corpus_id\":\"tiny\",\"as_of\":\"2026-01-02T00:00:00Z\",\"question\":\"hi?\"}\n");
        Manifest.write(p);
    }

    void corpus(String text) throws Exception { TestBench.write(p.corpora().resolve("tiny.jsonl"), MSG.formatted(text)); }
    void gold(String forbidden) throws Exception { TestBench.write(p.sealedGold().resolve("dev.jsonl"), GOLD.formatted(forbidden)); }

    Set<String> rules() throws Exception {
        return new SyntheticDataGate(p).run().violations().stream().map(SyntheticDataGate.Violation::rule).collect(Collectors.toSet());
    }

    Set<String> rulesAfterReManifest() throws Exception { Manifest.write(p); return rules(); }

    @Test void cleanSyntheticTreePasses() throws Exception {
        assertTrue(new SyntheticDataGate(p).run().passed(), () -> rulesSafe().toString());
    }

    @Test void realBenchmarkTreePasses() throws Exception {
        SyntheticDataGate.Report r = new SyntheticDataGate(BenchPaths.locate()).run();
        assertTrue(r.passed(), () -> r.violations().toString());
        assertEquals(Set.of("050-0000-417"), r.allowlistedCanaries());
    }

    @Test void israeliMobileFails() throws Exception {
        corpus("call me 052-0000000");
        assertTrue(rulesAfterReManifest().contains("PHONE_IL"));
        corpus("call me 0520000000");
        assertTrue(rulesAfterReManifest().contains("PHONE_IL"));
    }

    @Test void plus972AndE164Fail() throws Exception {
        corpus("ring +972-0-0000000");
        Set<String> r = rulesAfterReManifest();
        assertTrue(r.contains("PHONE_972") && r.contains("PHONE_E164"), r::toString);
        corpus("ring +10000000000");
        assertTrue(rulesAfterReManifest().contains("PHONE_E164"));
    }

    @Test void canaryAllowedOnlyWhenDeclaredInGold() throws Exception {
        corpus("door code, call 050-0000-417");
        assertTrue(rulesAfterReManifest().contains("PHONE_IL"), "undeclared canary must fail");
        gold("\"050-0000-417\"");
        assertEquals(Set.of(), rulesAfterReManifest());
    }

    @Test void declaredPhoneThatIsNotObviouslyFakeFails() throws Exception {
        corpus("call 052-0000000");
        gold("\"052-0000000\"");
        Set<String> r = rulesAfterReManifest();
        assertTrue(r.contains("CANARY_NOT_OBVIOUSLY_FAKE") && r.contains("PHONE_IL"), r::toString);
    }

    @Test void emailIdAndUrlFail() throws Exception {
        corpus("mail nobody@example.invalid");
        assertTrue(rulesAfterReManifest().contains("EMAIL"));
        corpus("id 000000000");
        assertTrue(rulesAfterReManifest().contains("ID_9_DIGITS"));
        corpus("see https://example.invalid/page");
        assertTrue(rulesAfterReManifest().contains("URL"));
        corpus("see example.com for details");
        assertTrue(rulesAfterReManifest().contains("DOMAIN"));
    }

    @Test void findingsNeverEchoMatchedText() throws Exception {
        corpus("mail nobody@example.invalid");
        Manifest.write(p);
        String s = new SyntheticDataGate(p).run().violations().toString();
        assertFalse(s.contains("nobody"), s);
    }

    @Test void corpusOutsideCorporaFails() throws Exception {
        TestBench.write(p.results().resolve("stray.jsonl"), MSG.formatted("hello"));
        assertTrue(rules().contains("CORPUS_OUTSIDE_ALLOWLIST"));
    }

    @Test void legacyFixtureFormatOutsideCorporaFails() throws Exception {
        TestBench.write(p.bench().resolve("notes/old.txt"), "a1|ann|0||hello there\n");
        assertTrue(rules().contains("CORPUS_OUTSIDE_ALLOWLIST"));
    }

    @Test void configuredInputOutsideCorporaFails() throws Exception {
        assertTrue(new SyntheticDataGate(p).run(List.of(p.prompts().resolve("other.jsonl"))).violations().stream()
                .anyMatch(v -> v.rule().equals("CORPUS_OUTSIDE_ALLOWLIST")));
    }

    @Test void fileNotInManifestFails() throws Exception {
        TestBench.write(p.sealedGold().resolve("extra.jsonl"), "{}\n");
        assertTrue(rules().contains("HASH_NOT_IN_MANIFEST"));
    }

    @Test void modifiedFileFailsHashCheck() throws Exception {
        corpus("hello from the garden club, edited after freezing");
        assertTrue(rules().contains("HASH_MISMATCH"));
    }

    @Test void staleOrUnsafeManifestEntriesFail() throws Exception {
        String m = Files.readString(p.manifest());
        Files.writeString(p.manifest(), m + "0".repeat(64) + "  ../escape.txt\n" + "0".repeat(64) + "  data/real.jsonl\n" + "0".repeat(64) + "  corpora/gone.jsonl\n");
        Set<String> r = rules();
        assertTrue(r.containsAll(Set.of("MANIFEST_UNSAFE_PATH", "PATH_UNDER_DATA", "MANIFEST_ENTRY_MISSING_FILE")), r::toString);
    }

    @Test void missingManifestFails() throws Exception {
        Files.delete(p.manifest());
        assertTrue(rules().contains("MANIFEST_MISSING"));
    }

    @Test void pathsUnderDataAreRefusedWithoutOpening() throws Exception {
        Path never = p.dataDir().resolve("private/does-not-exist.jsonl");
        SyntheticDataGate.Report r = new SyntheticDataGate(p).run(List.of(never));
        assertTrue(r.violations().stream().anyMatch(v -> v.rule().equals("PATH_UNDER_DATA")));
        assertFalse(Files.exists(never));
        BenchPaths underData = BenchPaths.of(tmp, tmp.resolve("data/eval/value-benchmark"));
        assertTrue(new SyntheticDataGate(underData).run().violations().stream().anyMatch(v -> v.rule().equals("PATH_UNDER_DATA")));
    }

    @Test void symlinksFail() throws Exception {
        Files.createSymbolicLink(p.corpora().resolve("link.jsonl"), p.sealedGold().resolve("dev.jsonl"));
        assertTrue(rules().contains("SYMLINK"));
    }

    @Test void canaryTokensAndHashesAreNotMistakenForIds() {
        assertEquals(List.of(), SyntheticDataGate.scanText("x", "DITA-CANARY-1234567890123456 and " + "1".repeat(64), Set.of()));
    }

    private Set<String> rulesSafe() { try { return rules(); } catch (Exception e) { return Set.of(e.toString()); } }
}
