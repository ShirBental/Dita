package app.dita.eval.answer;

import app.dita.eval.Json;
import app.dita.eval.arms.ArmId;
import app.dita.eval.guard.CanaryGuard;
import app.dita.eval.guard.LeakageException;
import app.dita.eval.guard.Sentinels;
import app.dita.eval.runner.Prompt;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnswerPromptGuardTest {
    final Prompt p = new Prompt("Q1", "c", Instant.parse("2026-01-01T00:00:00Z"), "Where is the tournament?");

    @Test void goldAnswerInFixedPromptTextTripsGuard() {
        ModelRequest leaky = AnswerPrompt.render(p, ArmId.A_FULL, "[]", AnswerPrompt.SYSTEM + "\nHint: the answer is Gizmo Dome.");
        CanaryGuard g = new CanaryGuard(new Sentinels(Set.of(), Set.of(Sentinels.hash("Gizmo Dome"))));
        assertThrows(LeakageException.class, () -> g.check(leaky));
    }

    @Test void onlyTheContextBlockDiffersBetweenArms() {
        ModelRequest a = AnswerPrompt.render(p, ArmId.A_FULL, "[]");
        ModelRequest c = AnswerPrompt.render(p, ArmId.C_TODAY, "[]");
        assertEquals(a.body(), c.body());
        assertEquals(a.fixedText(), AnswerPrompt.scaffold());
        JsonNode body = Json.parse(a.body());
        assertEquals(AnswerPrompt.MODEL, body.get("model").asText());
        assertEquals(AnswerPrompt.MAX_COMPLETION_TOKENS, body.get("max_completion_tokens").asInt());
        assertEquals(AnswerPrompt.SYSTEM, body.get("messages").get(0).get("content").asText());
        assertTrue(body.get("messages").get(1).get("content").asText().contains("Where is the tournament?"));
    }

    @Test void systemPromptContainsNoGoldLabelVocabulary() {
        for (String m : CanaryGuard.LABEL_MARKERS) assertFalse(AnswerPrompt.SYSTEM.contains(m), m);
    }

    @Test void museStubRefusesLiveCalls() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> new MuseAnswererStub().answer(AnswerPrompt.render(p, ArmId.A_FULL, "[]")));
        assertTrue(e.getMessage().contains("live calls require approval"));
    }

    @Test void parserAcceptsSchemaAndRejectsViolations() {
        String ok = "{\"behavior\":\"UNKNOWN\",\"answer\":\"\",\"slots\":{},\"claims\":[],\"clarify_question\":null}";
        assertTrue(OutputParser.parse(ok, "stop").ok());
        assertTrue(OutputParser.parse("```json\n" + ok + "\n```", "stop").ok());
        assertFalse(OutputParser.parse(ok, "length").ok());
        assertFalse(OutputParser.parse("not json", "stop").ok());
        assertFalse(OutputParser.parse(ok.replace("UNKNOWN", "MAYBE"), "stop").ok());
        assertFalse(OutputParser.parse(ok.replace("\"slots\":{},", ""), "stop").ok());
        assertFalse(OutputParser.parse(ok.replace("}", ",\"extra\":1}"), "stop").ok());
        String badClaim = "{\"behavior\":\"ANSWER\",\"answer\":\"x\",\"slots\":{},\"claims\":[{\"speaker\":\"a\",\"attributed_to\":null,"
                + "\"message_ids\":[\"m1\"],\"quotes\":[\"x\"],\"stance\":\"pro\",\"qualifier\":null,\"is_current\":true,\"commitment\":\"maybe\"}],\"clarify_question\":null}";
        assertFalse(OutputParser.parse(badClaim, "stop").ok());
        assertTrue(OutputParser.parse(badClaim.replace("maybe", "offer"), "stop").ok());
    }
}
