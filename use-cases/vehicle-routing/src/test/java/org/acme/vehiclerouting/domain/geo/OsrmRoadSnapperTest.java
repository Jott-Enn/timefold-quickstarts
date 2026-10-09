package org.acme.vehiclerouting.domain.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.acme.vehiclerouting.domain.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Snapping against a local stub that replays answers recorded from https://router.project-osrm.org on 2026-10-09.
 * <p>
 * Regression for finding-20261009-155741 (mark 1, visit:16 "Gus Jones"): the PHILADELPHIA demo put the visit in
 * the forest about 8 m off Sweet Hollow Road.
 */
class OsrmRoadSnapperTest {

    /** visit:16 as marked in finding-20261009-155741. */
    static final Location FINDING_VISIT_16 = new Location(40.61874158464826, -75.07658771957155);
    static final Location SWEET_HOLLOW_ROAD = new Location(40.618788, -75.076508);

    private static final String HINT = "\"hint\":\"uuj-gM_o_oAVAAAAKwAAAEQCAADXAAAADgJvQVBN7kHbtMlDDtAVQxUAAAArAAAARAIAANcAAABhfwAAZGyG-yTLawIUbIb79sprAhAA7wgAAAAA\"";
    private static final String SWEET_HOLLOW_WAYPOINT =
            "{" + HINT + ",\"location\":[-75.076508,40.618788],\"name\":\"Sweet Hollow Road\",\"distance\":8.479979925}";
    /** Recorded answer for visit 16 alone ({@code ?sources=0&destinations=1}). */
    private static final String SINGLE_RESPONSE = "{\"code\":\"Ok\",\"destinations\":[" + SWEET_HOLLOW_WAYPOINT
            + "],\"durations\":[[0]],\"sources\":[" + SWEET_HOLLOW_WAYPOINT + "]}";
    /** Recorded answer for visit 16 plus a second point ({@code ?destinations=0}). */
    private static final String PAIR_RESPONSE = "{\"code\":\"Ok\",\"destinations\":[" + SWEET_HOLLOW_WAYPOINT
            + "],\"durations\":[[0],[3238.8]],\"sources\":[" + SWEET_HOLLOW_WAYPOINT
            + ",{\"location\":[-75.200017,40.300662],\"name\":\"Barberry Court\",\"distance\":73.5232626}]}";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static OsrmRoadSnapper snapper(URI url) {
        return new OsrmRoadSnapper(true, url, "driving", Duration.ofSeconds(5));
    }

    @Test
    void findingVisit16IsMovedOntoSweetHollowRoad() throws IOException {
        OsrmRoadSnapper snapper = snapper(serve(200, SINGLE_RESPONSE));

        RoadSnap snap = snapper.snap(List.of(FINDING_VISIT_16)).get(0).orElseThrow();

        // Longitude first, and a lone location is sent twice because the table service needs two coordinates.
        assertThat(requests).containsExactly(
                "/table/v1/driving/-75.0765877,40.6187416;-75.0765877,40.6187416?sources=0&destinations=1");
        assertThat(snap.originalLocation()).isSameAs(FINDING_VISIT_16);
        assertThat(snap.snappedLocation().getLatitude()).isEqualTo(SWEET_HOLLOW_ROAD.getLatitude());
        assertThat(snap.snappedLocation().getLongitude()).isEqualTo(SWEET_HOLLOW_ROAD.getLongitude());
        assertThat(snap.distanceMeters()).isCloseTo(8.48, within(0.01));
        assertThat(snap.roadName()).isEqualTo("Sweet Hollow Road");
    }

    @Test
    void severalLocationsShareOneRequestAndAreCached() throws IOException {
        OsrmRoadSnapper snapper = snapper(serve(200, PAIR_RESPONSE));
        Location other = new Location(40.3, -75.2);

        List<Optional<RoadSnap>> snaps = snapper.snap(List.of(FINDING_VISIT_16, other));
        snapper.snap(List.of(other, FINDING_VISIT_16));

        assertThat(requests).containsExactly(
                "/table/v1/driving/-75.0765877,40.6187416;-75.2000000,40.3000000?destinations=0");
        assertThat(snaps.get(0).orElseThrow().roadName()).isEqualTo("Sweet Hollow Road");
        assertThat(snaps.get(1).orElseThrow().roadName()).isEqualTo("Barberry Court");
        assertThat(snaps.get(1).orElseThrow().snappedLocation().getLatitude()).isEqualTo(40.300662);
    }

    @Test
    void unreachableServerLeavesLocationsUnsnappedAndRetries() throws IOException {
        OsrmRoadSnapper snapper = snapper(serve(503, "busy"));

        assertThat(snapper.snap(List.of(FINDING_VISIT_16))).containsExactly(Optional.empty());
        assertThat(snapper.snap(List.of(FINDING_VISIT_16))).containsExactly(Optional.empty());
        assertThat(requests).hasSize(2);
    }

    @Test
    void disabledSnapperSendsNoRequest() throws IOException {
        URI url = serve(200, SINGLE_RESPONSE);
        OsrmRoadSnapper snapper = new OsrmRoadSnapper(false, url, "driving", Duration.ofSeconds(5));

        assertThat(snapper.snap(List.of(FINDING_VISIT_16))).containsExactly(Optional.empty());
        assertThat(requests).isEmpty();
    }

    @Test
    void largeDataSetsAreSplitIntoChunksOfAtMostOneHundred() throws IOException {
        // Echo stub: every coordinate snaps onto itself.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            String path = exchange.getRequestURI().getPath();
            String waypoints = Arrays.stream(path.substring(path.lastIndexOf('/') + 1).split(";"))
                    .map(lonLat -> "{\"location\":[" + lonLat + "],\"name\":\"Echo\",\"distance\":0}")
                    .collect(Collectors.joining(","));
            byte[] bytes = ("{\"code\":\"Ok\",\"sources\":[" + waypoints + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        OsrmRoadSnapper snapper = snapper(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        List<Location> locations = IntStream.range(0, 150).mapToObj(i -> new Location(40 + i * 0.001, -75)).toList();

        List<Optional<RoadSnap>> snaps = snapper.snap(locations);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).split(";")).hasSize(OsrmRoadSnapper.MAX_COORDINATES_PER_REQUEST);
        assertThat(requests.get(1).split(";")).hasSize(50);
        assertThat(snaps).allMatch(Optional::isPresent);
        assertThat(snaps.get(149).orElseThrow().snappedLocation().getLatitude()).isCloseTo(40.149, within(1e-9));
    }

    /** The web UI reads the correction from JSON for the popup, and posts it back with a new visit. */
    @Test
    void roadSnapJsonRoundTrip() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        RoadSnap snap = new RoadSnap(FINDING_VISIT_16, SWEET_HOLLOW_ROAD, 8.479979925, "Sweet Hollow Road");

        JsonNode json = mapper.valueToTree(snap);
        assertThat(json.toString()).isEqualTo("{\"originalLocation\":[40.61874158464826,-75.07658771957155],"
                + "\"snappedLocation\":[40.618788,-75.076508],\"distanceMeters\":8.479979925,\"roadName\":\"Sweet Hollow Road\"}");
        RoadSnap read = mapper.treeToValue(json, RoadSnap.class);
        assertThat(read.originalLocation().getLatitude()).isEqualTo(FINDING_VISIT_16.getLatitude());
        assertThat(read.snappedLocation().getLongitude()).isEqualTo(SWEET_HOLLOW_ROAD.getLongitude());
        assertThat(read.roadName()).isEqualTo("Sweet Hollow Road");
    }
}
