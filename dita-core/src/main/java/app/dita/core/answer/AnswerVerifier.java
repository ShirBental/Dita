package app.dita.core.answer;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;

import java.util.List;
import java.util.Optional;

/**
 * Last gate before an answer leaves Dita. A citation survives only if the message exists in the caller's
 * group, is not deleted, and the quote occurs verbatim in it. Applies to every composer, including LLM ones.
 */
public final class AnswerVerifier {
    private final MessageStore store;

    public AnswerVerifier(MessageStore store) { this.store = store; }

    public GroundedAnswer verify(GroupScope scope, GroundedAnswer answer) {
        List<Citation> kept = answer.citations().stream().filter(c -> valid(scope, c)).toList();
        if (kept.isEmpty()) return GroundedAnswer.notFound(answer.question());
        if (kept.size() == answer.citations().size()) return answer;
        return ExtractiveAnswerComposer.render(answer.question(), kept);
    }

    private boolean valid(GroupScope scope, Citation c) {
        Optional<Message> m = store.find(scope, c.messageId());
        return m.isPresent() && !m.get().deleted() && c.quote() != null && !c.quote().isBlank()
                && m.get().text().contains(c.quote()) && m.get().authorId().equals(c.authorId());
    }
}
