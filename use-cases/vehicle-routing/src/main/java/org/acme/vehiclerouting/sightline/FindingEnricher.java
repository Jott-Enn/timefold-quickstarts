package org.acme.vehiclerouting.sightline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolutionUpdatePolicy;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.geo.HaversineDrivingTimeCalculator;
import org.acme.vehiclerouting.rest.VehicleRouteDemoResource;

/**
 * Attaches what the page cannot see to a sidecar at filing time: build identity, solver inputs and seeds, the
 * backend journal, and for every object a mark resolved to, the data and pipeline stage that produced it.
 */
public final class FindingEnricher {

    static final long BACKEND_WINDOW_MILLIS = 10 * 60 * 1000L;
    static final int BACKEND_EVENT_CAP = 200;

    private final SolutionManager<VehicleRoutePlan, HardMediumSoftScore> solutionManager;
    private final SolverConfig solverConfig;
    private final ObjectMapper mapper;
    private final Path workingDir;

    public FindingEnricher(SolutionManager<VehicleRoutePlan, HardMediumSoftScore> solutionManager,
            SolverConfig solverConfig, ObjectMapper mapper, Path workingDir) {
        this.solutionManager = solutionManager;
        this.solverConfig = solverConfig;
        this.mapper = mapper;
        this.workingDir = workingDir;
    }

    public void enrich(ObjectNode sidecar) {
        sidecar.set("build", mapper.valueToTree(build()));
        sidecar.set("inputs", mapper.valueToTree(inputs(sidecar.path("state"))));
        sidecar.set("backend", mapper.valueToTree(BackendJournal.snapshot(BACKEND_WINDOW_MILLIS, BACKEND_EVENT_CAP)));
        try {
            sidecar.set("objects", mapper.valueToTree(objects(sidecar)));
        } catch (RuntimeException e) {
            sidecar.put("objectsError", "Could not resolve object data from the state: " + e);
        }
    }

    // ************************************************************************
    // Build identity
    // ************************************************************************

    Map<String, Object> build() {
        Map<String, Object> build = new LinkedHashMap<>();
        String commit = git("rev-parse", "HEAD");
        String status = git("status", "--porcelain", "--untracked-files=no");
        build.put("commit", commit == null ? null : commit.strip());
        build.put("branch", stripOrNull(git("rev-parse", "--abbrev-ref", "HEAD")));
        build.put("dirty", status == null ? null : !status.isBlank());
        if (status != null && !status.isBlank()) {
            List<String> files = status.lines().limit(50).map(String::strip).toList();
            build.put("dirtyFiles", files);
        }
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("java", System.getProperty("java.version"));
        versions.put("timefoldSolver", implementationVersion(SolutionManager.class));
        versions.put("quarkus", implementationVersion(io.quarkus.runtime.Application.class));
        versions.put("jackson", implementationVersion(ObjectMapper.class));
        build.put("versions", versions);
        Map<String, Object> caches = new LinkedHashMap<>();
        caches.put("drivingTimeMatrix", Map.of(
                "calculator", HaversineDrivingTimeCalculator.class.getSimpleName(),
                "averageSpeedKmph", HaversineDrivingTimeCalculator.AVERAGE_SPEED_KMPH,
                "lifecycle", "recomputed from scratch for every deserialized VehicleRoutePlan; no persistent cache"));
        caches.put("persistentStores", "none: solver jobs live in an in-memory map of VehicleRoutePlanResource");
        build.put("caches", caches);
        return build;
    }

    private static String stripOrNull(String s) {
        return s == null ? null : s.strip();
    }

    private static String implementationVersion(Class<?> c) {
        Package p = c.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        if (v != null) {
            return v;
        }
        try {
            var location = c.getProtectionDomain().getCodeSource().getLocation().getPath();
            String file = location.substring(location.lastIndexOf('/') + 1);
            return file.isEmpty() ? null : file;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String git(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(cmd).directory(workingDir.toFile()).redirectErrorStream(false).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().reduce("", (a, b) -> a + b + "\n");
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ************************************************************************
    // Inputs, parameters and seeds
    // ************************************************************************

    Map<String, Object> inputs(JsonNode state) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        String demoDataId = state.path("demoDataId").asText(null);
        if (demoDataId != null) {
            try {
                VehicleRouteDemoResource.DemoData demo = VehicleRouteDemoResource.DemoData.valueOf(demoDataId);
                inputs.put("demoData", Map.of("id", demo.name(), "seed", demo.getSeed(),
                        "note", "generator seed; dates are relative to the generation day (tomorrow), so the state's "
                                + "absolute times, not the seed, are the reproducible input"));
            } catch (IllegalArgumentException e) {
                inputs.put("demoData", Map.of("id", demoDataId, "error", "unknown demo data id"));
            }
        }
        Map<String, Object> solver = new LinkedHashMap<>();
        // The effective settings come from application.properties (applied by the Quarkus extension at runtime),
        // the injected SolverConfig only holds what was set at build time.
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        for (String key : List.of("quarkus.timefold.solver.termination.spent-limit",
                "quarkus.timefold.solver.termination.unimproved-spent-limit",
                "quarkus.timefold.solver.termination.best-score-limit",
                "quarkus.timefold.solver.environment-mode", "quarkus.timefold.solver.move-thread-count",
                "quarkus.timefold.solver.random-seed", "quarkus.timefold.solver-manager.parallel-solver-count")) {
            solver.put(key.substring("quarkus.timefold.".length()), config.getOptionalValue(key, String.class).orElse("(default)"));
        }
        if (solverConfig != null) {
            solver.put("buildTimeEnvironmentMode", String.valueOf(solverConfig.getEnvironmentMode()));
            solver.put("randomSeed", solverConfig.getRandomSeed() == null
                    ? "not set: Timefold's fixed default seed is used (reproducible environment modes)" : solverConfig.getRandomSeed());
        }
        solver.put("constraintProvider", "org.acme.vehiclerouting.solver.VehicleRoutingConstraintProvider");
        solver.put("reproducibility", "seeded, but terminated by wall-clock time or by the user, so re-running "
                + "the solver does not necessarily reach the same assignment; the state holds the assignment itself");
        inputs.put("solver", solver);
        return inputs;
    }

    // ************************************************************************
    // Objects under the marks
    // ************************************************************************

    Map<String, Object> objects(ObjectNode sidecar) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode mark : sidecar.path("marks")) {
            for (JsonNode o : mark.path("objects")) {
                ids.add(o.path("id").asText());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        JsonNode planNode = sidecar.path("state").path("routePlan");
        if (!planNode.isObject()) {
            return result;
        }
        VehicleRoutePlan plan;
        try {
            plan = mapper.treeToValue(stripComputed((ObjectNode) planNode.deepCopy()), VehicleRoutePlan.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Score analysis (constraint matches) is a commercial feature in this Timefold edition, so recompute shadows
        // and score with update() and report each object's impact via the same domain methods the constraints use.
        HardMediumSoftScore score = solutionManager.update(plan, SolutionUpdatePolicy.UPDATE_ALL);
        String jobId = sidecar.path("state").path("scheduleId").asText(null);
        for (String id : ids) {
            Map<String, Object> data = describe(plan, id, jobId);
            if (data == null) {
                continue;
            }
            data.put("constraintImpacts", impacts(plan, id));
            result.put(id, data);
        }
        result.put("_recomputedScore", String.valueOf(score));
        return result;
    }

    /** Per-object impact, computed with the domain methods VehicleRoutingConstraintProvider penalizes on. */
    private static List<Map<String, Object>> impacts(VehicleRoutePlan plan, String id) {
        String[] parts = id.split(":");
        List<Map<String, Object>> list = new ArrayList<>();
        if (parts[0].equals("visit")) {
            plan.getVisits().stream().filter(v -> v.getId().equals(parts[1])).findFirst().ifPresent(v -> {
                if (v.getVehicle() == null) {
                    list.add(Map.of("constraint", "Maximize visits assigned", "score",
                            "-" + v.getServiceDuration().toMinutes() + "medium"));
                } else if (v.isServiceFinishedAfterMaxEndTime()) {
                    list.add(Map.of("constraint", "Service finished after max end time", "score",
                            "-" + v.getServiceFinishedDelayInMinutes() + "hard"));
                }
            });
        } else if (parts[0].equals("vehicle") || parts[0].equals("leg")) {
            plan.getVehicles().stream().filter(v -> v.getId().equals(parts[1])).findFirst().ifPresent(v -> {
                if (v.getTotalDemand() > v.getCapacity()) {
                    list.add(Map.of("constraint", "Vehicle capacity", "score",
                            "-" + (v.getTotalDemand() - v.getCapacity()) + "hard"));
                }
                list.add(Map.of("constraint", "Minimize travel time", "score",
                        "-" + v.getTotalDrivingTimeSeconds() + "soft", "scope", "whole vehicle route"));
            });
        }
        return list;
    }

    /** Removes shadow/derived fields so they are recomputed by the solver's own pipeline. */
    public static ObjectNode stripComputed(ObjectNode plan) {
        plan.remove(List.of("score", "solverStatus", "totalDrivingTimeSeconds"));
        for (JsonNode v : plan.path("vehicles")) {
            ((ObjectNode) v).remove(List.of("totalDemand", "totalDrivingTimeSeconds", "arrivalTime"));
        }
        for (JsonNode v : plan.path("visits")) {
            ((ObjectNode) v).remove(List.of("vehicle", "previousVisit", "arrivalTime", "departureTime",
                    "startServiceTime", "drivingTimeSecondsFromPreviousStandstill"));
        }
        return plan;
    }

    private Map<String, Object> describe(VehicleRoutePlan plan, String id, String jobId) {
        String[] parts = id.split(":");
        Map<String, Object> d = new LinkedHashMap<>();
        switch (parts[0]) {
            case "visit" -> {
                Visit visit = plan.getVisits().stream().filter(v -> v.getId().equals(parts[1])).findFirst().orElse(null);
                if (visit == null) {
                    return null;
                }
                d.put("type", "visit");
                d.put("name", visit.getName());
                d.put("location", List.of(visit.getLocation().getLatitude(), visit.getLocation().getLongitude()));
                d.put("demand", visit.getDemand());
                d.put("timeWindow", List.of(String.valueOf(visit.getMinStartTime()), String.valueOf(visit.getMaxEndTime())));
                d.put("serviceMinutes", visit.getServiceDuration().toMinutes());
                Vehicle vehicle = visit.getVehicle();
                d.put("vehicle", vehicle == null ? null : vehicle.getId());
                d.put("routeIndex", vehicle == null ? null : vehicle.getVisits().indexOf(visit));
                d.put("previousVisit", visit.getPreviousVisit() == null ? null : visit.getPreviousVisit().getId());
                d.put("arrivalTime", String.valueOf(visit.getArrivalTime()));
                d.put("startServiceTime", String.valueOf(visit.getStartServiceTime()));
                d.put("departureTime", String.valueOf(visit.getDepartureTime()));
                d.put("drivingSecondsFromPrevious", visit.getDrivingTimeSecondsFromPreviousStandstillOrNull());
                d.put("lateMinutes", visit.isServiceFinishedAfterMaxEndTime() ? visit.getServiceFinishedDelayInMinutes() : 0);
                d.put("placedBy", placement(jobId, visit.getId()));
            }
            case "vehicle" -> {
                Vehicle vehicle = plan.getVehicles().stream().filter(v -> v.getId().equals(parts[1])).findFirst().orElse(null);
                if (vehicle == null) {
                    return null;
                }
                d.put("type", "vehicle");
                d.put("capacity", vehicle.getCapacity());
                d.put("totalDemand", vehicle.getTotalDemand());
                d.put("home", List.of(vehicle.getHomeLocation().getLatitude(), vehicle.getHomeLocation().getLongitude()));
                d.put("departureTime", String.valueOf(vehicle.getDepartureTime()));
                d.put("arrivalTime", String.valueOf(vehicle.arrivalTime()));
                d.put("totalDrivingTimeSeconds", vehicle.getTotalDrivingTimeSeconds());
                d.put("visits", vehicle.getVisits().stream().map(Visit::getId).toList());
            }
            case "leg" -> {
                if (parts.length < 3) {
                    return null;
                }
                Vehicle vehicle = plan.getVehicles().stream().filter(v -> v.getId().equals(parts[1])).findFirst().orElse(null);
                if (vehicle == null) {
                    return null;
                }
                int i = Integer.parseInt(parts[2]);
                List<Visit> visits = vehicle.getVisits();
                if (i < 0 || i > visits.size()) {
                    return null;
                }
                String from = i == 0 ? "home:" + vehicle.getId() : "visit:" + visits.get(i - 1).getId();
                String to = i == visits.size() ? "home:" + vehicle.getId() : "visit:" + visits.get(i).getId();
                var fromLoc = i == 0 ? vehicle.getHomeLocation() : visits.get(i - 1).getLocation();
                var toLoc = i == visits.size() ? vehicle.getHomeLocation() : visits.get(i).getLocation();
                d.put("type", "route-leg");
                d.put("vehicle", vehicle.getId());
                d.put("legIndex", i);
                d.put("from", from);
                d.put("to", to);
                d.put("drivingSeconds", fromLoc.getDrivingTimeTo(toLoc));
                if (i < visits.size()) {
                    d.put("placedBy", placement(jobId, visits.get(i).getId()));
                }
            }
            default -> {
                d.put("type", parts[0]);
            }
        }
        return d;
    }

    private Object placement(String jobId, String visitId) {
        Map<String, Object> p = BackendJournal.provenance(jobId, visitId);
        if (p != null) {
            p.put("jobId", jobId);
            return p;
        }
        return "unknown: no solver job in this server's journal placed it (state loaded from demo data, an older "
                + "server run, or an applied recommendation outside a job)";
    }
}
