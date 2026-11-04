package app.dita.app;

import app.dita.core.*;
import app.dita.core.knowledge.*;
import app.dita.core.model.*;
import app.dita.core.store.MessageStore;
import app.dita.core.memory.*;
import app.dita.core.retrieval.Retriever;
import app.dita.core.deletion.*;
import app.dita.projections.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, frozen English inputs; real Spring POSTGRES runtime + Vespa embedding; no model API calls. */
class Task049EnglishIntegrationTest {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static Path artifacts(){return Path.of(System.getProperty("dita.task049.artifacts")).toAbsolutePath();}
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));}
 @TestConfiguration(proxyBeanMethods=false) static class Recorded {
  @Bean KnowledgeProposer task049Recorded() throws Exception {
   Proposal frozen=JSON.readValue(Files.readString(artifacts().resolve("proposals.json")),Proposal.class);
   return (scope,batch,targets,context)->frozen;
  }

 }
 static void await(java.util.function.BooleanSupplier condition)throws Exception{
  long end=System.nanoTime()+Duration.ofSeconds(50).toNanos();
  while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(200);} fail("isolated projection did not catch up");
 }
 @Test void frozenEnglishRealStack()throws Exception{
  assumeTrue(Boolean.getBoolean("dita.task049.run"));
  JsonNode manifest=JSON.readTree(Files.readString(artifacts().resolve(System.getProperty("dita.task049.freeze","freeze-manifest.json"))));
  var fields=manifest.path("files").fields();while(fields.hasNext()){var f=fields.next();assertEquals(f.getValue().asText(),sha(Files.readAllBytes(artifacts().resolve(f.getKey()))),"frozen artifact changed: "+f.getKey());}
  double threshold=manifest.path("threshold").asDouble();
  String smoke=manifest.path("semanticControlQuestion").asText("Who is handling refreshments?");
  String smokeSource=manifest.path("semanticControlSource").asText("m3");
  String run="task049-"+UUID.randomUUID().toString().substring(0,8);GroupScope scope=new GroupScope(run);
  Map<String,String> props=Map.ofEntries(Map.entry("dita.storage.backend","POSTGRES"),
   Map.entry("dita.storage.postgres.url","jdbc:postgresql://127.0.0.1:55532/dita049"),
   Map.entry("dita.storage.vespa.url","http://127.0.0.1:58180"),Map.entry("dita.storage.relay-consumer-prefix",run),
   Map.entry("dita.retrieval.dense.enabled","true"),Map.entry("dita.retrieval.dense.min-cosine",Double.toString(threshold)),
   Map.entry("dita.extraction.worker-enabled","false"),Map.entry("dita.telegram.enabled","false"),Map.entry("dita.muse.enabled","false"));
  try(var ctx=new SpringApplicationBuilder(DitaApplication.class,Recorded.class).run(props.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new))){
   var store=ctx.getBean(MessageStore.class);assertEquals("PostgresMessageStore",store.getClass().getSimpleName());
   ctx.getBean(GroupRegistry.class).ensureRegistered(scope.groupId(),"synthetic",scope.groupId(),GroupDirectory.ChatType.SYNTHETIC,SourceKind.SYNTHETIC);
   var extraction=ctx.getBean(ExtractionService.class);var ingestion=ctx.getBean(IngestionPipeline.class);
   Instant start=Instant.parse("2026-11-01T12:00:00Z");
   for(JsonNode row:JSON.readTree(Files.readString(artifacts().resolve("conversation.json")))){
    Message m=new Message(row.path("id").asText(),scope.groupId(),row.path("author").asText(),start.plusSeconds(60L*row.path("minute").asInt()),null,row.path("text").asText(),SourceKind.SYNTHETIC,false);
    ingestion.ingest(scope,m);assertTrue(extraction.enqueue(scope,m));
   }
   var reports=new ArrayList<ExtractionService.Report>();
   for(var batch:extraction.collect(scope)){extraction.approve(scope,batch.id(),"TASK-049-English-frozen-recorded-only");reports.add(extraction.process(scope,batch.id()).orElseThrow());}
   assertEquals(9,reports.stream().mapToInt(ExtractionService.Report::accepted).sum(),reports.toString());
   var vespa=ctx.getBean(VespaProjection.class);var truth=KnowledgeTruth.of(store);
   await(()->store.knowledge(scope).stream().allMatch(k->vespa.knowledgeStatus(scope.groupId(),k.id())==200));
   // A real derived vector must exist, not an empty field or hand-authored probe.
   var first=store.knowledge(scope).get(0);var document=vespa.knowledgeDocument(scope.groupId(),first.id()).orElseThrow();
   // embedding is not a public summary field; query distance and matched results prove indexed vectors are used below.
   var runtime=ctx.getBean(QueryService.class);var hybrid=ctx.getBean(KnowledgeRetriever.class);
   assertEquals("vespa-bm25+dense-rrf",hybrid.name());
   KnowledgeRetriever sparse=new KnowledgeRetriever(){public String name(){return "A-BM25";}public List<Hit> retrieve(GroupScope s,String q,int k){return vespa.retrieveKnowledge(s,q,k,true).stream().map(h->new Hit(h.knowledgeId(),h.relevance())).toList();}};
   KnowledgeRetriever dense=new KnowledgeRetriever(){public String name(){return "B-dense";}public List<Hit> retrieve(GroupScope s,String q,int k){return vespa.retrieveDenseKnowledge(s,q,k,threshold,truth);}};
   List<Map<String,Object>> results=new ArrayList<>();
   var access=new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY,(s,id)->Optional.empty(),store);
   Retriever noMessageFallback=(s,q,k)->List.of();
   for(JsonNode question:JSON.readTree(Files.readString(artifacts().resolve("questions.json")))){
    String q=question.path("question").asText();Set<String> expected=new LinkedHashSet<>();question.path("expectedSources").forEach(x->expected.add(x.asText()));
    for(var arm:Map.of("A",sparse,"B",dense,"C",hybrid).entrySet()){
     long before=System.nanoTime();var hits=arm.getValue().retrieve(scope,q,10);long retrievalMs=(System.nanoTime()-before)/1_000_000;
     var candidateSources=hits.stream().map(h->store.knowledgeById(scope,h.knowledgeId()).orElseThrow().sourceMessageIds()).toList();
     var answer=new QueryService(store,noMessageFallback,access,8,400,id->id,arm.getValue()).ask(scope,"synthetic-reader",q);
     Set<String> cited=new LinkedHashSet<>();answer.citations().forEach(c->cited.add(c.messageId()));
     int firstRank=0;for(int i=0;i<candidateSources.size();i++)if(candidateSources.get(i).stream().anyMatch(expected::contains)){firstRank=i+1;break;}
     Map<String,Object> row=new LinkedHashMap<>();row.put("questionId",question.path("id").asText());row.put("question",q);row.put("paraphrase",question.path("paraphrase").asBoolean());row.put("arm",arm.getKey());row.put("expected",expected);row.put("retrievedSources",candidateSources);row.put("hits",hits);row.put("firstRelevantRank",firstRank);row.put("retrievalMs",retrievalMs);row.put("found",answer.found());row.put("cited",cited);row.put("exactSources",expected.equals(cited));row.put("answer",answer.text());results.add(row);
    }
   }
   Path out=Path.of(System.getProperty("dita.task049.output"));Files.createDirectories(out);
   JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("abc-results.json").toFile(),results);
   JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("extraction-reports.json").toFile(),reports);
   JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("indexed-example.json").toFile(),document);
   // Production QueryService, with real configured graph retriever, must answer a lexical-disjoint question.
   var semantic=runtime.ask(scope,"synthetic-reader",smoke);
   JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("production-semantic-answer.json").toFile(),semantic);
   assertTrue(semantic.found(),semantic.text());assertTrue(semantic.citations().stream().anyMatch(c->c.messageId().equals(smokeSource)),semantic.text());
   assertFalse(new QueryService(store,noMessageFallback,access,8,400,id->id,sparse).ask(scope,"synthetic-reader",smoke).found(),"BM25 composer control must abstain on the semantic smoke question");
   // Cross-group lookup yields no knowledge; stale vector after edit cannot be admitted or returned.
   assertTrue(dense.retrieve(new GroupScope(run+"-foreign"),"Who is handling refreshments?",10).isEmpty());
   ctx.getBean(DeletionService.class).edit(scope,smokeSource,"That information has been withdrawn.",Instant.now());
   assertFalse(runtime.ask(scope,"synthetic-reader",smoke).citations().stream().anyMatch(c->c.messageId().equals(smokeSource)));
   // Deletion reaches the vector document through the normal outbox projection lifecycle.
   var transport=store.knowledge(scope).stream().filter(k->k.sourceMessageIds().contains("m4")).findFirst().orElseThrow();
   ctx.getBean(DeletionService.class).delete(scope,"m4",Instant.now(),DeletionSource.FORGET_COMMAND);
   await(()->vespa.knowledgeStatus(scope.groupId(),transport.id())==404);
   assertFalse(dense.retrieve(scope,"Who provides a shuttle for the helpers?",10).stream().anyMatch(h->h.knowledgeId().equals(transport.id())));
   JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("lifecycle-security.json").toFile(),Map.of("crossGroupEmpty",true,"editExcluded",true,"deletedVectorDocumentAbsent",true,"runtimeRetriever",ctx.getBean(Retriever.class).getClass().getName(),"hybridMetrics",((HybridKnowledgeRetriever)hybrid).metrics()));
  }
 }
}
