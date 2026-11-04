package app.dita.app.trial;

import app.dita.core.model.GroupScope;

/**
 * TASK-054: are the projections the answer path reads caught up with the knowledge just stored? The production check is
 * the replay barrier's (TelegramConfiguration.pipelineIdle): POSTGRES = relay lag 0 for the neo4j and vespa consumers;
 * IN_MEMORY = dispatcher lag 0 for every consumer. Tests replace it to simulate a projection that never catches up.
 */
@FunctionalInterface
public interface TrialReadiness {
    boolean ready(GroupScope scope);
}
