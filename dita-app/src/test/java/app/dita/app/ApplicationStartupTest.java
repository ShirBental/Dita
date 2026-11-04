package app.dita.app;

import app.dita.core.QueryService;
import app.dita.core.IngestionPipeline;
import app.dita.core.events.EventDispatcher;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring Boot startup of DitaApplication with the committed application.yml; in-process vertical slice on
 * synthetic data. (SpringApplication is used directly because @SpringBootTest's Mockito agent cannot self-attach here.)
 */
class ApplicationStartupTest {
    @Test void startsAndAnswersFromSyntheticMemory() {
      try (ConfigurableApplicationContext ctx = SpringApplication.run(DitaApplication.class)) {
        IngestionPipeline ingestion = ctx.getBean(IngestionPipeline.class);
        QueryService queries = ctx.getBean(QueryService.class);
        org.junit.jupiter.api.Assertions.assertFalse(queries.supportingMessagesEnabled(),
                "TASK-045 (DEC-034, N-90/N-102): production answers carry no 'Related message' lines");
        EventDispatcher dispatcher = ctx.getBean(EventDispatcher.class);
        GroupScope g = new GroupScope("g-synthetic");
        Instant t = Instant.parse("2026-11-01T10:00:00Z");
        ingestion.ingest(g, new Message("s1", "g-synthetic", "dana", t, null, "Does anyone know a good pediatrician?", SourceKind.SYNTHETIC, false));
        ingestion.ingest(g, new Message("s2", "g-synthetic", "yossi", t.plusSeconds(60), "s1", "Dr. Levi on Herzl St. is excellent", SourceKind.SYNTHETIC, false));
        dispatcher.drain();
        var a = queries.ask(g, "dana", "pediatrician Dr Levi");
        assertThat(a.found()).isTrue();
        assertThat(a.citations()).extracting(c -> c.messageId()).contains("s2");
        assertThat(dispatcher.retainedEnvelopes()).isZero();
      }
    }
}
