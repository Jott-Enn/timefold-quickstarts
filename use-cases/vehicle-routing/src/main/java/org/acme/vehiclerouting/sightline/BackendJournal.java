package org.acme.vehiclerouting.sightline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sightline backend journal: what the server did in the last minutes (requests, solver jobs, cache work,
 * warnings), so a filed finding can carry the backend's side of what was on screen.
 * <p>
 * Static and dependency-free so domain and solver code can call it without CDI. It is a no-op until
 * {@link #enable()} is called, which only the dev/test-profile {@code SightlineStartup} bean does;
 * in a production build nothing is recorded.
 */
public final class BackendJournal {

    public static final int MAX_EVENTS = 400;
    public static final int MAX_JOBS = 20;
    private static final int MAX_STRING = 300;

    private static volatile boolean enabled = false;

    private static final Deque<Map<String, Object>> events = new ArrayDeque<>();
    /** Per solver job: a summary plus per-visit placement provenance. Insertion ordered, capped. */
    private static final Map<String, JobRecord> jobs = new LinkedHashMap<>();

    private BackendJournal() {
    }

    public static void enable() {
        enabled = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Test hook. */
    public static synchronized void clear() {
        events.clear();
        jobs.clear();
    }

    public static void record(String type, Object... keyValues) {
        if (!enabled) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("t", System.currentTimeMillis());
        event.put("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            event.put(String.valueOf(keyValues[i]), sanitize(keyValues[i + 1]));
        }
        synchronized (BackendJournal.class) {
            events.addLast(event);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
    }

    static Object sanitize(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String s = String.valueOf(value).replaceAll("[\\r\\n\\t]+", " ");
        return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) + "...(truncated)" : s;
    }

    // ************************************************************************
    // Solver jobs
    // ************************************************************************

    public static void jobSubmitted(String jobId, int visitCount, int vehicleCount) {
        if (!enabled) {
            return;
        }
        synchronized (BackendJournal.class) {
            JobRecord job = new JobRecord(jobId, System.currentTimeMillis(), visitCount, vehicleCount);
            jobs.put(jobId, job);
            while (jobs.size() > MAX_JOBS) {
                jobs.remove(jobs.keySet().iterator().next());
            }
        }
        record("solver.submitted", "jobId", jobId, "visits", visitCount, "vehicles", vehicleCount);
    }

    /**
     * A new best solution. {@code assignment} maps visitId to "vehicleId#index" (or null when unassigned);
     * visits whose assignment changed since the previous best get this event as their placement provenance.
     */
    public static void bestSolution(String jobId, String eventKind, String producer, String score,
            Map<String, String> assignment) {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (BackendJournal.class) {
            JobRecord job = jobs.get(jobId);
            if (job == null) {
                return;
            }
            job.bestSolutionCount++;
            int eventNo = job.bestSolutionCount;
            job.lastScore = score;
            job.lastProducer = producer;
            job.lastEventAt = now;
            job.producerCounts.merge(producer, 1, Integer::sum);
            if ("firstInitialized".equals(eventKind)) {
                job.firstInitializedAt = now;
                job.firstInitializedScore = score;
            }
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("n", eventNo);
            h.put("ms", now - job.submittedAt);
            h.put("score", score);
            h.put("producer", producer);
            job.scoreHistory.add(h);
            if (job.scoreHistory.size() > 60) {
                job.scoreHistory.remove(1); // keep the first entry, drop the oldest after it
            }
            for (Map.Entry<String, String> e : assignment.entrySet()) {
                String previous = job.assignment.get(e.getKey());
                if (!java.util.Objects.equals(previous, e.getValue()) || !job.provenance.containsKey(e.getKey())) {
                    job.assignment.put(e.getKey(), e.getValue());
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("placedBy", producer);
                    p.put("bestSolutionEvent", eventNo);
                    p.put("msAfterSubmit", now - job.submittedAt);
                    p.put("assignment", e.getValue());
                    p.put("previousAssignment", previous);
                    job.provenance.put(e.getKey(), p);
                }
            }
        }
    }

    public static void jobFinished(String jobId, String score, String outcome) {
        if (!enabled) {
            return;
        }
        synchronized (BackendJournal.class) {
            JobRecord job = jobs.get(jobId);
            if (job != null) {
                job.finishedAt = System.currentTimeMillis();
                job.outcome = outcome;
                if (score != null) {
                    job.lastScore = score;
                }
            }
        }
        record("solver.finished", "jobId", jobId, "outcome", outcome, "score", score);
    }

    /** Placement provenance recorded outside a solver job (e.g. an applied recommendation). */
    public static void placedOutsideSolver(String jobId, String visitId, String how, String assignment) {
        if (!enabled) {
            return;
        }
        synchronized (BackendJournal.class) {
            JobRecord job = jobId == null ? null : jobs.get(jobId);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("placedBy", how);
            p.put("assignment", assignment);
            p.put("at", System.currentTimeMillis());
            if (job != null) {
                job.provenance.put(visitId, p);
                job.assignment.put(visitId, assignment);
            }
        }
        record("placement", "visitId", visitId, "placedBy", how, "assignment", assignment);
    }

    // ************************************************************************
    // Snapshots
    // ************************************************************************

    /** Events and job summaries from the last {@code windowMillis}, newest last, capped. */
    public static synchronized Map<String, Object> snapshot(long windowMillis, int maxEvents) {
        long since = System.currentTimeMillis() - windowMillis;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> e : events) {
            if (((Long) e.get("t")) >= since) {
                recent.add(e);
            }
        }
        boolean truncated = recent.size() > maxEvents;
        if (truncated) {
            recent = new ArrayList<>(recent.subList(recent.size() - maxEvents, recent.size()));
        }
        List<Map<String, Object>> jobList = new ArrayList<>();
        for (JobRecord job : jobs.values()) {
            long last = Math.max(job.submittedAt, Math.max(job.lastEventAt, job.finishedAt));
            if (last >= since || job.finishedAt == 0) {
                jobList.add(job.summary());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMinutes", windowMillis / 60000.0);
        result.put("enabled", enabled);
        result.put("jobs", jobList);
        result.put("events", recent);
        result.put("eventsTruncated", truncated);
        result.put("eventCap", maxEvents);
        return result;
    }

    public static synchronized Map<String, Object> provenance(String jobId, String visitId) {
        JobRecord job = jobId == null ? null : jobs.get(jobId);
        if (job == null) {
            return null;
        }
        Map<String, Object> p = job.provenance.get(visitId);
        return p == null ? null : new LinkedHashMap<>(p);
    }

    private static final class JobRecord {
        final String jobId;
        final long submittedAt;
        final int visitCount;
        final int vehicleCount;
        int bestSolutionCount;
        String lastScore;
        String lastProducer;
        long lastEventAt;
        long firstInitializedAt;
        String firstInitializedScore;
        long finishedAt;
        String outcome;
        final Map<String, Integer> producerCounts = new LinkedHashMap<>();
        final List<Map<String, Object>> scoreHistory = new ArrayList<>();
        final Map<String, String> assignment = new java.util.HashMap<>();
        final Map<String, Map<String, Object>> provenance = new LinkedHashMap<>();

        JobRecord(String jobId, long submittedAt, int visitCount, int vehicleCount) {
            this.jobId = jobId;
            this.submittedAt = submittedAt;
            this.visitCount = visitCount;
            this.vehicleCount = vehicleCount;
        }

        Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", jobId);
            m.put("submittedAt", submittedAt);
            m.put("visits", visitCount);
            m.put("vehicles", vehicleCount);
            m.put("bestSolutionEvents", bestSolutionCount);
            m.put("producerCounts", new LinkedHashMap<>(producerCounts));
            m.put("firstInitializedMs", firstInitializedAt == 0 ? null : firstInitializedAt - submittedAt);
            m.put("firstInitializedScore", firstInitializedScore);
            m.put("lastScore", lastScore);
            m.put("lastProducer", lastProducer);
            m.put("lastEventMs", lastEventAt == 0 ? null : lastEventAt - submittedAt);
            m.put("finishedMs", finishedAt == 0 ? null : finishedAt - submittedAt);
            m.put("outcome", outcome == null ? "running" : outcome);
            m.put("scoreHistory", new ArrayList<>(scoreHistory));
            return m;
        }
    }
}
