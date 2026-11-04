package app.dita.core.knowledge;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DispatchSettings;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventDispatcher;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

/**
 * TASK-045 stress suite (eval harness only; no implementation code). Runs whole synthetic group conversations
 * (Peter-authored, 20–40 messages) through the real offline pipeline: ingestion → extraction service with a SYNTHETIC
 * proposer (Fury-authored items, NOT Muse output) → evidence validation → relation rules → knowledge retrieval →
 * composition → verifier; then deletion and cross-group isolation probes. No model call, no network.
 *
 * Directory: -Ddita.stress.dir (default ../eval/TASK-045-stress/dev). Each S*.json conversation may have
 * extraction/S*.items.json. Writes target/stress-report.md and target/stress-results.json.
 * Never point it at a sealed directory during development.
 */
class StressConversationSuiteTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    static final int RETRIEVE_K = 10;

    record Conv(String id, JsonNode doc, JsonNode items, GroupScope g) {}

    @Test void runStressSuite() throws Exception {
        Path dir = Path.of(System.getProperty("dita.stress.dir", "../eval/TASK-045-stress/dev"));
        Assumptions.assumeTrue(Files.isDirectory(dir), "no stress directory " + dir);
        List<Conv> convs = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".json")) continue;
                JsonNode doc = JSON.readTree(Files.readString(f));
                String id = doc.get("id").asText();
                Path xp = dir.resolve("extraction").resolve(id + ".items.json");
                JsonNode items = Files.exists(xp) ? JSON.readTree(Files.readString(xp)) : JSON.createArrayNode();
                convs.add(new Conv(id, doc, items, new GroupScope("g-stress-" + id.toLowerCase(Locale.ROOT))));
            }
        }
        Assumptions.assumeFalse(convs.isEmpty(), "no conversations");

        // One store for ALL conversations: each conversation is its own group (isolation is tested on shared storage).
        InMemoryMessageStore store = new InMemoryMessageStore();
        EventDispatcher dispatcher = new EventDispatcher(new DispatchSettings(100, 2, 3, Duration.ofSeconds(1), Duration.ofSeconds(4)),
                Clock.fixed(T0.plusSeconds(86_400), ZoneOffset.UTC));
        RuleLinkInferencer inferencer = new RuleLinkInferencer();
        IngestionPipeline ingest = new IngestionPipeline(store, inferencer, 50, dispatcher);
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> ownerConv = new HashMap<>();
        for (Conv c : convs) {
            c.doc().path("names").fields().forEachRemaining(n -> names.put(n.getKey(), n.getValue().asText()));
            for (JsonNode m : c.doc().path("messages")) {
                String reply = m.hasNonNull("replyTo") ? m.get("replyTo").asText() : null;
                ingest.ingest(c.g(), new Message(m.get("id").asText(), c.g().groupId(), m.get("author").asText(),
                        T0.plusSeconds(60L * m.get("minute").asLong()), reply, m.get("text").asText(), SourceKind.SYNTHETIC, false));
                ownerConv.put(m.get("id").asText(), c.id());
            }
        }
        dispatcher.drain();
        Function<String, String> display = id -> names.getOrDefault(id, id);

        Map<String, Object> results = new LinkedHashMap<>();
        StringBuilder rep = new StringBuilder("# TASK-045 stress suite (development set)\n\n")
                .append("Extraction: SYNTHETIC (Fury-authored items replayed through the real validator). NOT live Muse extraction.\n")
                .append("Retriever: offline lexical knowledge retriever (DEC-032 ranker); message fallback = offline hybrid retriever.\n\n");
        StringBuilder detail = new StringBuilder();
        int tq = 0, tok = 0, tFalseUnknown = 0, tMissedUnknown = 0, tMissing = 0, tIrrelevant = 0, tStale = 0, tFalseSup = 0,
                tMissedSup = 0, tExpSup = 0, tProposed = 0, tAccepted = 0, tIsoViol = 0, tDelFail = 0;
        Map<String, Integer> stageCount = new TreeMap<>();
        rep.append("| conv | msgs | items proposed/accepted | sup expected/formed/false | questions correct | false UNKNOWN | missed UNKNOWN | missing evidence | irrelevant cites | stale cites |\n|---|---|---|---|---|---|---|---|---|---|\n");

        for (Conv c : convs) {
            GroupScope g = c.g();
            Map<String, Message> msgs = new LinkedHashMap<>();
            for (JsonNode m : c.doc().path("messages")) msgs.put(m.get("id").asText(), store.find(g, m.get("id").asText()).orElseThrow());

            // ---- extraction (synthetic proposer) + validation + relation rules
            List<Proposal.Item> items = new ArrayList<>();
            for (JsonNode it : c.items()) {
                List<Proposal.Qualifier> qs = new ArrayList<>();
                it.path("qualifiers").forEach(q -> qs.add(new Proposal.Qualifier(q.get("kind").asText(), q.get("mid").asText(), q.get("quote").asText())));
                List<Proposal.Quote> ev = new ArrayList<>();
                ev.add(new Proposal.Quote(it.get("mid").asText(), it.get("quote").asText()));
                it.path("extraEvidence").forEach(q -> ev.add(new Proposal.Quote(q.get("mid").asText(), q.get("quote").asText())));
                items.add(new Proposal.Item(it.get("status").asText(), it.get("certainty").asText(), it.get("subjectId").asText(),
                        it.get("subject").asText(), it.get("predicate").asText(), null,
                        it.hasNonNull("attributedTo") ? it.get("attributedTo").asText() : null, ev, qs, List.of()));
            }
            Clock now = Clock.fixed(T0.plusSeconds(7200), ZoneOffset.UTC);
            ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, ctx) -> new Proposal(items, "stress-synthetic"),
                    EventPublisher.NONE, now, 50, Duration.ofMinutes(30));
            for (Message m : msgs.values()) x.enqueue(g, m);
            List<String> rejections = new ArrayList<>();
            for (var batch : x.collect(g)) {
                x.approve(g, batch.id(), "stress-suite");
                var r = x.process(g, batch.id()).orElseThrow();
                for (var rj : r.rejections()) {
                    JsonNode it = c.items().get(rj.item() - 1);
                    rejections.add(it.get("mid").asText() + ": " + rj.reason());
                }
            }
            Map<String, KnowledgeItem> byMid = new HashMap<>();
            for (KnowledgeItem k : store.knowledge(g)) if (!k.asserting().isEmpty()) byMid.putIfAbsent(k.asserting().get(0).messageId(), k);
            Map<String, String> midOfK = new HashMap<>();
            byMid.forEach((mid, k) -> midOfK.put(k.id(), mid));
            Set<String> formed = new LinkedHashSet<>();
            for (KnowledgeRelation r : store.relations(g)) if (r.kind() == KnowledgeRelation.Kind.SUPERSEDES)
                formed.add(midOfK.getOrDefault(r.fromId(), r.fromId()) + ">" + midOfK.getOrDefault(r.toId(), r.toId()));
            Set<String> expected = new LinkedHashSet<>();
            Set<String> expectSuperseded = new HashSet<>(), expectCurrent = new HashSet<>();
            for (JsonNode s : c.doc().path("state")) {
                s.path("expectedSupersedes").forEach(p -> expected.add(p.get(0).asText() + ">" + p.get(1).asText()));
                s.path("superseded").forEach(v -> expectSuperseded.add(v.asText()));
                s.path("current").forEach(v -> expectCurrent.add(v.asText()));
            }
            List<String> falseSup = formed.stream().filter(f -> !expected.contains(f)).toList();
            List<String> missedSup = expected.stream().filter(f -> !formed.contains(f)).toList();
            // state-level: superseded message still ACTIVE / current message SUPERSEDED (relation-stage outcome)
            List<String> staleActive = new ArrayList<>(), currentRetired = new ArrayList<>(), unextracted = new ArrayList<>();
            for (String mid : expectSuperseded) { KnowledgeItem k = byMid.get(mid); if (k == null) unextracted.add(mid); else if (k.assertion().validity() == Assertion.Validity.ACTIVE) staleActive.add(mid); }
            for (String mid : expectCurrent) { KnowledgeItem k = byMid.get(mid); if (k == null) unextracted.add(mid); else if (k.assertion().validity() != Assertion.Validity.ACTIVE) currentRetired.add(mid); }
            tProposed += items.size(); tAccepted += byMid.size(); tExpSup += expected.size(); tFalseSup += falseSup.size(); tMissedSup += missedSup.size();

            detail.append("\n## ").append(c.id()).append(" — ").append(c.doc().path("theme").asText()).append("\n\n")
                  .append("- validation: proposed ").append(items.size()).append(", accepted ").append(byMid.size())
                  .append(rejections.isEmpty() ? "" : ", rejected: " + String.join("; ", rejections)).append("\n")
                  .append("- supersessions: expected ").append(expected.size()).append(", formed ").append(formed.size())
                  .append(" · FALSE ").append(falseSup).append(" · MISSED ").append(missedSup).append("\n")
                  .append("- state: superseded-but-ACTIVE ").append(staleActive).append(" · current-but-SUPERSEDED ").append(currentRetired)
                  .append(" · state messages without an accepted item ").append(unextracted).append("\n\n")
                  .append("| q | kind | pol | ok | answer path | stage | reasons |\n|---|---|---|---|---|---|---|\n");

            // ---- questions
            var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
            LexicalKnowledgeRetriever kr = new LexicalKnowledgeRetriever(store, display);
            QueryService q = new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, display, kr);
            int cq = 0, cok = 0, cFU = 0, cMU = 0, cMiss = 0, cIrr = 0, cStale = 0;
            List<Map<String, Object>> qres = new ArrayList<>();
            for (JsonNode qq : c.doc().path("questions")) {
                cq++;
                GroundedAnswer ans = q.ask(g, "u-asker", qq.get("question").asText());
                List<String> ids = ans.citations().stream().map(Citation::messageId).distinct().toList();
                String text = ans.text();
                boolean expectUnknown = qq.path("expectUnknown").asBoolean(false);
                Set<String> must = strs(qq.path("mustCite")), allow = strs(qq.path("allowCite")), mustNot = strs(qq.path("mustNotCite"));
                Set<String> ok = new HashSet<>(must); ok.addAll(allow);
                boolean unknownLabel = !ans.found() || text.contains("found no reply") || text.toLowerCase(Locale.ROOT).contains("no answer");
                List<String> why = new ArrayList<>();
                List<String> stages = new ArrayList<>();
                // isolation: every citation must belong to this conversation's group
                for (String id : ids) if (!c.id().equals(ownerConv.get(id)) || store.find(g, id).isEmpty()) { tIsoViol++; why.add("ISOLATION cites " + id); stages.add("isolation"); }
                List<String> missing = must.stream().filter(m -> !ids.contains(m)).sorted().toList();
                List<String> stale = mustNot.stream().filter(ids::contains).sorted().toList();
                List<String> irrelevant = ok.isEmpty() && !expectUnknown ? List.of() : ids.stream().filter(i -> !ok.contains(i) && !mustNot.contains(i)).toList();
                if (expectUnknown) {
                    boolean onlyAllowed = ids.stream().allMatch(ok::contains);
                    if (!unknownLabel && !onlyAllowed) { cMU++; why.add("MISSED UNKNOWN (asserted an answer)"); }
                } else if (unknownLabel) { cFU++; why.add("FALSE UNKNOWN"); }
                if (!missing.isEmpty()) { cMiss += missing.size(); why.add("missing evidence " + missing); }
                if (!stale.isEmpty()) { cStale += stale.size(); why.add("forbidden cite " + stale); }
                if (!irrelevant.isEmpty()) { cIrr += irrelevant.size(); why.add("irrelevant cite " + irrelevant); }
                for (JsonNode t : qq.path("textMust")) if (!text.contains(t.asText())) why.add("text missing '" + t.asText() + "'");
                for (JsonNode t : qq.path("textMustNot")) if (text.contains(t.asText())) why.add("text has '" + t.asText() + "'");
                boolean correct = why.stream().noneMatch(w -> !w.startsWith("irrelevant"));   // irrelevant extras reported, not failing
                // ---- stage attribution (first failing stage along the pipeline, per missing/forbidden message)
                List<String> kHits = kr.retrieve(g, qq.get("question").asText(), RETRIEVE_K).stream().map(KnowledgeRetriever.Hit::knowledgeId).toList();
                String path = ans.knowledgeIds().isEmpty() ? (ans.found() ? "message-fallback" : "not-found") : "knowledge";
                for (String m : missing) {
                    KnowledgeItem k = byMid.get(m);
                    boolean proposed = false; for (JsonNode it : c.items()) if (it.get("mid").asText().equals(m)) proposed = true;
                    if (k == null) stages.add(proposed ? "validation(" + m + ")" : "extraction(" + m + " not proposed)");
                    else if (k.assertion().validity() != Assertion.Validity.ACTIVE) stages.add("relation:false-supersession(" + m + ")");
                    else if (!kHits.contains(k.id())) stages.add("retrieval(" + m + " not in top-" + RETRIEVE_K + ")");
                    else stages.add("composition(" + m + " retrieved, not used)");
                }
                for (String m : stale) {
                    KnowledgeItem k = byMid.get(m);
                    if (k != null && k.assertion().validity() == Assertion.Validity.ACTIVE && expectSuperseded.contains(m)) stages.add("relation:missed-supersession(" + m + ")");
                    else if (k == null || !ans.knowledgeIds().contains(k.id())) stages.add("composition/fallback(" + m + " via message path)");
                    else stages.add("composition(" + m + ")");
                }
                if (!expectUnknown && unknownLabel && missing.isEmpty() && must.isEmpty()) stages.add("composition(unknown label)");
                if (expectUnknown && why.stream().anyMatch(w -> w.startsWith("MISSED UNKNOWN"))) stages.add(path.equals("knowledge") ? "composition(asserted answer)" : "retrieval/fallback(asserted answer)");
                boolean textOnly = !correct && missing.isEmpty() && stale.isEmpty() && stages.isEmpty();
                if (textOnly) stages.add("composition(text)");
                if (!correct) for (String s : stages) stageCount.merge(s.replaceAll("\\(.*", ""), 1, Integer::sum);
                if (correct) cok++;
                detail.append("| ").append(qq.get("qid").asText()).append(" | ").append(qq.path("kind").asText()).append(" | ").append(qq.path("polarity").asText())
                      .append(" | ").append(correct ? "yes" : "NO").append(" | ").append(path).append(" | ").append(correct ? "" : String.join(", ", stages))
                      .append(" | ").append(String.join("; ", why).replace("|", "/")).append(" |\n");
                Map<String, Object> rq = new LinkedHashMap<>();
                rq.put("qid", qq.get("qid").asText()); rq.put("correct", correct); rq.put("path", path); rq.put("cited", ids);
                rq.put("reasons", why); rq.put("stages", stages); rq.put("answer", text);
                qres.add(rq);
            }

            // ---- deletion probe (early purge via platform event, then re-ask)
            JsonNode dp = c.doc().path("deletionProbe");
            String delResult = "n/a";
            if (dp.hasNonNull("delete")) {
                String target = dp.get("delete").asText();
                DeletionService del = new DeletionService(store, inferencer, DeletionSettings.DEFAULTS, dispatcher, 50);
                del.delete(g, target, msgs.get(target).sentAt().plusSeconds(1), DeletionSource.PLATFORM_EVENT);
                dispatcher.drain();
                GroundedAnswer after = q.ask(g, "u-asker", dp.get("question").asText());
                List<String> ids = after.citations().stream().map(Citation::messageId).toList();
                List<String> dw = new ArrayList<>();
                for (JsonNode v : dp.path("mustNotCite")) if (ids.contains(v.asText())) dw.add("cites deleted " + v.asText());
                for (JsonNode v : dp.path("textMustNot")) if (after.text().contains(v.asText())) dw.add("text has '" + v.asText() + "'");
                String deletedText = msgs.get(target).text();
                if (after.text().contains(deletedText)) dw.add("deleted text reproduced");
                boolean liveItem = store.knowledge(g).stream().anyMatch(k -> k.asserting().stream().anyMatch(e -> e.messageId().equals(target))
                        && k.assertion() != null && k.assertion().validity() == Assertion.Validity.ACTIVE);
                if (liveItem) dw.add("ACTIVE knowledge item still asserts from deleted message");
                if (!dw.isEmpty()) tDelFail++;
                delResult = dw.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", dw);
            }
            detail.append("\n- deletion probe (").append(dp.path("delete").asText("none")).append("): ").append(delResult).append("\n");

            tq += cq; tok += cok; tFalseUnknown += cFU; tMissedUnknown += cMU; tMissing += cMiss; tIrrelevant += cIrr; tStale += cStale;
            rep.append(String.format("| %s | %d | %d/%d | %d/%d/%d | %d/%d | %d | %d | %d | %d | %d |%n", c.id(), msgs.size(), items.size(), byMid.size(),
                    expected.size(), formed.size(), falseSup.size(), cok, cq, cFU, cMU, cMiss, cIrr, cStale));
            Map<String, Object> rc = new LinkedHashMap<>();
            rc.put("proposed", items.size()); rc.put("accepted", byMid.size()); rc.put("rejections", rejections);
            rc.put("supExpected", expected); rc.put("supFormed", formed); rc.put("falseSup", falseSup); rc.put("missedSup", missedSup);
            rc.put("staleActive", staleActive); rc.put("currentRetired", currentRetired); rc.put("deletion", delResult); rc.put("questions", qres);
            results.put(c.id(), rc);
        }

        // ---- cross-group isolation probe: ask every conversation's questions inside every OTHER group
        int crossAsked = 0;
        for (Conv a : convs) for (Conv b : convs) {
            if (a == b) continue;
            var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
            QueryService q = new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, display,
                    new LexicalKnowledgeRetriever(store, display));
            for (JsonNode qq : a.doc().path("questions")) {
                crossAsked++;
                for (Citation ci : q.ask(b.g(), "u-asker", qq.get("question").asText()).citations())
                    if (!b.id().equals(ownerConv.get(ci.messageId()))) tIsoViol++;
            }
        }

        rep.append(String.format("%n**Totals:** questions %d · correct %d · FALSE UNKNOWN %d · MISSED UNKNOWN %d · missing evidence %d · irrelevant citations %d · "
                + "forbidden (stale/off) citations %d · supersessions expected %d / missed %d / FALSE %d · items proposed %d / accepted %d · "
                + "deletion-probe failures %d · isolation violations %d (incl. %d cross-group asks)%n%n", tq, tok, tFalseUnknown, tMissedUnknown, tMissing,
                tIrrelevant, tStale, tExpSup, tMissedSup, tFalseSup, tProposed, tAccepted, tDelFail, tIsoViol, crossAsked));
        rep.append("**Failing questions by first-attributed stage:** ").append(stageCount).append("\n");
        rep.append(detail);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/stress-report.md"), rep.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("target/stress-results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results), StandardCharsets.UTF_8);
        System.out.println(rep.toString().lines().filter(l -> l.startsWith("**")).reduce("", (x1, y) -> x1 + y + "\n"));
    }

    static Set<String> strs(JsonNode n) { Set<String> s = new LinkedHashSet<>(); n.forEach(v -> s.add(v.asText())); return s; }
}
