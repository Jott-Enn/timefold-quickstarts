package org.acme.vehiclerouting.sightline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.solver.VehicleRoutingConstraintProvider;

/**
 * Rebuilds the result a finding shows from its sidecar alone, outside the UI and without Quarkus: deserializes the
 * captured route plan (with every shadow/derived field stripped), runs the solver's own scoring pipeline
 * (shadow variables + constraint score) and compares the result with what the page displayed.
 */
public final class FindingRebuild {

    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Report(VehicleRoutePlan plan, List<String> matches, List<String> mismatches) {
        public boolean ok() {
            return mismatches.isEmpty();
        }

        @Override
        public String toString() {
            return "REBUILD " + (ok() ? "MATCH" : "MISMATCH") + ": " + matches.size() + " checks matched, "
                    + mismatches.size() + " mismatched\n  matched: " + String.join("\n  matched: ", matches)
                    + (mismatches.isEmpty() ? "" : "\n  MISMATCH: " + String.join("\n  MISMATCH: ", mismatches));
        }
    }

    private FindingRebuild() {
    }

    public static JsonNode read(Path sidecar) throws IOException {
        return MAPPER.readTree(sidecar.toFile());
    }

    public static VehicleRoutePlan rebuild(JsonNode sidecar) throws IOException {
        JsonNode planNode = sidecar.path("state").path("routePlan");
        if (!planNode.isObject()) {
            throw new IllegalArgumentException("Sidecar has no state.routePlan; the capture misses the application state.");
        }
        ObjectNode stripped = FindingEnricher.stripComputed(((ObjectNode) planNode).deepCopy());
        VehicleRoutePlan plan = MAPPER.treeToValue(stripped, VehicleRoutePlan.class);
        SolverFactory<VehicleRoutePlan> factory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(VehicleRoutePlan.class)
                .withEntityClasses(Vehicle.class, Visit.class)
                .withConstraintProviderClass(VehicleRoutingConstraintProvider.class));
        SolutionManager<VehicleRoutePlan, HardMediumSoftScore> solutionManager = SolutionManager.create(factory);
        solutionManager.update(plan);
        return plan;
    }

    /** Same formatting as app.js formatScore(). */
    static String formatScore(HardMediumSoftScore score) {
        return score == null ? "?" : score.toString().replace("hard", "H").replace("medium", "M").replace("soft", "S");
    }

    /** Same formatting as app.js formatDrivingTime(). */
    static String formatDrivingTime(long seconds) {
        return (seconds / 3600) + "h " + Math.round((seconds % 3600) / 60.0) + "m";
    }

    public static Report compare(JsonNode sidecar, VehicleRoutePlan plan) {
        List<String> ok = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        JsonNode display = sidecar.path("display");
        BiCheck check = (what, shown, rebuilt) -> {
            if (String.valueOf(shown).equals(String.valueOf(rebuilt))) {
                ok.add(what + " = " + rebuilt);
            } else {
                bad.add(what + ": on screen " + shown + ", rebuilt " + rebuilt);
            }
        };
        check.apply("score", display.path("score").asText(), formatScore(plan.getScore()));
        check.apply("total driving time", display.path("drivingTime").asText(), formatDrivingTime(plan.getTotalDrivingTimeSeconds()));

        Map<String, Vehicle> vehicles = plan.getVehicles().stream().collect(Collectors.toMap(Vehicle::getId, v -> v));
        int rows = 0;
        for (JsonNode row : display.path("vehicles")) {
            Vehicle v = vehicles.get(row.path("id").asText());
            if (v == null) {
                bad.add("vehicle row " + row.path("id").asText() + " on screen has no vehicle in the rebuilt plan");
                continue;
            }
            rows++;
            String load = v.getTotalDemand() + "/" + v.getCapacity();
            String driving = formatDrivingTime(v.getTotalDrivingTimeSeconds());
            if (!load.equals(row.path("load").asText()) || !driving.equals(row.path("drivingTime").asText())) {
                bad.add("vehicle " + v.getId() + ": on screen " + row.path("load").asText() + " " + row.path("drivingTime").asText()
                        + ", rebuilt " + load + " " + driving);
            }
        }
        check.apply("vehicle rows (count)", display.path("vehicles").size(), vehicles.size());
        if (bad.stream().noneMatch(s -> s.startsWith("vehicle "))) {
            ok.add("all " + rows + " vehicle rows: load and driving time");
        }

        Map<String, Visit> visits = plan.getVisits().stream().collect(Collectors.toMap(Visit::getId, v -> v));
        int positions = 0;
        for (JsonNode m : display.path("visits")) {
            Visit v = visits.get(m.path("id").asText());
            if (v == null || !samePlace(m.path("latlng"), v.getLocation())) {
                bad.add("visit marker " + m.path("id").asText() + " at " + m.path("latlng") + " has no matching visit position");
            } else {
                positions++;
            }
        }
        check.apply("visit markers (count)", display.path("visits").size(), visits.size());
        if (positions == visits.size()) {
            ok.add("all " + positions + " visit marker positions");
        }

        // Route polylines: each must be exactly home -> visits in order -> home of one vehicle.
        Map<String, Integer> expected = new HashMap<>();
        for (Vehicle v : plan.getVehicles()) {
            expected.merge(routeKey(v), 1, Integer::sum);
        }
        int routes = 0;
        for (JsonNode r : display.path("routes")) {
            StringBuilder key = new StringBuilder();
            for (JsonNode ll : r.path("latlngs")) {
                key.append(round(ll.path(0).asDouble())).append(',').append(round(ll.path(1).asDouble())).append(';');
            }
            Integer n = expected.get(key.toString());
            if (n == null || n == 0) {
                bad.add("route polyline (" + r.path("colour").asText() + ", " + r.path("latlngs").size()
                        + " points) matches no rebuilt vehicle route");
            } else {
                expected.put(key.toString(), n - 1);
                routes++;
            }
        }
        if (routes == plan.getVehicles().size()) {
            ok.add("all " + routes + " route polylines: same stops in the same order");
        }

        // Objects resolved at filing time: the rebuilt numbers must agree.
        JsonNode objects = sidecar.path("objects");
        objects.fieldNames().forEachRemaining(id -> {
            JsonNode o = objects.get(id);
            if (id.startsWith("visit:")) {
                Visit v = visits.get(id.substring(6));
                if (v == null) {
                    bad.add(id + " no longer exists in the rebuilt plan");
                    return;
                }
                check.apply(id + " arrival", o.path("arrivalTime").asText(), String.valueOf(v.getArrivalTime()));
                check.apply(id + " vehicle", o.path("vehicle").asText(), v.getVehicle() == null ? "null" : v.getVehicle().getId());
            } else if (id.startsWith("vehicle:")) {
                Vehicle v = vehicles.get(id.substring(8));
                if (v != null) {
                    check.apply(id + " total demand", o.path("totalDemand").asText(), String.valueOf(v.getTotalDemand()));
                }
            }
        });
        if (objects.has("_recomputedScore")) {
            check.apply("score recomputed at filing", objects.path("_recomputedScore").asText(), String.valueOf(plan.getScore()));
        }
        return new Report(plan, ok, bad);
    }

    private static String routeKey(Vehicle v) {
        StringBuilder key = new StringBuilder();
        List<Location> stops = new ArrayList<>();
        stops.add(v.getHomeLocation());
        v.getVisits().forEach(visit -> stops.add(visit.getLocation()));
        stops.add(v.getHomeLocation());
        for (Location l : stops) {
            key.append(round(l.getLatitude())).append(',').append(round(l.getLongitude())).append(';');
        }
        return key.toString();
    }

    private static String round(double d) {
        return String.format(java.util.Locale.ROOT, "%.9f", d);
    }

    private static boolean samePlace(JsonNode latlng, Location l) {
        return Math.abs(latlng.path(0).asDouble() - l.getLatitude()) < 1e-9
                && Math.abs(latlng.path(1).asDouble() - l.getLongitude()) < 1e-9;
    }

    @FunctionalInterface
    private interface BiCheck {
        void apply(String what, Object shown, Object rebuilt);
    }
}
