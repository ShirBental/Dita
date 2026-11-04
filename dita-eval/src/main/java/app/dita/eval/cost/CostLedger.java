package app.dita.eval.cost;

import app.dita.eval.Json;
import app.dita.eval.cost.UsageRecord.CallKind;
import app.dita.eval.guard.BudgetGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * N-56: one experiment-wide, append-only cost ledger covering answers, extraction, connectivity/warm-up and retries.
 * <ul>
 *   <li>Every call is reserved at its worst case (full {@code max_completion_tokens}) through a {@link BudgetGuard}
 *       BEFORE it is sent. A reservation is never refunded, and a call id can be reserved only once (nothing is re-sent).</li>
 *   <li>Actual cost (from usage) is recorded next to the reservation, never in its place (N-57). Actual sums are kept
 *       per arm and per call kind, separately for each {@link Provenance}: MEASURED, REPLAYED and SIMULATED money is never
 *       added together.</li>
 *   <li>{@link #recordFailure} halts the ledger: no further reservation is accepted, even after reopening (the run
 *       stops on the first error).</li>
 *   <li>Disk mode: every event is appended as one JSON line and fsynced before the method returns, with a SHA-256 hash
 *       chain ({@code prev} = hash of the previous line). Reopening replays the file, so the committed total survives a
 *       restart and cannot be reset. There is no reset method. Reopening with a different experiment id, ceiling or
 *       price table is refused. A broken chain or an edited amount fails the open. Truncating the file's tail is only
 *       detectable against an externally recorded {@link #headHash()} (pass it as {@code expectedHead}).</li>
 * </ul>
 * Procedural control, not a security boundary: anyone with write access can delete the file.
 */
public final class CostLedger implements AutoCloseable {
    public static final String FORMAT = "dita-eval/cost-ledger-v1";
    public enum EventType { HEADER, RESERVE, ACTUAL, FAIL }
    public enum FailureCode { HTTP_ERROR, TRANSPORT, PARSE, GUARD, TRUNCATED, OTHER }

    static final Pattern CALL_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    static final Pattern EXPERIMENT = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final String GENESIS = "0".repeat(64);

    /** One ledger event. {@code usd} is the worst case for RESERVE, the actual cost for ACTUAL, null otherwise. */
    public record Entry(long seq, EventType type, String callId, CallKind callKind, String arm, BigDecimal usd,
                        Provenance provenance, Long inputTokens, Integer maxCompletionTokens, boolean reserved) {}

    public record Reconciliation(BigDecimal ceilingUsd, BigDecimal reservedUsd, int callsReserved,
                                 Map<Provenance, BigDecimal> actualUsdByProvenance, int callsWithActual,
                                 int reservedWithoutActual, BigDecimal reservedWithoutActualUsd,
                                 List<String> overrunCallIds, int unreservedActuals, boolean halted) {
        /** True iff every reserved call's actual cost is within its reservation. */
        public boolean withinReservations() { return overrunCallIds.isEmpty(); }
    }

    private final String experiment;
    private final BigDecimal ceilingUsd;
    private final PriceTable prices;
    private final BudgetGuard guard;
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> reservations = new LinkedHashMap<>();
    private final Map<String, Entry> actuals = new LinkedHashMap<>();
    private String head = GENESIS;
    private boolean halted;
    private long seq;

    private CostLedger(String experiment, BigDecimal ceilingUsd, PriceTable prices, Path file, FileChannel ch, FileLock lock) {
        if (!EXPERIMENT.matcher(experiment).matches()) throw new IllegalArgumentException("experiment id must match " + EXPERIMENT);
        this.experiment = experiment;
        this.ceilingUsd = Objects.requireNonNull(ceilingUsd);
        this.prices = Objects.requireNonNull(prices);
        this.guard = new BudgetGuard(ceilingUsd, prices);
        this.file = file;
        this.channel = ch;
        this.lock = lock;
    }

    public static CostLedger inMemory(String experiment, BigDecimal ceilingUsd, PriceTable prices) {
        CostLedger l = new CostLedger(experiment, ceilingUsd, prices, null, null, null);
        l.appendHeader();
        return l;
    }

    public static CostLedger openOrCreate(Path file, String experiment, BigDecimal ceilingUsd, PriceTable prices) throws IOException {
        return openOrCreate(file, experiment, ceilingUsd, prices, null);
    }

    /**
     * Opens the ledger file, replaying and verifying it, or creates it with a header. Holds an exclusive file lock until
     * {@link #close()}, so two ledger instances cannot append to the same file at once.
     */
    public static CostLedger openOrCreate(Path file, String experiment, BigDecimal ceilingUsd, PriceTable prices,
                                          String expectedHead) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("ledger file must not be a symlink");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock lk;
        try {
            lk = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            ch.close();
            throw new IllegalStateException("ledger already open in this process");
        }
        if (lk == null) { ch.close(); throw new IllegalStateException("ledger locked by another process"); }
        CostLedger l = new CostLedger(experiment, ceilingUsd, prices, file, ch, lk);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isEmpty()) l.appendHeader();
            else l.replay(text, expectedHead);
            ch.position(ch.size());
            return l;
        } catch (RuntimeException | IOException e) {
            l.close();
            throw e;
        }
    }

    // ---------------------------------------------------------------- writing

    /** Charges the call's worst case before it is sent. Throws if halted, if the id was already used, or if over budget. */
    public synchronized BudgetGuard.Reservation reserve(String callId, CallKind kind, String arm, long inputTokens, int maxCompletionTokens) {
        checkOpen();
        if (halted) throw new IllegalStateException("ledger halted after a failure: no further calls");
        checkCallId(callId);
        Objects.requireNonNull(kind);
        if (arm != null && !UsageRecord.ARMS.contains(arm)) throw new IllegalArgumentException("arm not allowlisted");
        if (reservations.containsKey(callId)) throw new IllegalStateException("call id already reserved (nothing is re-sent): " + callId);
        BudgetGuard.Reservation r = guard.reserve(inputTokens, maxCompletionTokens);   // throws BudgetExceededException
        Entry e = new Entry(++seq, EventType.RESERVE, callId, kind, arm, r.worstCaseUsd(), null, inputTokens, maxCompletionTokens, true);
        append(e, null);
        reservations.put(callId, e);
        return r;
    }

    /** Records the actual usage of a reserved call. Kind and arm must match the reservation. */
    public synchronized BigDecimal recordActual(String callId, UsageRecord usage) {
        checkOpen();
        checkCallId(callId);
        Entry res = reservations.get(callId);
        if (res == null) throw new IllegalStateException("no reservation for call id " + callId + " (reserve before sending)");
        if (actuals.containsKey(callId)) throw new IllegalStateException("actual already recorded for " + callId);
        if (res.callKind() != usage.callKind() || !Objects.equals(res.arm(), usage.arm()))
            throw new IllegalArgumentException("usage kind/arm do not match the reservation for " + callId);
        if (usage.maxCompletionTokens() != res.maxCompletionTokens())
            throw new IllegalArgumentException("usage max_completion_tokens differs from the reservation for " + callId);
        BigDecimal usd = prices.actualUsd(usage);
        Entry e = new Entry(++seq, EventType.ACTUAL, callId, usage.callKind(), usage.arm(), usd, usage.provenance(),
                usage.promptTokens(), usage.maxCompletionTokens(), true);
        append(e, usage);
        actuals.put(callId, e);
        return usd;
    }

    /**
     * Imports a call that happened before this ledger existed (for example the MEASURED live runs 2–5). It has no
     * reservation and is reported as unreserved in {@link #reconcile()}; it does not consume the budget ceiling.
     */
    public synchronized BigDecimal importHistorical(String callId, UsageRecord usage) {
        checkOpen();
        checkCallId(callId);
        if (reservations.containsKey(callId) || actuals.containsKey(callId)) throw new IllegalStateException("call id already used: " + callId);
        BigDecimal usd = prices.actualUsd(usage);
        Entry e = new Entry(++seq, EventType.ACTUAL, callId, usage.callKind(), usage.arm(), usd, usage.provenance(),
                usage.promptTokens(), usage.maxCompletionTokens(), false);
        append(e, usage);
        actuals.put(callId, e);
        return usd;
    }

    /** Halts the ledger permanently (persisted). */
    public synchronized void recordFailure(String callId, FailureCode code) {
        checkOpen();
        checkCallId(callId);
        Entry e = new Entry(++seq, EventType.FAIL, callId, null, null, null, null, null, null, reservations.containsKey(callId));
        ObjectNode extra = Json.object();
        extra.put("failure", code.name());
        append(e, null, extra);
        halted = true;
    }

    // ---------------------------------------------------------------- reading

    public synchronized BigDecimal committedUsd() { return guard.committedUsd(); }
    public synchronized BigDecimal remainingUsd() { return guard.remainingUsd(); }
    public synchronized int callsReserved() { return guard.callsReserved(); }
    public synchronized boolean halted() { return halted; }
    public synchronized String headHash() { return head; }
    public synchronized List<Entry> entries() { return List.copyOf(entries); }
    public BigDecimal ceilingUsd() { return ceilingUsd; }
    public PriceTable prices() { return prices; }
    public String experiment() { return experiment; }

    /** Actual USD per arm (null arm = "(none)"), kept separate per provenance. */
    public synchronized Map<Provenance, Map<String, BigDecimal>> actualByArm() {
        Map<Provenance, Map<String, BigDecimal>> out = new EnumMap<>(Provenance.class);
        for (Entry e : actuals.values())
            out.computeIfAbsent(e.provenance(), k -> new TreeMap<>()).merge(e.arm() == null ? "(none)" : e.arm(), e.usd(), BigDecimal::add);
        return Collections.unmodifiableMap(out);
    }

    /** Actual USD per call kind, kept separate per provenance. */
    public synchronized Map<Provenance, Map<CallKind, BigDecimal>> actualByCallKind() {
        Map<Provenance, Map<CallKind, BigDecimal>> out = new EnumMap<>(Provenance.class);
        for (Entry e : actuals.values())
            out.computeIfAbsent(e.provenance(), k -> new EnumMap<>(CallKind.class)).merge(e.callKind(), e.usd(), BigDecimal::add);
        return Collections.unmodifiableMap(out);
    }

    public synchronized BigDecimal actualTotal(Provenance p) {
        return actuals.values().stream().filter(e -> e.provenance() == p).map(Entry::usd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Worst-case reservations versus actuals. A reserved call without an actual is presumed charged at its worst case. */
    public synchronized Reconciliation reconcile() {
        Map<Provenance, BigDecimal> byProv = new EnumMap<>(Provenance.class);
        for (Entry e : actuals.values()) byProv.merge(e.provenance(), e.usd(), BigDecimal::add);
        List<String> overruns = new ArrayList<>();
        int without = 0, unreserved = 0, withActual = 0;
        BigDecimal withoutUsd = BigDecimal.ZERO;
        for (Entry r : reservations.values()) {
            Entry a = actuals.get(r.callId());
            if (a == null) { without++; withoutUsd = withoutUsd.add(r.usd()); continue; }
            withActual++;
            if (a.usd().compareTo(r.usd()) > 0) overruns.add(r.callId());
        }
        for (Entry a : actuals.values()) if (!a.reserved()) unreserved++;
        return new Reconciliation(ceilingUsd, guard.committedUsd(), guard.callsReserved(), Collections.unmodifiableMap(byProv),
                withActual, without, withoutUsd, List.copyOf(overruns), unreserved, halted);
    }

    @Override public synchronized void close() throws IOException {
        try { if (lock != null && lock.isValid()) lock.release(); }
        finally { if (channel != null && channel.isOpen()) channel.close(); }
    }

    // ---------------------------------------------------------------- internals

    private void checkOpen() { if (channel != null && !channel.isOpen()) throw new IllegalStateException("ledger closed"); }
    private static void checkCallId(String id) { if (id == null || !CALL_ID.matcher(id).matches()) throw new IllegalArgumentException("call id must match " + CALL_ID); }

    private void appendHeader() {
        ObjectNode h = Json.object();
        h.put("format", FORMAT);
        h.put("experiment", experiment);
        h.put("ceiling_usd", ceilingUsd.toPlainString());
        h.put("price_in_per_million", prices.inPerMillion().toPlainString());
        h.put("price_out_per_million", prices.outPerMillion().toPlainString());
        if (prices.cachedInPerMillion() == null) h.putNull("price_cached_in_per_million");
        else h.put("price_cached_in_per_million", prices.cachedInPerMillion().toPlainString());
        h.put("price_source", prices.source());
        writeLine(0, "HEADER", h);
    }

    private void append(Entry e, UsageRecord u) { append(e, u, null); }

    private void append(Entry e, UsageRecord u, ObjectNode extra) {
        ObjectNode o = Json.object();
        o.put("call_id", e.callId());
        if (e.callKind() != null) o.put("call_kind", e.callKind().name());
        if (e.arm() != null) o.put("arm", e.arm());
        if (e.usd() != null) o.put("usd", e.usd().toPlainString());
        if (e.provenance() != null) o.put("provenance", e.provenance().name());
        if (e.type() == EventType.RESERVE) {
            o.put("input_tokens", e.inputTokens());
            o.put("max_completion_tokens", e.maxCompletionTokens());
        }
        if (e.type() == EventType.ACTUAL) {
            o.put("reserved", e.reserved());
            o.set("usage", UsageJsonl.toJson(u));
        }
        if (extra != null) o.setAll(extra);
        writeLine(e.seq(), e.type().name(), o);
        entries.add(e);
    }

    /** Builds {seq, type, prev, ...payload}, writes it durably (disk mode) and advances the chain head. */
    private void writeLine(long s, String type, ObjectNode payload) {
        ObjectNode line = Json.object();
        line.put("seq", s);
        line.put("type", type);
        line.put("prev", head);
        line.setAll(payload);
        String text = Json.compact(line);
        if (channel != null) {
            try {
                ByteBuffer buf = ByteBuffer.wrap((text + "\n").getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) channel.write(buf);
                channel.force(true);
            } catch (IOException ex) {
                halted = true;
                throw new IllegalStateException("ledger write failed; ledger halted (do not send the call)", ex);
            }
        }
        head = sha256(text);
    }

    private void replay(String text, String expectedHead) {
        if (!text.endsWith("\n")) throw new IllegalStateException("ledger file has a partial last line");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        String prev = GENESIS;
        for (int i = 0; i < lines.length; i++) {
            JsonNode n;
            try { n = Json.parse(lines[i]); } catch (IllegalArgumentException e) { throw corrupt(i, "not JSON"); }
            if (!prev.equals(n.path("prev").asText())) throw corrupt(i, "hash chain broken");
            if (n.path("seq").asLong(-1) != i) throw corrupt(i, "sequence gap");
            String type = n.path("type").asText();
            if (i == 0) {
                if (!type.equals("HEADER") || !FORMAT.equals(n.path("format").asText())) throw corrupt(i, "missing header");
                checkHeader(n);
            } else {
                replayEvent(i, EventType.valueOf(type), n);
            }
            prev = sha256(lines[i]);
        }
        head = prev;
        seq = lines.length - 1;
        if (expectedHead != null && !expectedHead.equals(head)) throw new IllegalStateException("ledger head does not match the recorded head (truncated or replaced file)");
    }

    private void checkHeader(JsonNode h) {
        if (!experiment.equals(h.path("experiment").asText())) throw new IllegalStateException("ledger belongs to another experiment");
        if (new BigDecimal(h.path("ceiling_usd").asText()).compareTo(ceilingUsd) != 0)
            throw new IllegalStateException("ledger ceiling differs from the requested ceiling (cannot be changed mid-experiment)");
        boolean pricesMatch = new BigDecimal(h.path("price_in_per_million").asText()).compareTo(prices.inPerMillion()) == 0
                && new BigDecimal(h.path("price_out_per_million").asText()).compareTo(prices.outPerMillion()) == 0
                && (h.path("price_cached_in_per_million").isNull() ? prices.cachedInPerMillion() == null
                    : prices.cachedInPerMillion() != null && new BigDecimal(h.path("price_cached_in_per_million").asText()).compareTo(prices.cachedInPerMillion()) == 0);
        if (!pricesMatch) throw new IllegalStateException("ledger price table differs (cannot be changed mid-experiment)");
    }

    private void replayEvent(int i, EventType type, JsonNode n) {
        String callId = n.path("call_id").asText();
        checkCallId(callId);
        switch (type) {
            case RESERVE -> {
                CallKind kind = CallKind.valueOf(n.path("call_kind").asText());
                String arm = n.hasNonNull("arm") ? n.get("arm").asText() : null;
                long in = n.path("input_tokens").asLong();
                int max = n.path("max_completion_tokens").asInt();
                if (reservations.containsKey(callId)) throw corrupt(i, "duplicate reservation");
                BudgetGuard.Reservation r = guard.reserve(in, max);
                if (r.worstCaseUsd().compareTo(new BigDecimal(n.path("usd").asText())) != 0) throw corrupt(i, "reservation amount does not match its tokens");
                Entry e = new Entry(i, type, callId, kind, arm, r.worstCaseUsd(), null, in, max, true);
                reservations.put(callId, e);
                entries.add(e);
            }
            case ACTUAL -> {
                UsageRecord u = UsageJsonl.parse(Json.compact(n.get("usage"))).get(0);
                boolean reserved = n.path("reserved").asBoolean();
                if (reserved != reservations.containsKey(callId)) throw corrupt(i, "actual/reservation mismatch");
                if (actuals.containsKey(callId)) throw corrupt(i, "duplicate actual");
                BigDecimal usd = prices.actualUsd(u);
                if (usd.compareTo(new BigDecimal(n.path("usd").asText())) != 0) throw corrupt(i, "actual amount does not match its usage");
                Entry e = new Entry(i, type, callId, u.callKind(), u.arm(), usd, u.provenance(), u.promptTokens(), u.maxCompletionTokens(), reserved);
                actuals.put(callId, e);
                entries.add(e);
            }
            case FAIL -> {
                halted = true;
                entries.add(new Entry(i, type, callId, null, null, null, null, null, null, reservations.containsKey(callId)));
            }
            case HEADER -> throw corrupt(i, "second header");
        }
    }

    private static IllegalStateException corrupt(int line, String what) {
        return new IllegalStateException("ledger file corrupt at line " + (line + 1) + ": " + what);
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    Path file() { return file; }
}
