package app.dita.projections;
import app.dita.core.knowledge.KnowledgeRetriever.Hit;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HybridKnowledgeRetrieverTest {
 @Test void fusionIsDeduplicatedAndWeakDenseRankCannotDisplaceSparse() {
  var result=HybridKnowledgeRetriever.fuse(List.of(new Hit("exact",9),new Hit("exact",9),new Hit("both",8)),
    List.of(new Hit("semantic",0.95,true),new Hit("both",0.94,true)),10);
  assertEquals(3,result.size()); assertEquals("both",result.get(0).knowledgeId());
  assertEquals("exact",result.get(1).knowledgeId()); assertFalse(result.get(1).semanticAdmission());
  assertTrue(result.get(2).semanticAdmission());
 }
 @Test void unapprovedCandidateNeverGetsSemanticAdmissionFromScoreAlone() {
  assertFalse(HybridKnowledgeRetriever.fuse(List.of(),List.of(new Hit("x",999)),1).get(0).semanticAdmission());
 }
 @Test void invalidThresholdFailsBeforeNetwork() {
  assertThrows(IllegalArgumentException.class,()->new HybridKnowledgeRetriever(null,null,null,null,Double.NaN));
 }
 @Test void denseConnectionFailurePreservesSparseAndIncrementsCounter() {
  var fixture=new KnowledgeFixture("task049-fallback").withKnowledge();
  app.dita.core.knowledge.KnowledgeRetriever sparse=new app.dita.core.knowledge.KnowledgeRetriever(){
   public List<Hit> retrieve(app.dita.core.model.GroupScope s,String q,int k){return List.of(new Hit("k-c02",1));}
   public String name(){return "controlled-sparse";}
  };
  var h=new HybridKnowledgeRetriever(new VespaProjection("http://127.0.0.1:1"),fixture.truth(),sparse,sparse,0.60);
  assertEquals("k-c02",h.retrieve(fixture.g,"doctor",5).get(0).knowledgeId());
  assertEquals(1,h.metrics().denseFailures());assertEquals(0,h.metrics().sparseFailures());
 }
 @Test void isolationFailureIsNeverAnAvailabilityFallback() {
  app.dita.core.knowledge.KnowledgeRetriever isolated=new app.dita.core.knowledge.KnowledgeRetriever(){
   public List<Hit> retrieve(app.dita.core.model.GroupScope s,String q,int k){throw new app.dita.core.model.IsolationViolation("scope mismatch");}
   public String name(){return "controlled-isolation";}
  };
  var h=new HybridKnowledgeRetriever(null,null,isolated,null,0.60);
  assertThrows(app.dita.core.model.IsolationViolation.class,()->h.retrieve(new app.dita.core.model.GroupScope("task049-fallback"),"doctor",5));
  assertEquals(0,h.metrics().sparseFailures());assertEquals(0,h.metrics().denseFailures());
 }
}
