package app.dita.core.knowledge;
import app.dita.core.model.*;
import app.dita.core.QueryService;
import app.dita.core.answer.*;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.retrieval.*;
import app.dita.core.memory.*;
import app.dita.core.config.RetrievalSettings;
import java.time.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Composer plumbing tests with synthetic admissions; these do NOT measure an embedding model. */
class Task049SemanticAdmissionTest {
 final GroupScope g=new GroupScope("task048-new-synthetic");
 final InMemoryMessageStore s=new InMemoryMessageStore();
 final Instant t=Instant.parse("2027-05-01T09:00:00Z");
 int n=0;
 KnowledgeItem item(String mid,String speaker,String status,String subject,String text){
  Instant at=t.plusSeconds(n++*60);s.append(g,new Message(mid,g.groupId(),speaker,at,null,text,SourceKind.SYNTHETIC,false));
  Assertion.Status st=Assertion.Status.valueOf(status);String predicate=st==Assertion.Status.QUESTION?"asks":st==Assertion.Status.CONFIRMED_COMMITMENT?"commits":st==Assertion.Status.OFFER?"offers":"states";
  var a=new Assertion(st,st==Assertion.Status.OFFER?Assertion.Certainty.HEDGED:Assertion.Certainty.ASSERTED,speaker,null,subject,subject,predicate,List.of(),List.of(),at,Assertion.Validity.ACTIVE,"task048-test","b");
  var k=new KnowledgeItem("k-"+mid,g.groupId(),st==Assertion.Status.QUESTION?KnowledgeItem.Kind.QUESTION:st==Assertion.Status.CONFIRMED_COMMITMENT||st==Assertion.Status.OFFER?KnowledgeItem.Kind.COMMITMENT:KnowledgeItem.Kind.CLAIM,subject,text,0,MessageLink.Method.LLM,List.of(KnowledgeItem.Evidence.span(mid,KnowledgeItem.Role.ASSERTS,0,text.length())),false,a);s.addKnowledge(g,k);return k;
 }
 KnowledgeRetriever ordered(KnowledgeItem...items){return new KnowledgeRetriever(){public List<Hit> retrieve(GroupScope scope,String q,int k){List<Hit> hits=new ArrayList<>();for(var i:items)hits.add(new Hit(i.id(),100-hits.size()));return hits;}public String name(){return "test-ranking";}};}
 QueryService service(KnowledgeRetriever kr){return new QueryService(s,Retrievers.build(s,RetrievalSettings.DEFAULTS),new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY,(ss,m)->Optional.empty(),s),8,200,id->id,kr);}
 Set<String> cites(GroundedAnswer a){return new LinkedHashSet<>(a.citations().stream().map(Citation::messageId).toList());}

 KnowledgeRetriever admitted(String id,boolean semantic){return new KnowledgeRetriever(){
  public List<Hit> retrieve(GroupScope scope,String q,int k){return List.of(new Hit(id,0.95,semantic));}
  public String name(){return "synthetic-admission-control";}
 };}
 @Test void semanticOnlyEvidenceCanReachVerifiedAnswerWhileRankAloneCannot(){
  var fact=item("drinks","Kai","CONFIRMED_COMMITMENT","lemonade","I will bring lemonade.");
  String q="Who handles refreshments?";
  assertFalse(service(admitted(fact.id(),false)).ask(g,"reader",q).found());
  var answer=service(admitted(fact.id(),true)).ask(g,"reader",q);
  assertTrue(answer.found(),answer.text());assertEquals(Set.of("drinks"),cites(answer));
  assertTrue(answer.text().contains("I will bring lemonade."));
 }
 @Test void semanticAdmissionCannotTurnParticipantQuestionIntoAnswer(){
  var fact=item("ask","Kai","QUESTION","lemonade","Should someone bring lemonade?");
  assertFalse(service(admitted(fact.id(),true)).ask(g,"reader","Who handles refreshments?").found());
 }
 @Test void semanticAdmissionCannotInventCrossGroupKnowledge(){
  assertFalse(service(admitted("foreign-knowledge-id",true)).ask(g,"reader","Who handles refreshments?").found());
 }
 @Test void restrictedHistoryCannotEnterSemanticKnowledgePath(){
  var fact=item("drinks","Kai","CONFIRMED_COMMITMENT","lemonade","I will bring lemonade.");
  java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();
  KnowledgeRetriever guarded=new KnowledgeRetriever(){
   public List<Hit> retrieve(GroupScope scope,String q,int k){called.set(true);return List.of(new Hit(fact.id(),0.99,true));}
   public String name(){return "must-not-run";}
  };
  var service=new QueryService(s,Retrievers.build(s,RetrievalSettings.DEFAULTS),
   new HistoryAccessFilter(HistoryAccess.SINCE_REQUESTER_JOINED,(scope,id)->Optional.empty(),s),8,200,id->id,guarded);
  assertFalse(service.ask(g,"unknown-member","Who handles refreshments?").found());assertFalse(called.get());
 }
}
