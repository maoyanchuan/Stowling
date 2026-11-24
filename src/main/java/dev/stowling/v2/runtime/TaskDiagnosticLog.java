package dev.stowling.v2.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runtime-owned history survives browser refreshes and records events between monitor polls. */
public final class TaskDiagnosticLog {
    public static final int CAPACITY = 20000;
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile(
            "(?i)Bearer\\s+[^\\s;,]+"
    );
    private static final java.util.regex.Pattern CREDENTIAL = java.util.regex.Pattern.compile(
            "(?i)((?:token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s;,&]+"
    );
    private final String session = UUID.randomUUID().toString();
    private final Deque<Event> events = new ArrayDeque<>();
    private final Map<String, Repetition> repetitions = new LinkedHashMap<>();
    private final Map<UUID, TaskDiagnosticContext> contexts = new LinkedHashMap<>();
    private final Clock clock;
    private final int capacity;
    private long sequence;

    public TaskDiagnosticLog() {
        this(CAPACITY, Clock.systemUTC());
    }

    public TaskDiagnosticLog(int capacity, Clock clock) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public synchronized void captureContext(UUID runId, TaskDiagnosticContext context) {
        contexts.putIfAbsent(runId, context);
        while (contexts.size() > 50) contexts.remove(contexts.keySet().iterator().next());
    }

    public synchronized void append(UUID runId, String phase, String step, String module,
            String kind, String operation, String details, long elapsedMillis, boolean repeatable) {
        Instant now = clock.instant();
        details = CREDENTIAL.matcher(BEARER.matcher(details).replaceAll("Bearer [redacted]"))
                .replaceAll("$1[redacted]");
        if (details.length() > 4000) details = details.substring(0, 4000) + "…";
        String key = runId + ":" + phase + ":" + step + ":" + kind + ":" + operation;
        Repetition prior = repetitions.get(key);
        int repeated = prior == null ? 0 : prior.repeated;
        if (repeatable && prior != null && prior.details.equals(details)
                && now.toEpochMilli() - prior.time.toEpochMilli() < 1000) {
            prior.repeated++;
            return;
        }
        repetitions.put(key, new Repetition(details, now));
        if (repetitions.size() > capacity) {
            repetitions.remove(repetitions.keySet().iterator().next());
        }
        events.addLast(new Event(++sequence, now, runId, phase, step, module, kind, operation,
                details, Math.max(0, elapsedMillis), repeated));
        while (events.size() > capacity) events.removeFirst();
    }

    public synchronized Batch read(long after, int limit) {
        if (after < 0 || limit <= 0 || limit > CAPACITY) {
            throw new IllegalArgumentException("invalid diagnostic cursor or limit");
        }
        long first = events.isEmpty() ? sequence + 1 : events.getFirst().sequence();
        // A stale cursor from a previous runtime must not hide this runtime's records.
        long cursor = after > sequence ? 0 : after;
        List<Event> selected = events.stream().filter(event -> event.sequence() > cursor)
                .limit(limit).toList();
        long next = selected.isEmpty() ? sequence : selected.getLast().sequence();
        return new Batch(session, first, next, sequence, cursor < first - 1, selected);
    }

    public synchronized Batch readRun(UUID runId) {
        java.util.Objects.requireNonNull(runId, "runId");
        List<Event> selected = events.stream().filter(event -> runId.equals(event.runId())).toList();
        long first = selected.isEmpty() ? sequence + 1 : selected.getFirst().sequence();
        long last = selected.isEmpty() ? sequence : selected.getLast().sequence();
        boolean truncated = !events.isEmpty() && events.getFirst().sequence() > 1;
        return new Batch(session, first, last, last, truncated, selected, contexts.get(runId));
    }

    public record Event(long sequence, Instant time, UUID runId, String phase, String step,
            String module, String kind, String operation, String details, long elapsedMillis,
            int repeated) {
    }

    public record Batch(String session, long firstAvailable, long nextSequence, long latestSequence,
            boolean truncated, List<Event> events, TaskDiagnosticContext context) {
        public Batch(String session, long firstAvailable, long nextSequence, long latestSequence,
                boolean truncated, List<Event> events) {
            this(session, firstAvailable, nextSequence, latestSequence, truncated, events, null);
        }

        public Batch {
            events = List.copyOf(events);
        }
    }

    private static final class Repetition {
        private final String details;
        private final Instant time;
        private int repeated;

        private Repetition(String details, Instant time) {
            this.details = details;
            this.time = time;
        }
    }
}
