package app.dita.eval.runner;

import app.dita.eval.arms.ArmContextFactory;
import app.dita.eval.arms.ArmId;
import app.dita.eval.arms.ArmSettings;
import app.dita.eval.arms.StoreReplay;
import app.dita.eval.corpus.Corpus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runner-side: loads prompts and corpora through {@link RunnerFileAccess} only, replays stores, builds arm contexts. */
public final class ContextPlanner {
    private final RunnerFileAccess files;
    private final ArmContextFactory factory;
    private final Map<String, Corpus> corpora = new HashMap<>();
    private final Map<String, Optional<Corpus>> decoys = new HashMap<>();
    private final Map<String, StoreReplay.Replayed> replays = new HashMap<>();

    public ContextPlanner(RunnerFileAccess files, ArmSettings settings) {
        this.files = files; this.factory = new ArmContextFactory(settings);
    }

    public RunnerFileAccess files() { return files; }
    public ArmContextFactory factory() { return factory; }

    public List<Prompt> prompts(String split) { return files.prompts(split); }

    public Corpus corpus(String id) { return corpora.computeIfAbsent(id, files::corpus); }

    public Optional<Corpus> decoy(String id) { return decoys.computeIfAbsent(id, files::decoyFor); }

    public static String replayKey(Prompt p) { return p.corpusId() + "|" + p.asOf(); }

    /** Fresh replay (used for timing); does not touch the cache. */
    public StoreReplay.Replayed replayFresh(Prompt p) {
        return StoreReplay.replay(corpus(p.corpusId()), decoy(p.corpusId()).orElse(null), p.asOf());
    }

    public StoreReplay.Replayed replay(Prompt p) { return replays.computeIfAbsent(replayKey(p), k -> replayFresh(p)); }

    public void putReplay(Prompt p, StoreReplay.Replayed r) { replays.put(replayKey(p), r); }

    public ArmContextFactory.ArmContext context(ArmId arm, Prompt p) {
        return factory.build(arm, corpus(p.corpusId()), replay(p), p);
    }
}
