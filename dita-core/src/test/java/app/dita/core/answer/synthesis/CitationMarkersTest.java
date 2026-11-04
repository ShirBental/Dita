package app.dita.core.answer.synthesis;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CitationMarkersTest {
    AnswerSynthesizer.Evidence e(String id) {return new AnswerSynthesizer.Evidence(id,"P1","","MESSAGE",0,false,"A statement.");}
    AnswerSynthesizer.Request req(String... ids) {return new AnswerSynthesizer.Request("?","",Arrays.stream(ids).map(this::e).toList(),List.of(),false);}
    @Test void exactDeclaredUniqueTargetOnly() {
        var draft = new AnswerSynthesizer.Draft(false,"P1 said A [m329]",List.of("tgt123m329"));
        assertEquals("P1 said A [tgt123m329]",CitationMarkers.canonicalize(req("tgt123m329"),draft).answer());
        for (var r : List.of(req("tgt123m329","tgt456m329"),req("tgt456m329"),req("m329","tgt123m329"),req("tgt123m328")))
            assertEquals(draft,CitationMarkers.canonicalize(r,draft));
        var missing = new AnswerSynthesizer.Draft(false,draft.answer(),List.of());
        assertEquals(missing,CitationMarkers.canonicalize(req("tgt123m329"),missing));
    }
}
