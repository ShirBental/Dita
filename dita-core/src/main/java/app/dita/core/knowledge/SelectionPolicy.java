package app.dita.core.knowledge;

import app.dita.core.answer.GroundedAnswer;
import app.dita.core.model.*;
import app.dita.core.store.MessageStore;
import app.dita.core.text.Tokenizer;
import java.util.*;

/** Query intent and answer eligibility only; never changes assertions, relations or validity. */
public final class SelectionPolicy {
    private SelectionPolicy() {}
    public static boolean outstandingRequest(String question) {
        List<String> raw=Tokenizer.rawWords(question);
        boolean inquiry=raw.stream().anyMatch(Set.of("what","which","any","list","show")::contains);
        return inquiry && (outstandingStatement(question) || raw.contains("unanswered") || raw.contains("unresolved")
                || raw.contains("outstanding") || raw.contains("pending")
                || (raw.contains("open") && raw.stream().anyMatch(Set.of("questions","issues","decisions","tasks")::contains)));
    }
    static boolean outstandingStatement(String text) {
        List<String> words=Tokenizer.rawWords(text);
        for(int i=0;i<words.size();i++) {
            String word=words.get(i);
            if(Set.of("unanswered","unresolved","outstanding","pending").contains(word))return true;
            if(word.equals("still") && i+1<words.size() && Set.of("need","needs","waiting","awaiting","undecided").contains(words.get(i+1)))return true;
            if(word.equals("remain") || word.equals("remains")) {
                if(i+1<words.size() && Set.of("open","unanswered","unresolved","undecided").contains(words.get(i+1)))return true;
            }
        }
        return false;
    }
    static Set<String> outstandingTopics(String question) {
        List<String> prefix=new ArrayList<>(); boolean inquiry=false;
        for(String word:Tokenizer.rawWords(question)) {
            if(Set.of("what","which","any","list","show").contains(word))inquiry=true;
            if(inquiry && Set.of("before","after","until").contains(word))break;
            prefix.add(word);
        }
        Set<String> topic=new LinkedHashSet<>(Tokenizer.normalizedSet(String.join(" ",prefix)));
        topic.removeAll(Tokenizer.normalizedSet("question questions issue issues decision decisions task tasks unanswered unresolved outstanding pending open remain remains remaining still need needs resolve resolving resolved list show any"));
        topic.removeIf(KnowledgeAnswerComposer::isFrame);
        return topic;
    }
    /** A recorded participant question cannot by itself establish an answer to a factual request. */
    public static boolean substantive(MessageStore store,GroupScope scope,GroundedAnswer answer) {
        if(!answer.found())return false;
        if(outstandingRequest(answer.question())) {
            Set<String> topic=outstandingTopics(answer.question());
            return topic.isEmpty() || answer.citations().stream().anyMatch(c->Tokenizer.normalizedSet(c.quote()).stream().anyMatch(topic::contains));
        }
        var all=store.knowledge(scope);
        Set<String> query=KnowledgeAnswerComposer.baseForms(Tokenizer.normalizedSet(answer.question()));
        var focused=all.stream().filter(k->k.assertion()!=null && k.assertion().status()==Assertion.Status.QUESTION)
                .filter(k->{Set<String> topic=KnowledgeAnswerComposer.baseForms(Tokenizer.tokenSet(k.assertion().subject()));return !topic.isEmpty() && query.containsAll(topic);}).toList();
        for(var citation:answer.citations()) {
            var sourceItems=all.stream().filter(k->k.sourceMessageIds().contains(citation.messageId()) && k.assertion()!=null).toList();
            if(sourceItems.isEmpty() || sourceItems.stream().anyMatch(k->k.assertion().status()!=Assertion.Status.QUESTION)) {
                if(focused.isEmpty())return true;
                var message=store.find(scope,citation.messageId()).orElse(null);
                boolean onTopic=focused.stream().anyMatch(q->sourceItems.stream().anyMatch(k->k.assertion().subjectId().equals(q.assertion().subjectId()))
                        || (message!=null && q.sourceMessageIds().contains(message.replyToId()))
                        || KnowledgeAnswerComposer.baseForms(Tokenizer.normalizedSet(citation.quote())).containsAll(KnowledgeAnswerComposer.baseForms(Tokenizer.tokenSet(q.assertion().subject()))));
                if(onTopic)return true;
            }
        }
        return false;
    }
}
