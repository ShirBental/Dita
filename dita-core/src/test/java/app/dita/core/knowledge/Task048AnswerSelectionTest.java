package app.dita.core.knowledge;

import app.dita.core.QueryService;
import app.dita.core.answer.*;
import app.dita.core.model.*;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.retrieval.*;
import app.dita.core.memory.*;
import app.dita.core.config.RetrievalSettings;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** TASK-048: offline behavior regressions; frozen acceptance is an external read-only regression input. */
class Task048AnswerSelectionTest {
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

 @Test void higherQuestionMustNotDisplaceAnExistingCommitment(){
  var ask=item("clarification","organizer","QUESTION","camera station","Do you mean the camera station or the reception desk?");
  var fact=item("commitment","Morgan","CONFIRMED_COMMITMENT","camera station","I will staff the camera station from 10 to noon and bring a tripod.");
  var a=service(ordered(ask,fact)).ask(g,"reader","What has Morgan committed to at the camera station?");
  assertTrue(cites(a).contains("commitment"),a.text());assertFalse(cites(a).contains("clarification"),a.text());
 }
 @Test void unresolvedQuestionsRemainEligibleAlongsideAnOutstandingStatement(){
  var fact=item("needs","organizer","ATTRIBUTED_STATEMENT","coverage","We still need reception coverage and an answer about access.");
  var question=item("labels","coordinator","QUESTION","labels","Do we need translated labels on the donation boxes?");
  var a=service(ordered(fact,question)).ask(g,"reader","What still needs resolving before opening?");
  assertTrue(cites(a).contains("labels"),a.text());assertTrue(cites(a).contains("needs"),a.text());
 }
 @Test void aQuestionOnlySourceMustNotBecomeAnAnswer(){
  var ask=item("unknown","Ravi","QUESTION","permits","Who will arrange the permits for the workshop?");
  var a=service(ordered(ask)).ask(g,"reader","Who will arrange the permits?");
  assertFalse(a.found(),"Only a participant question is available, not a commitment: "+a.text());
 }
 @Test void questionOnlyKnowledgeMustNotBlockARelevantSourceFallback(){
  var ask=item("question","Nora","QUESTION","projector","Who will bring the projector to the screening?");
  s.append(g,new Message("unextracted-answer",g.groupId(),"Malik",t.plusSeconds(120),null,"Confirmed: I will bring the projector to the screening.",SourceKind.SYNTHETIC,false));
  var a=service(ordered(ask)).ask(g,"reader","Who will bring the projector to the screening?");
  assertTrue(cites(a).contains("unextracted-answer"),a.text());assertTrue(a.text().contains("Malik"),a.text());
 }
 @Test void relevantSubjectsSurviveOptionalSameSubjectContext(){
  var radios=item("radio","Inez","CONFIRMED_COMMITMENT","radios","I will bring the radios for the volunteers.");
  item("radio-context-one","Inez","ATTRIBUTED_STATEMENT","radios","The radios are stored in the cabinet.");
  item("radio-context-two","Inez","ATTRIBUTED_STATEMENT","radios","The radios were purchased last winter.");
  var lanterns=item("lantern","Felix","CONFIRMED_COMMITMENT","lanterns","I will bring the lanterns for the volunteers.");
  var badges=item("badges","Asha","CONFIRMED_COMMITMENT","badges","I will bring the badges for the volunteers.");
  var a=service(ordered(radios,lanterns,badges)).ask(g,"reader","Who will bring radios, lanterns and badges?");
  assertTrue(cites(a).containsAll(Set.of("radio","lantern","badges")),a.text());
 }
 @Test void genericFramingWordIsNotEnoughToSelectAnUnrelatedFact(){
  var noise=item("snacks","Zoe","ATTRIBUTED_STATEMENT","snacks","The volunteers are welcome to take snacks.");
  var fact=item("desk","Emil","CONFIRMED_COMMITMENT","welcome desk","I will staff the welcome desk from 11 to 12.");
  var a=service(ordered(noise,fact)).ask(g,"reader","What is the welcome desk commitment?");
  assertTrue(cites(a).contains("desk"),a.text());assertFalse(cites(a).contains("snacks"),a.text());
 }
 @Test void changedPeopleTopicsAndOrderDoNotChangeTheQuestionEligibilityRule(){
  for(int seed=0;seed<5;seed++){
   var fact=item("actor-"+seed,"Person-"+seed,"CONFIRMED_COMMITMENT","telescopes","I will bring the telescopes to the observation session.");
   var ask=item("query-"+seed,"Facilitator-"+seed,"QUESTION","telescopes","Do you mean the telescopes or the audio equipment?");
   var a=service(seed%2==0?ordered(ask,fact):ordered(fact,ask)).ask(g,"reader","Who committed to bring the telescopes?");
   assertTrue(cites(a).contains("actor-"+seed),a.text());assertFalse(cites(a).contains("query-"+seed),a.text());
  }
 }
 @Test void aResolvedQuestionIsNotReportedAsOutstanding(){
  var q=item("permit-q","Jo","QUESTION","permit","Who will arrange the permit?");
  var a=item("permit-a","Cleo","CONFIRMED_COMMITMENT","permit","I will arrange the permit this afternoon.");
  var pending=item("access-q","Nils","QUESTION","access","Do we need a ramp for access?");
  var result=service(ordered(q,a,pending)).ask(g,"reader","Which questions remain unanswered?");
  assertFalse(cites(result).contains("permit-q"),result.text());assertTrue(cites(result).contains("access-q"),result.text());
 }

 @Test void aScopedOutstandingRequestMustNotReturnOtherTopics(){
  var permit=item("permit-open","Dana","QUESTION","permit","Who will obtain the staging permit?");
  var refund=item("refund-open","Olin","QUESTION","refund","Who will process the refund?");
  var a=service(ordered(refund,permit)).ask(g,"reader","Which permit questions remain unresolved?");
  assertTrue(cites(a).contains("permit-open"),a.text());assertFalse(cites(a).contains("refund-open"),a.text());
 }
 @Test void aSharedVenueDoesNotAnswerADifferentUnansweredObject(){
  var q=item("deposit-q","Elin","QUESTION","deposit","Has anyone paid the deposit for the theater?");
  var noise=item("keys","Matteo","ATTRIBUTED_STATEMENT","theater keys","The theater keys are with the caretaker.");
  var a=service(ordered(q,noise)).ask(g,"reader","Has the theater deposit been paid?");
  assertFalse(a.found(),a.text());assertFalse(cites(a).contains("keys"),a.text());
 }
 @Test void anExplicitReplyCanAnswerWithoutRepeatingItsTopic(){
  var q=item("watering-q","Kiran","QUESTION","plants","Who will water the plants on Thursday?");
  var reply=item("watering-a","June","CONFIRMED_COMMITMENT","plants","I will do it before breakfast.");
  // The platform reply edge is metadata, not inferred from wording.
  var original=s.find(g,"watering-a").orElseThrow();s.removeKnowledge(g,reply.id());
  // Use a separate small store so append remains immutable and no edit/deletion policy is bypassed.
  var other=new InMemoryMessageStore();other.append(g,s.find(g,"watering-q").orElseThrow());
  other.append(g,new Message(original.id(),original.groupId(),original.authorId(),original.sentAt(),"watering-q",original.text(),original.sourceKind(),false));
  other.addKnowledge(g,q);other.addKnowledge(g,reply);
  var a=new QueryService(other,Retrievers.build(other,RetrievalSettings.DEFAULTS),new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY,(ss,m)->Optional.empty(),other),8,200,id->id,ordered(q)).ask(g,"reader","Who waters the plants?");
  assertTrue(cites(a).contains("watering-a"),a.text());assertFalse(cites(a).contains("watering-q"),a.text());
 }
 @Test void aRelevantConditionMustSurviveBesideAlternativeEvidence(){
  var cancel=item("snow-cancel","Tess","ATTRIBUTED_STATEMENT","we cancel rather than move indoors","If it snows, we cancel rather than move indoors.");
  var condition=new Assertion.Qualifier(Assertion.Qualifier.Kind.CONDITION,"snow-cancel",0,11);
  s.replaceKnowledge(g,new KnowledgeItem(cancel.id(),cancel.groupId(),cancel.kind(),cancel.subject(),cancel.statement(),cancel.polarity(),cancel.method(),cancel.evidence(),false,cancel.assertion().withQualifiers(List.of(condition))));
  cancel=s.knowledgeById(g,cancel.id()).orElseThrow();
  var alternate=item("snow-backup","Orin","ATTRIBUTED_STATEMENT","snow backup","The gym was considered as a snow backup.");
  var a=service(ordered(cancel,alternate)).ask(g,"reader","What happens if it snows?");
  assertTrue(cites(a).contains("snow-cancel"),a.text());
 }
 static record Frozen(InMemoryMessageStore store,GroupScope scope,List<String> questions){}
 static Frozen frozen()throws Exception{
  String configured=System.getProperty("dita.task048.fixture");Assumptions.assumeTrue(configured!=null,"frozen acceptance path supplied for TASK-048 replay");
  Path root=Path.of(configured);ObjectMapper j=new ObjectMapper();var store=new InMemoryMessageStore();JsonNode all=j.readTree(root.resolve("live-knowledge.json").toFile());GroupScope group=new GroupScope(all.get(0).path("groupId").asText());
  for(JsonNode m:j.readTree(root.resolve("input.json").toFile()))store.append(group,new Message(m.path("id").asText(),group.groupId(),m.path("author").asText(),Instant.parse(m.path("sent_at").asText()),m.hasNonNull("reply_to")?m.path("reply_to").asText():null,m.path("text").asText(),SourceKind.SYNTHETIC,false));
  for(JsonNode k:all){JsonNode a=k.path("assertion");List<Assertion.Qualifier> qs=new ArrayList<>();for(JsonNode q:a.path("qualifiers"))qs.add(new Assertion.Qualifier(Assertion.Qualifier.Kind.valueOf(q.path("kind").asText()),q.path("messageId").asText(),q.path("spanStart").asInt(),q.path("spanEnd").asInt()));
   List<Assertion.Entity> entities=new ArrayList<>();for(JsonNode e:a.path("entities"))entities.add(new Assertion.Entity(e.path("id").asText(),e.path("surface").asText()));
   var assertion=new Assertion(Assertion.Status.valueOf(a.path("status").asText()),Assertion.Certainty.valueOf(a.path("certainty").asText()),a.path("speakerId").asText(),a.hasNonNull("attributedTo")?a.path("attributedTo").asText():null,a.path("subjectId").asText(),a.path("subject").asText(),a.path("predicate").asText(),qs,entities,Instant.parse(a.path("statedAt").asText()),Assertion.Validity.valueOf(a.path("validity").asText()),a.path("proposalVersion").asText(),a.path("batchId").asText());
   List<KnowledgeItem.Evidence> evidence=new ArrayList<>();for(JsonNode e:k.path("evidence"))evidence.add(new KnowledgeItem.Evidence(e.path("messageId").asText(),KnowledgeItem.Basis.valueOf(e.path("basis").asText()),e.hasNonNull("viaLinkKey")?e.path("viaLinkKey").asText():null,e.path("sourceDeleted").asBoolean(),KnowledgeItem.Role.valueOf(e.path("role").asText()),e.path("spanStart").asInt(),e.path("spanEnd").asInt()));
   store.addKnowledge(group,new KnowledgeItem(k.path("id").asText(),group.groupId(),KnowledgeItem.Kind.valueOf(k.path("kind").asText()),k.path("subject").asText(),k.path("statement").asText(),k.path("polarity").asInt(),MessageLink.Method.valueOf(k.path("method").asText()),evidence,k.path("statementRedacted").asBoolean(),assertion));
  }
  List<String> questions=new ArrayList<>();for(JsonNode q:j.readTree(root.resolve("questions.json").toFile()))questions.add(q.path("question").asText());return new Frozen(store,group,questions);
 }
 @Test void frozenQ03MustPreserveAllPreviouslyCorrectTableEvidence()throws Exception{var f=frozen();var a=frozenQuery(f,2);assertTrue(cites(a).containsAll(Set.of("M02","M15","M16","M48")),a.text());assertFalse(cites(a).contains("M27"),a.text());}
 @Test void frozenQ06MustPreserveAllPreviouslyCorrectRefreshmentEvidence()throws Exception{var f=frozen();var a=frozenQuery(f,5);assertTrue(cites(a).containsAll(Set.of("M20","M37","M38","M39")),a.text());}
 @Test void frozenQ08MustPreserveCancellationCondition()throws Exception{var f=frozen();assertTrue(cites(frozenQuery(f,7)).contains("M36"));}
 @Test void frozenQ04MustRecoverOmarCommitment()throws Exception{var f=frozen();var answer=frozenQuery(f,3);assertTrue(cites(answer).contains("M17"),answer.text());assertFalse(cites(answer).contains("M34"),answer.text());}
 @Test void frozenQ05MustPreserveCorrectedShiftAndGap()throws Exception{var f=frozen();var answer=frozenQuery(f,4);assertTrue(cites(answer).containsAll(Set.of("M45","M46")),answer.text());assertFalse(cites(answer).contains("M34"),answer.text());}
 @Test void frozenQ10MustNotDropTheLabelQuestion()throws Exception{var f=frozen();var answer=frozenQuery(f,9);assertTrue(cites(answer).contains("M47"),answer.text());assertFalse(cites(answer).contains("M48"),answer.text());}
 static GroundedAnswer frozenQuery(Frozen f,int i){return new QueryService(f.store(),Retrievers.build(f.store(),RetrievalSettings.DEFAULTS),new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY,(ss,m)->Optional.empty(),f.store()),8,200,id->id,new LexicalKnowledgeRetriever(f.store(),id->id)).ask(f.scope(),"Maya",f.questions().get(i));}
}
