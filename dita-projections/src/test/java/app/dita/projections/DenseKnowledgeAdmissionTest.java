package app.dita.projections;
import app.dita.core.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Artificial response controls for security/lifecycle; not a semantic quality evaluation. */
class DenseKnowledgeAdmissionTest {
 final KnowledgeFixture fixture=new KnowledgeFixture("task049-security").withKnowledge();
 JsonNode response(String group,String hash,double score) {
  return Http.JSON.valueToTree(Map.of("root",Map.of("children",List.of(Map.of("relevance",score,"fields",
   Map.of("group_id",group,"knowledge_id","k-c02","validity","ACTIVE","content_hash",hash))))));
 }
 String liveHash(){return VespaProjection.contentHash(KnowledgeDocument.render(fixture.c02(),fixture.truth()::message).orElseThrow().text());}
 @Test void onlyCurrentHashAndStrongFiniteCosineAdmitSemanticEvidence() {
  assertEquals(1,VespaProjection.parseDenseKnowledge(fixture.g,response(fixture.g.groupId(),liveHash(),0.95),0.9,fixture.truth()).size());
  assertTrue(VespaProjection.parseDenseKnowledge(fixture.g,response(fixture.g.groupId(),"stale",0.99),0.9,fixture.truth()).isEmpty());
  assertTrue(VespaProjection.parseDenseKnowledge(fixture.g,response(fixture.g.groupId(),liveHash(),0.89),0.9,fixture.truth()).isEmpty());
  assertTrue(VespaProjection.parseDenseKnowledge(fixture.g,response(fixture.g.groupId(),liveHash(),1.5),0.9,fixture.truth()).isEmpty());
 }
 @Test void foreignGroupIsHardFailureEvenBelowThreshold() {
  assertThrows(IsolationViolation.class,()->VespaProjection.parseDenseKnowledge(fixture.g,response("other",liveHash(),0.1),0.9,fixture.truth()));
 }
 @Test void editsExcludeKnowledgeEvenIfItsOldSpanTextStillMatches() {
  var old=response(fixture.g.groupId(),liveHash(),0.99);
  fixture.store.edit(fixture.g,"c02",KnowledgeFixture.C02+" edited",Instant.parse("2026-09-02T00:00:00Z"));
  assertTrue(VespaProjection.parseDenseKnowledge(fixture.g,old,0.9,fixture.truth()).isEmpty());
 }
 @Test void queryErrorsNeverBecomeCleanEmptyResults() {
  var body=Http.JSON.valueToTree(Map.of("root",Map.of("errors",List.of(Map.of("code",4,"summary","bad query")))));
  assertThrows(Http.ProjectionException.class,()->VespaProjection.parseDenseKnowledge(fixture.g,body,0.9,fixture.truth()));
 }
 @Test void deletedSourceCannotBeAdmittedFromAStaleIndex(){
  var old=response(fixture.g.groupId(),liveHash(),0.99);
  fixture.store.tombstone(fixture.g,"c02",Instant.parse("2026-09-02T00:00:00Z"),
   app.dita.core.deletion.DeletionMode.LATE_TEXT_ONLY,app.dita.core.deletion.DeletionSource.FORGET_COMMAND);
  assertTrue(VespaProjection.parseDenseKnowledge(fixture.g,old,0.9,fixture.truth()).isEmpty());
 }
}
