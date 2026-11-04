package app.dita.core.link;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;

import java.util.List;

/** Proposes links for a new message. Implementations: rules (T0), Muse adjudication (T1/T2). */
public interface LinkInferencer {
    List<MessageLink> infer(GroupScope scope, Message message, List<Message> window, List<MessageLink> knownLinks);
}
