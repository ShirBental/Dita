package app.dita.core.answer.synthesis;

import app.dita.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TASK-053: deterministic grounding checks on a generator's draft. Any violation rejects the draft and the caller
 * returns Dita's deterministic answer. Rules are structural/lexical only; no model judges a model. Deliberately strict:
 * a rejected good paraphrase costs fluency, an accepted bad one costs trust.
 * <p>Unit of checking: the CLAUSE, i.e. the text from the previous citation marker up to and including the next
 * marker(s). Every claim must sit in a clause that cites its evidence, and is checked against THAT clause's quotes only
 * (Natasha TASK-053 F1/F4: neither the question nor other evidence nor Dita's draft may lend words to a clause).
 * <ol>
 *   <li>R1 shape: non-empty, bounded, no URLs/handles.</li>
 *   <li>R2 citation validity: markers and cited ids are evidence supplied in THIS request (replaced ids only as markers).</li>
 *   <li>R3 grounding: every clause with content cites supplied evidence; no content after the last marker.</li>
 *   <li>R4 attribution: a clause names the pseudonym of every speaker it cites and no other pseudonym. DEC-051: within
 *       ONE sentence, a clause that names nobody may continue the previous clause's speaker when every evidence it cites
 *       is by that same speaker ("P1 said A [m1], at B [m2]" with m2 by P1); a speaker change, a new sentence, or a
 *       previous clause that was not cleanly attributed to one speaker requires explicit attribution again.</li>
 *   <li>R5 conflict: when members disagree, both stances are cited; no consensus wording.</li>
 *   <li>R6 speech acts: offers keep offer wording and gain no commitment wording; questions stay open; decision or
 *       commitment wording only for DECISION/CONFIRMED_COMMITMENT evidence or when the quote itself says it (F3);
 *       relayed claims stay attributed.</li>
 *   <li>R7 supersession: a replaced id appears only with replacement wording.</li>
 *   <li>R8 no unsupported content: clause terms come from that clause's quotes or a fixed framing vocabulary; numbers
 *       only from that clause's quotes. DEC-051, all conservative: (a) a neutral reporting verb ("stated", "noted")
 *       only directly after a pseudonym; (b) an inflected form of a word in the clause's quotes ("used" for "use",
 *       "found" for "find"; regular suffixes + a closed irregular list); (c) a TOPIC reference: a question word used as
 *       "the/this/that X" is allowed only when some supplied message (not the question) contains X. The question never
 *       authorizes any other term, so a false premise ("the barbecue" when nobody mentioned one) stays unsupported.</li>
 *   <li>R8 reference subjects: trusted LOCAL reference metadata may resolve one definite object in an immediately
 *       preceding selected question to sentence-initial "It is" in its answer. Only that exact subject is substituted;
 *       predicates and numbers remain citation-local, and the question is retained as contextual provenance. A neutral
 *       WHERE field label is presentation only; its place words still require the directly cited quote.</li>
 *   <li>R8 order words (DEC-052): "later"/"then" only when the request's local send times show every message of the
 *       clause sent strictly after every message cited before it in the answer (unknown or equal times → rejected, order
 *       uncertain; "then" in the cited quote is content only before any other citation); "first" only when quoted; "earlier"/"previous(ly)" only with a
 *       replaced message cited in the clause or when quoted.</li>
 *   <li>R9 stance: negation/opposition wording needs cited evidence that is against (polarity &lt; 0) or that contains
 *       the same negation in its own quote (F2).</li>
 *   <li>R11 qualifiers (DEC-051, narrow): when a cited non-question quote carries a condition or hedge ("if", "unless",
 *       "permitting", "maybe", "probably", "might", "may", "perhaps", "possibly", "depends", "not sure", "I think"), the
 *       clause citing it must carry one too. Lexical: it detects a DROPPED qualifier, not a changed one.</li>
 * </ol>
 * Known limits (documented, not hidden): a qualifier replaced by a different one, qualifiers outside the R11 list, and
 * stale-but-not-superseded evidence presented as current are not detected (F5, ADV9); a topic reference allowed by
 * R8(c) could be attached to a cited message from another exchange (the older topic-reference rule has no exchange constraint); Hebrew answers nearly always fall back (no Hebrew framing vocabulary).
 */
public final class SynthesisValidator {
    public static final int MAX_CHARS = 1200;
    static final Pattern MARKER = Pattern.compile("\\[([A-Za-z0-9_.:-]{1,80})\\]");
    static final Pattern MARKER_RUN = Pattern.compile("(\\s*\\[[A-Za-z0-9_.:-]{1,80}\\])+");
    static final Pattern ALIAS = Pattern.compile("\\bP(\\d{1,3})\\b");
    static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|@[a-z0-9_]{2,})");
    static final Pattern NUMBER = Pattern.compile("\\d+");
    /** Single-word bracket tags the deterministic composer writes; they are labels, not citations. */
    static final Set<String> COMPOSER_TAGS = Set.of("tentative");
    /** Negated decision/commitment wording ("not confirmed", "no decision") is a correct description, not a claim. */
    static final Pattern NEGATED_COMMIT = Pattern.compile(
            "(?i)\\b(not|never|un|no|nothing\\s+(was|has\\s+been|is))\\s*-?\\s*(yet\\s+)?(been\\s+)?(confirmed|committed|decided|decision)\\b");
    static final Set<String> CONSENSUS = Set.of("everyone agrees", "all agree", "everybody agrees", "consensus", "unanimous",
            "agreed by all", "no disagreement", "all members agree", "the group agrees");
    static final Set<String> OFFER_WORDS = Set.of("offer", "offered", "offers", "might", "may", "could", "can", "probably",
            "tentative", "tentatively", "if", "possibly", "not confirmed", "unconfirmed", "maybe");
    static final Set<String> COMMIT_WORDS = Set.of("confirmed", "committed", "commitment", "will definitely", "guaranteed",
            "has agreed", "is bringing", "will bring", "promised", "decided", "decision", "decides", "decide", "will be");
    static final Set<String> QUESTION_WORDS = Set.of("asked", "asks", "question", "unanswered", "nobody", "no one", "not yet",
            "no answer", "open");
    static final Set<String> RELAYED_WORDS = Set.of("relayed", "reported", "reports", "heard", "according", "passed on", "says that", "said that");
    static final Set<String> REPLACE_WORDS = Set.of("replace", "replaces", "replaced", "earlier", "previous", "previously",
            "changed", "moved", "correct", "corrected", "correction", "update", "updated", "no longer", "instead");
    static final Set<String> NEGATION = Set.of("not", "no", "never", "against", "avoid", "avoids", "avoided", "nobody", "nothing",
            "none", "don't", "doesn't", "didn't", "isn't", "won't", "can't", "cannot", "couldn't", "wouldn't", "shouldn't", "aren't",
            "wasn't", "weren't", "haven't", "hasn't", "hadn't", "mustn't", "needn't", "shan't", "ain't",
            "cant", "dont", "didnt", "doesnt", "isnt", "wont", "couldnt", "wouldnt", "shouldnt", "arent", "wasnt", "werent",
            "havent", "hasnt", "hadnt", "disagree", "disagrees", "oppose", "opposes", "opposed");
    /** Framing words a faithful answer may add. They describe statements; they carry no facts of their own. */
    static final List<String> FRAMING_WORDS = List.of(
            "said", "say", "says", "saying", "wrote", "mentioned", "according", "member", "members", "group", "chat",
            "recommend", "recommends", "recommended", "recommendation", "advise", "advises", "advised", "against", "avoid",
            "disagree", "disagrees", "disagreement", "disagreed", "differ", "views", "view", "opinion", "opinions", "side",
            "offer", "offered", "offers", "might", "may", "could", "probably", "tentative", "tentatively", "possibly",
            "unconfirmed", "confirmed", "confirm", "committed", "commitment", "decided", "decision", "decide", "decides",
            "asked", "asks", "ask", "question", "unanswered", "nobody", "no", "one", "not", "yet", "answer", "answered",
            "open", "still", "earlier", "previous", "previously", "replaced", "replace", "replaces", "changed", "moved",
            "corrected", "correction", "update", "updated", "longer", "instead", "however", "while", "whereas", "both",
            "other", "another", "also", "only", "unclear", "uncertain", "ambiguous", "sure", "suggested", "suggestion",
            "suggests", "statement", "statements", "there", "will", "would", "should", "nothing", "anything", "someone",
            "anyone", "which", "whether", "they", "their", "them", "has", "had", "did", "first", "then", "separately",
            "but", "so", "though", "although", "message", "messages", "relayed", "reported", "heard");
    /** DEC-052: order words, checked by R8 before the framing list applies. */
    static final List<String> ORDER_WORDS = List.of("later", "then", "first", "earlier", "previous", "previously");

    static final Set<String> FRAMING = Set.copyOf(Tokenizer.normalizedTokens(String.join(" ", FRAMING_WORDS)));
    /**
     * TASK-054: neutral attribution verbs that are NOT in {@link #FRAMING} because, outside attribution, they can carry
     * content ("the state", "statement of"). They are accepted ONLY in attribution position, directly after a speaker
     * pseudonym ("P1 stated ...", "P1 also states ..."), and only for R8. Closed list; everything after the verb is still
     * checked against the clause's quotes.
     */
    static final Pattern ATTRIBUTION_VERB = Pattern.compile("\\b(P\\d{1,3})(\\s+also)?\\s+(?:stated|states|noted(?:\\s+as)?|notes)\\b");

    /** DEC-051 R8(b): closed irregular past/participle forms → base. Anything else must match a regular suffix rule. */
    static final Map<String, String> IRREGULAR = Map.ofEntries(Map.entry("found", "find"), Map.entry("made", "make"),
            Map.entry("said", "say"), Map.entry("went", "go"), Map.entry("gone", "go"), Map.entry("took", "take"),
            Map.entry("taken", "take"), Map.entry("brought", "bring"), Map.entry("bought", "buy"), Map.entry("met", "meet"),
            Map.entry("kept", "keep"), Map.entry("told", "tell"), Map.entry("thought", "think"),
            Map.entry("came", "come"), Map.entry("got", "get"), Map.entry("gave", "give"), Map.entry("given", "give"),
            Map.entry("knew", "know"), Map.entry("known", "know"), Map.entry("sent", "send"), Map.entry("paid", "pay"));
    /** DEC-051 R8(c): a question word may be used only as a topic reference in this position. */
    static final Pattern TOPIC_REF = Pattern.compile("(?i)\\b(?:the|this)\\s+([\\p{L}][\\p{L}'-]*)");
    static final Pattern CONDITION_Q = Pattern.compile("(?i)\\b(if|unless|permitting|provided|assuming|in case|depends?|depending)\\b");
    static final Pattern HEDGE_Q = Pattern.compile("(?i)\\b(maybe|probably|might|may|perhaps|possibly|not sure|i think|tentative|tentatively)\\b");
    /** In the ANSWER a hedge may also be expressed as offer / not-confirmed wording ("P3 offered to …, not confirmed"). */
    static final Pattern HEDGE_OK = Pattern.compile("(?i)\\b(maybe|probably|might|may|perhaps|possibly|not sure|i think|tentative|tentatively|"
            + "could|not confirmed|unconfirmed)\\b");
    /** Offer / suggestion wording keeps a hedge only for OFFER evidence ("I might come" is not an offer). */
    static final Pattern OFFER_HEDGE_OK = Pattern.compile("(?i)\\b(offer|offers|offered|suggested|suggests)\\b");
    /** R4 carry (Natasha DEC-051 H1): a clause that refers to OTHER people never inherits the sentence's speaker. */
    static final Pattern PEOPLE = Pattern.compile("(?i)\\b(other|others|another|members?|group|someone|somebody|anyone|anybody|everyone|"
            + "everybody|nobody|no one|people|they|their|them|he|she|his|her|him|we|us|our|you|your|i|me|my|both|each|who|whoever|chat)\\b");
    /** R12 (Natasha DEC-051 H2): negation that a cited statement carries must not be dropped. */
    static final Pattern NEGATED = Pattern.compile("(?i)\\b(not|no|never|cannot|nothing|nobody|none|nor|neither|cant|dont|didnt|doesnt|isnt|wont|couldnt|wouldnt|"
            + "shouldnt|arent|wasnt|werent|havent|hasnt|hadnt)\\b|n't\\b");
    static final Pattern SENTENCE_BREAK = Pattern.compile("^\\s*[.!?]|[.!?]\\s+\\S");

    public record Verdict(List<String> violations, Set<String> unsupportedTerms, Set<String> contextualSourceIds) {
        public Verdict(List<String> violations, Set<String> unsupportedTerms) { this(violations, unsupportedTerms, Set.of()); }
        public boolean ok() { return violations.isEmpty(); }
    }

    /** A clause: its text (without markers), the supplied-evidence ids it cites, and every marker it carries. */
    record Clause(String text, Set<String> support, Set<String> markers) {}

    /** Source-id integrity only: no linguistic, factual, chronology or wording judgments. */
    public Verdict validateReferences(AnswerSynthesizer.Request req, AnswerSynthesizer.Draft d) {
        Set<String> supplied = new HashSet<>();
        req.evidence().forEach(e -> supplied.add(e.id()));
        Set<String> markers = new HashSet<>();
        Matcher m = MARKER.matcher(d.answer());
        while (m.find()) markers.add(m.group(1));
        Set<String> used = new HashSet<>(d.citedIds());
        List<String> errors = new ArrayList<>();
        if (d.answer().isBlank()) errors.add("empty model answer");
        if (used.isEmpty() || !supplied.containsAll(used)) errors.add("missing or unknown source ids");
        if (!markers.equals(used)) errors.add("source markers differ from cited ids");
        return new Verdict(List.copyOf(errors), Set.of());
    }

    public Verdict validate(AnswerSynthesizer.Request req, AnswerSynthesizer.Draft d) {
        List<String> v = new ArrayList<>();
        Set<String> unsupported = new LinkedHashSet<>();
        Set<String> contextualSources = new LinkedHashSet<>();
        Map<String, AnswerSynthesizer.Evidence> byId = new HashMap<>();
        req.evidence().forEach(e -> byId.put(e.id(), e));
        Set<String> replaced = new HashSet<>(req.replaced());
        String text = d.answer().strip();

        // R1 shape
        if (text.isEmpty()) { v.add("R1 empty answer"); return new Verdict(v, unsupported); }
        if (text.length() > MAX_CHARS) v.add("R1 answer longer than " + MAX_CHARS + " chars");
        if (URL.matcher(text).find()) v.add("R1 URL or handle in answer");

        // R2 citation validity
        Set<String> markers = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) markers.add(m.group(1));
        markers.removeAll(COMPOSER_TAGS);
        for (String id : markers) if (!byId.containsKey(id) && !replaced.contains(id)) v.add("R2 marker [" + id + "] is not supplied evidence");
        for (String id : d.citedIds()) if (!byId.containsKey(id)) v.add("R2 cited id " + id + " is not supplied evidence");
        Set<String> support = new LinkedHashSet<>();
        for (String id : markers) if (byId.containsKey(id)) support.add(id);
        if (support.isEmpty()) v.add("R2 no supplied evidence is cited");
        if (!new HashSet<>(d.citedIds()).equals(support)) v.add("R2 citedIds differ from the evidence markers in the text");

        Set<String> citedPolarities = new HashSet<>();
        Set<String> topicRefs = topicReferences(req);
        String carry = null;                       // R4 (DEC-051): the speaker this sentence is attributed to so far
        Set<String> citedSoFar = new HashSet<>();  // R8 order words (DEC-052): every id cited before this clause
        for (Clause c : clauses(text, byId.keySet())) {
            if (SENTENCE_BREAK.matcher(c.text()).find()) carry = null;
            String plain = ALIAS.matcher(c.text()).replaceAll(" ");
            List<String> content = Tokenizer.normalizedTokens(plain);
            String cl = norm(c.text());          // NFKC, curly apostrophes → ' (Natasha DEC-051 blocker)
            if (c.markers().isEmpty()) {           // text after the last marker
                if (!content.isEmpty()) v.add("R3 text after the last citation: \"" + clip(c.text()) + "\"");
                continue;
            }
            if (c.support().isEmpty()) {
                if (!content.isEmpty()) v.add("R3 clause without supplied evidence: \"" + clip(c.text()) + "\"");
                continue;
            }
            List<AnswerSynthesizer.Evidence> ev = c.support().stream().map(byId::get).toList();
            String quotes = norm(String.join(" ", ev.stream().map(AnswerSynthesizer.Evidence::quote).toList()));

            // R4 attribution, per clause
            Set<String> speakers = new HashSet<>();
            ev.forEach(e -> speakers.add(e.speaker()));
            Set<String> named = new HashSet<>();
            Matcher am = ALIAS.matcher(c.text());
            while (am.find()) named.add(am.group());
            if (named.isEmpty() && carry != null && PEOPLE.matcher(cl).find()) {
                for (var e : ev) v.add("R4 [" + e.id() + "] continues " + carry + "'s sentence but refers to other people: name the speaker");
                carry = null;
            } else if (named.isEmpty() && carry != null) {
                // continuation within one sentence: allowed only when the cited evidence is by the same speaker
                for (var e : ev) if (!e.speaker().equals(carry))
                    v.add("R4 [" + e.id() + "] continues " + carry + "'s sentence but is by " + e.speaker() + ": name the speaker");
            } else {
                for (var e : ev) if (!named.contains(e.speaker())) v.add("R4 [" + e.id() + "] cited without naming its speaker " + e.speaker());
            }
            for (String p : named) if (!speakers.contains(p)) v.add("R4 names " + p + " without citing that speaker's evidence");
            if (named.size() == 1 && speakers.equals(named)) carry = named.iterator().next();
            else if (!(named.isEmpty() && carry != null && speakers.equals(Set.of(carry)))) carry = null;

            // R6 speech acts
            String unnegated = NEGATED_COMMIT.matcher(cl).replaceAll(" ");
            boolean decisive = ev.stream().anyMatch(e -> e.kind().equals("DECISION") || e.kind().equals("CONFIRMED_COMMITMENT"));
            for (String w : COMMIT_WORDS) {
                if (!containsWord(unnegated, w)) continue;
                boolean offer = ev.stream().anyMatch(e -> e.kind().equals("OFFER"));
                if (offer) v.add("R6 an offer is presented with commitment wording \"" + w + "\"");
                else if (!decisive && !containsWord(quotes, w)) v.add("R6 decision/commitment wording \"" + w + "\" without such evidence");
            }
            if (ev.stream().anyMatch(e -> e.kind().equals("OFFER")) && OFFER_WORDS.stream().noneMatch(w -> containsWord(cl, w)))
                v.add("R6 an offer is stated without offer/hedge wording");
            if (ev.stream().allMatch(e -> e.kind().equals("QUESTION")) && QUESTION_WORDS.stream().noneMatch(w -> containsWord(cl, w)))
                v.add("R6 a question is presented as an answer");
            if (ev.stream().anyMatch(e -> e.kind().equals("REPORTED_CLAIM")) && RELAYED_WORDS.stream().noneMatch(w -> containsWord(cl, w)))
                v.add("R6 a relayed claim is stated without relayed/heard wording");

            // R7 replaced ids only with replacement wording
            if (c.markers().stream().anyMatch(replaced::contains) && REPLACE_WORDS.stream().noneMatch(w -> containsWord(cl, w)))
                v.add("R7 a replaced message is cited without replacement wording");

            // R8 unsupported content: only this clause's quotes + framing (+ an attribution verb right after a pseudonym)
            Set<String> allowed = new HashSet<>(FRAMING);
            allowed.addAll(Tokenizer.normalizedTokens(quotes));
            String supportedText = ReferenceSupport.resolveSubject(req, c.text(), ev, contextualSources);
            supportedText = ReferenceSupport.locationLabel(req.question(), supportedText, ev);
            if (named.isEmpty() && carry != null && speakers.equals(Set.of(carry)))
                supportedText = supportedText.replaceFirst("^(\\s*,?\\s*and\\s+)(?=(?:stated|states|noted|notes)\\b)", "$1" + carry + " ");
            String r8text = ALIAS.matcher(ATTRIBUTION_VERB.matcher(supportedText).replaceAll("$1$2")).replaceAll(" ");
            Set<String> quoteWords = words(quotes);
            // R8(c): topic references "the X" where X is a question word that some supplied message contains
            Matcher tr = TOPIC_REF.matcher(r8text);
            StringBuilder rest = new StringBuilder();
            int last = 0;
            while (tr.find()) {
                if (!topicRefs.contains(tr.group(1).toLowerCase(Locale.ROOT))) continue;
                rest.append(r8text, last, tr.start(1)).append(' ');
                last = tr.end(1);
            }
            rest.append(r8text.substring(last));
            // DEC-052 (+ Natasha M1): order words are never free framing.
            //  "later"/"then": send times must show this clause's messages after EVERY message cited before it in the answer
            //  ("then" quoted by the cited message is content). "first": only when quoted. "earlier"/"previous(ly)": only
            //  with a replaced message cited in this clause (R7) or when quoted.
            Set<String> restWords = words(rest.toString());
            for (String w : ORDER_WORDS) {
                if (!restWords.contains(w)) continue;
                boolean quoted = quoteWords.contains(w) && !w.equals("later");
                boolean ok = verbatimOrder(c.text(), ev, w) || switch (w) {
                    // "then" quoted is content only when nothing was cited before it (Natasha L1: quoteWords spans
                    // every quote of the clause, so after a citation an order claim always needs the send times)
                    case "later", "then" -> (quoted && citedSoFar.isEmpty()) || laterEstablished(req.sentAt(), citedSoFar, c.support());
                    case "first" -> quoted;
                    default -> quoted || c.markers().stream().anyMatch(replaced::contains);
                };
                if (!ok) v.add("R8 \"" + w + "\" without " + (w.equals("later") || w.equals("then")
                        ? "timestamps that establish the order" : w.equals("first") ? "the cited message saying it" : "a replaced message"));
                rest = new StringBuilder(rest.toString().replaceAll("(?i)\\b" + w + "\\b", " "));
            }
            for (String w : words(rest.toString())) {
                if (quoteWords.contains(w) || formOf(w, quoteWords)) continue;      // R8(b) inflected forms of quoted words
                for (String t : Tokenizer.normalizedTokens(w)) if (!allowed.contains(t) && !NUMBER.matcher(t).matches()) { unsupported.add(t); }
            }
            Set<String> nums = new HashSet<>(numbers(quotes));
            for (String n : numbers(plain)) if (!nums.contains(n)) unsupported.add(n);

            // R9 stance
            String stance = NEGATED_COMMIT.matcher(cl).replaceAll(" ");
            for (String w : NEGATION) {
                if (!containsWord(stance, w)) continue;
                boolean ok = ev.stream().anyMatch(e -> e.polarity() < 0) || containsWord(quotes, w);
                if (!ok) { v.add("R9 negation/opposition \"" + w + "\" not supported by the cited evidence"); break; }
            }
            for (var e : ev) citedPolarities.add(Integer.toString(Integer.signum(e.polarity())));
            citedSoFar.addAll(c.support());

            // R11 (DEC-051): a condition/hedge in a cited statement is not dropped
            // (same class: a condition is kept as a condition, a hedge as a hedge)
            // R12 (DEC-051): a negation in a cited statement is not dropped ("I can't find a venue" ≠ "P1 found a venue")
            String plainNorm = norm(plain);
            for (var e : ev) {
                if (e.kind().equals("QUESTION") || e.quote().strip().endsWith("?")) continue;
                String quote = norm(e.quote());
                for (Pattern cls : List.of(CONDITION_Q, HEDGE_Q)) {
                    Matcher q = cls.matcher(quote);
                    boolean kept = cls == HEDGE_Q
                            ? HEDGE_OK.matcher(plainNorm).find() || (e.kind().equals("OFFER") && OFFER_HEDGE_OK.matcher(plainNorm).find())
                            : cls.matcher(plainNorm).find();
                    if (q.find() && !kept)
                        v.add("R11 [" + e.id() + "] drops the qualifier \"" + q.group() + "\"");
                }
                // "not confirmed" describes the speech act; it does not keep the statement's own negation
                if (NEGATED.matcher(quote).find() && !NEGATED.matcher(NEGATED_COMMIT.matcher(plainNorm).replaceAll(" ")).find())
                    v.add("R12 [" + e.id() + "] drops the negation of the cited statement");
            }
        }
        if (!unsupported.isEmpty()) v.add("R8 unsupported terms: " + unsupported);

        // R5 conflict preservation
        var cc = req.context();
        if (cc != null) {
            // G2 (raw messages): each GENUINE conflict group keeps both sides. Opposing statements explicitly about
            // different contexts are not a conflict; unknown context is (ContextCues). An unrelated positive or negative
            // statement cannot stand in for a missing side of a group.
            Set<String> cited = new HashSet<>();
            for (Clause c : clauses(text, byId.keySet())) cited.addAll(c.markers());
            Set<String> qNames = app.dita.core.text.Tokenizer.entities(req.question());
            for (Set<String> group : cc.conflictGroups()) {
                // A group is in play when the answer cites any member of it, or when it may be about what was asked:
                // the question names nobody, the group names nobody (pronouns only: uncertain), or they share a name.
                // Only a group about an explicitly DIFFERENT named subject, untouched by the answer, is out of play.
                Set<String> gNames = new HashSet<>();
                for (String id : group) if (byId.containsKey(id)) gNames.addAll(app.dita.core.answer.ContextCues.subjects(byId.get(id).quote()));
                boolean inPlay = group.stream().anyMatch(cited::contains) || qNames.isEmpty() || gNames.isEmpty()
                        || !java.util.Collections.disjoint(gNames, qNames);
                if (!inPlay) continue;
                boolean p = group.stream().anyMatch(id -> cited.contains(id) && byId.containsKey(id) && byId.get(id).polarity() > 0);
                boolean n = group.stream().anyMatch(id -> cited.contains(id) && byId.containsKey(id) && byId.get(id).polarity() < 0);
                if (!p) v.add("R5 conflicting evidence: the supporting side is missing " + new java.util.TreeSet<>(group));
                if (!n) v.add("R5 conflicting evidence: the opposing side is missing " + new java.util.TreeSet<>(group));
            }
            // R10 (G2) no extrapolation across contexts: a question that names a context ("for adults") may not be
            // answered only from statements whose explicit context is different. Unknown-context statements are allowed
            // (they are not about another population as far as Dita can tell); lexical, so synonyms are stricter.
            Set<String> q = cc.questionContext();
            if (!q.isEmpty() && !cited.isEmpty()) {
                boolean anyCompatible = cited.stream().anyMatch(id -> {
                    Set<String> e = cc.evidenceContext().getOrDefault(id, Set.of());
                    return e.isEmpty() || !app.dita.core.answer.ContextCues.distinctContexts(e, q);
                });
                if (!anyCompatible) v.add("R10 the question's context " + new java.util.TreeSet<>(q)
                        + " is not supported: every cited statement is about a different context");
            }
        } else if (req.conflicting()) {
            boolean hasPos = req.evidence().stream().anyMatch(e -> e.polarity() > 0);
            boolean hasNeg = req.evidence().stream().anyMatch(e -> e.polarity() < 0);
            if (hasPos && !citedPolarities.contains("1")) v.add("R5 conflicting evidence: the supporting side is missing");
            if (hasNeg && !citedPolarities.contains("-1")) v.add("R5 conflicting evidence: the opposing side is missing");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String c : CONSENSUS) if (lower.contains(c)) v.add("R5 consensus wording: \"" + c + "\"");
        return new Verdict(List.copyOf(v), Set.copyOf(unsupported), Set.copyOf(contextualSources));
    }

    /** Splits the answer at each run of citation markers; the final clause (no markers) is any trailing text. */
    static List<Clause> clauses(String text, Set<String> evidenceIds) {
        List<Clause> out = new ArrayList<>();
        Matcher run = MARKER_RUN.matcher(text);
        int from = 0;
        while (run.find()) {
            Set<String> ms = new LinkedHashSet<>();
            Matcher mm = MARKER.matcher(run.group());
            while (mm.find()) ms.add(mm.group(1));
            ms.removeAll(COMPOSER_TAGS);
            String body = text.substring(from, run.start());
            from = run.end();
            if (ms.isEmpty()) { out.add(new Clause(body, Set.of(), Set.of())); continue; }
            Set<String> sup = new LinkedHashSet<>();
            for (String id : ms) if (evidenceIds.contains(id)) sup.add(id);
            out.add(new Clause(body, sup, ms));
        }
        out.add(new Clause(text.substring(from), Set.of(), Set.of()));
        return out;
    }

    /**
     * DEC-052: the claimed order is established only when every message of {@code current} was sent strictly after every
     * message of {@code previous} (send times known for all of them, compared to the second). Otherwise it is uncertain.
     */
    static boolean laterEstablished(Map<String, java.time.Instant> sentAt, Set<String> previous, Set<String> current) {
        if (previous.isEmpty() || current.isEmpty()) return false;
        long latestBefore = Long.MIN_VALUE, earliestNow = Long.MAX_VALUE;
        for (String id : previous) { var t = sentAt.get(id); if (t == null) return false; latestBefore = Math.max(latestBefore, t.getEpochSecond()); }
        for (String id : current) { var t = sentAt.get(id); if (t == null) return false; earliestNow = Math.min(earliestNow, t.getEpochSecond()); }
        return earliestNow > latestBefore;
    }

    /** A directly quoted complete source sentence reports the source's wording, not the answer's chronology. */
    static boolean verbatimOrder(String clause, List<AnswerSynthesizer.Evidence> evidence, String word) {
        String reported = norm(clause).strip().replaceFirst("^[.!?\\s]*p[0-9]+\\s+(?:said|noted|stated)\\s+", "");
        reported = reported.replaceAll("^[\"“]|[\"”.!?]+$", "").strip();
        if (containsWord(reported, word) && words(reported).size() >= 3) {
            Pattern sentence = Pattern.compile("(?:^|[.!?]\\s+)" + Pattern.quote(reported) + "(?:[.!?]|$)");
            if (evidence.stream().anyMatch(e -> sentence.matcher(norm(e.quote()).strip()).find())) return true;
        }
        Matcher quoted = Pattern.compile("[\"“]([^\"”]+)[\"”]").matcher(clause);
        while (quoted.find()) {
            String span = norm(quoted.group(1)).strip().replaceAll("[.!?]+$", "");
            if (!containsWord(span, word) || words(span).size() < 3) continue;
            Pattern sentence = Pattern.compile("(?:^|[.!?]\\s+)" + Pattern.quote(span) + "(?:[.!?]|$)");
            if (evidence.stream().anyMatch(e -> sentence.matcher(norm(e.quote()).strip()).find())) return true;
        }
        return false;
    }

    /** NFKC, lower case, typographic apostrophes as ' (the same normalization as {@link Tokenizer#rawWords}). */
    static String norm(String s) {
        // apostrophe look-alikes mapped BEFORE NFKC (NFKC turns "´" into a space + combining accent)
        String apos = "[\u2019\u2018\u02BC\u0060\u00B4\u2032\uFF07\u05F3]";
        String t = java.text.Normalizer.normalize((s == null ? "" : s).replaceAll(apos, "'"), java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll(apos, "'");
        // "can t" / "won t": a contraction typed with a space is still a negation (Natasha DEC-051 re-review)
        return CONTRACTION_SPACE.matcher(t).replaceAll("$1't");
    }

    static final Pattern CONTRACTION_SPACE = Pattern.compile(
            "\\b(can|won|don|isn|aren|wasn|weren|didn|doesn|couldn|wouldn|shouldn|haven|hasn|hadn|mustn|needn|shan|ain)\\s+t\\b");

    /** Lower-case words of {@code s} as {@link Tokenizer#rawWords} splits them, after {@link #norm}. */
    static Set<String> words(String s) {
        return new LinkedHashSet<>(Tokenizer.rawWords(norm(s)));   // NFKC, ’ → ' : "can’t" stays one word (Natasha DEC-051 blocker)
    }

    /**
     * R8(b): {@code w} is an inflected form of a quoted word: a closed irregular form, or a regular suffix (-s, -es,
     * -ed, -ing, doubled consonant; no bare -d: "feed" is not "fee") whose base is quoted. Never the other way round across different words, and
     * never for words of fewer than 3 letters.
     */
    static boolean formOf(String w, Set<String> quoteWords) {
        if (w.length() < 3 || NEGATION.contains(w)) return false;          // "noted" is never a form of "not", nor the reverse
        String irr = IRREGULAR.get(w);
        if (irr != null && quoteWords.contains(irr)) return true;
        for (String q : quoteWords) { String qi = IRREGULAR.get(q); if (qi != null && qi.equals(w)) return true; }   // "find" for quoted "found"
        List<String> bases = bases(w);
        for (String b : bases) if (quoteWords.contains(b) && !NEGATION.contains(b)) return true;
        // sibling forms of one verb ("postponed" for quoted "postponing"): the same base of at least 4 letters
        for (String q : quoteWords) {
            if (NEGATION.contains(q)) continue;
            for (String b : bases(q)) if (b.length() >= 4 && bases.contains(b)) return true;
        }
        for (String q : quoteWords) for (String suf : List.of("ing", "ed", "es", "s"))      // the quote is inflected, the answer is the base
            if (q.length() > w.length() && q.startsWith(w) && q.substring(w.length()).equals(suf)) return true;
        return false;
    }

    /** Regular-suffix bases of {@code w} (-ing, -ed, -es, -s; with "e" restored and a doubled consonant undone). */
    static List<String> bases(String w) {
        List<String> out = new ArrayList<>();
        for (String suf : List.of("ing", "ed", "es", "s")) {
            if (!w.endsWith(suf) || w.length() - suf.length() < 2) continue;
            String b = w.substring(0, w.length() - suf.length());
            if (b.length() >= 3) out.add(b);
            // silent "e" restored only after a consonant preceded by a vowel ("us"→"use", "mov"→"move"; "fe"/"se" never)
            char lastC = b.charAt(b.length() - 1), prev = b.charAt(b.length() - 2);
            if ("aeiou".indexOf(lastC) < 0 && "aeiou".indexOf(prev) >= 0) out.add(b + "e");
            if (b.length() >= 4 && b.charAt(b.length() - 1) == b.charAt(b.length() - 2)) out.add(b.substring(0, b.length() - 1));
        }
        return out;
    }

    /** R8(c): content words of the question that some SUPPLIED message also contains (the question alone never counts). */
    static Set<String> topicReferences(AnswerSynthesizer.Request req) {
        Set<String> q = words(req.question());
        Set<String> supplied = new HashSet<>();
        for (var e : req.evidence()) supplied.addAll(words(e.quote()));
        Set<String> out = new HashSet<>();
        for (String w : q) if (w.length() >= 3 && !FRAMING.contains(w) && supplied.contains(w)) out.add(w);
        return out;
    }

    static List<String> numbers(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(s == null ? "" : s);
        while (m.find()) out.add(m.group());
        return out;
    }

    static boolean containsWord(String haystackLower, String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])").matcher(haystackLower).find();
    }

    private static String clip(String s) { String t = s.strip(); return t.length() > 80 ? t.substring(0, 80) + "…" : t; }
}
