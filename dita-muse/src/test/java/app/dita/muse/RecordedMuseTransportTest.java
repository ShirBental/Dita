package app.dita.muse;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-051: recorded replies are matched by request content, used once, and a miss fails closed (no network). */
class RecordedMuseTransportTest {
    final URI url = URI.create("https://api.meta.ai/v1/chat/completions");

    @Test void servesTheMatchingRecordingOnceThenFailsClosed() throws Exception {
        var t = new RecordedMuseTransport(List.of(new RecordedMuseTransport.Recording(List.of("tgr1m1"), "{\"items\":[]}")));
        var r = t.post(url, Map.of(), "{\"messages\":\"... tgr1m1 ...\"}", Duration.ofSeconds(1));
        assertEquals(200, r.status());
        assertTrue(r.body().contains("\\\"items\\\""), r.body());
        var e = assertThrows(MuseException.class, () -> t.post(url, Map.of(), "{\"messages\":\"... tgr1m1 ...\"}", Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("no network call"));
        assertThrows(MuseException.class, () -> t.post(url, Map.of(), "unrelated", Duration.ofSeconds(1)));
        assertEquals(3, t.requests().size());
    }
}
