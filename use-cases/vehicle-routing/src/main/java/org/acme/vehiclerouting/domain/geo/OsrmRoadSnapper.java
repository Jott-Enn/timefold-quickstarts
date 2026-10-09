package org.acme.vehiclerouting.domain.geo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.acme.vehiclerouting.domain.Location;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Snaps locations onto the nearest drivable road with an OSRM server.
 * <p>
 * Uses the table service rather than nearest: it returns the snapped waypoint of every coordinate it is given, so a
 * whole demo data set needs one request instead of one per location (the public demo server allows about one request
 * per second). Results are cached per coordinate. If the server cannot be reached, locations stay where they are.
 */
@ApplicationScoped
public class OsrmRoadSnapper implements RoadSnapper {

    private static final Logger LOGGER = Logger.getLogger(OsrmRoadSnapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The public OSRM server accepts at most 100 coordinates per table request. */
    static final int MAX_COORDINATES_PER_REQUEST = 100;

    private final boolean enabled;
    private final URI baseUrl;
    private final String profile;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Map<String, Optional<RoadSnap>> cache = new ConcurrentHashMap<>();

    @Inject
    public OsrmRoadSnapper(
            @ConfigProperty(name = "vehicle-routing.road-snapping.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "vehicle-routing.road-snapping.osrm-url",
                    defaultValue = "https://router.project-osrm.org") URI baseUrl,
            @ConfigProperty(name = "vehicle-routing.road-snapping.profile", defaultValue = "driving") String profile,
            @ConfigProperty(name = "vehicle-routing.road-snapping.timeout", defaultValue = "10s") Duration timeout) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.profile = profile;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<Optional<RoadSnap>> snap(List<Location> locations) {
        if (!enabled || locations.isEmpty()) {
            return Collections.nCopies(locations.size(), Optional.empty());
        }
        List<Location> uncached = locations.stream()
                .filter(location -> !cache.containsKey(key(location)))
                .collect(Collectors.toList());
        for (int from = 0; from < uncached.size(); from += MAX_COORDINATES_PER_REQUEST) {
            List<Location> chunk = uncached.subList(from, Math.min(from + MAX_COORDINATES_PER_REQUEST, uncached.size()));
            try {
                List<Optional<RoadSnap>> snaps = parseTableResponse(chunk, request(chunk));
                for (int i = 0; i < chunk.size(); i++) {
                    cache.put(key(chunk.get(i)), snaps.get(i));
                }
            } catch (IOException | RuntimeException e) {
                // Not cached: the next request tries again.
                LOGGER.warnf("Road snapping skipped for %d location(s), OSRM at %s failed: %s",
                        chunk.size(), baseUrl, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return locations.stream()
                .map(location -> cache.getOrDefault(key(location), Optional.empty()))
                .collect(Collectors.toList());
    }

    private String request(List<Location> locations) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(tableUri(locations))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    URI tableUri(List<Location> locations) {
        // OSRM expects longitude first.
        List<String> coordinates = locations.stream()
                .map(location -> String.format(Locale.ROOT, "%.7f,%.7f", location.getLongitude(), location.getLatitude()))
                .collect(Collectors.toCollection(ArrayList::new));
        String base = baseUrl.toString().replaceAll("/+$", "");
        // Every coordinate is a source (the default), so every coordinate gets a snapped waypoint;
        // a single destination keeps the duration matrix at n x 1.
        String query = "?destinations=0";
        if (coordinates.size() == 1) {
            // The table service needs at least two coordinates: repeat the location, but only as a destination.
            coordinates.add(coordinates.get(0));
            query = "?sources=0&destinations=1";
        }
        return URI.create(base + "/table/v1/" + profile + "/" + String.join(";", coordinates) + query);
    }

    static List<Optional<RoadSnap>> parseTableResponse(List<Location> locations, String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (!"Ok".equals(root.path("code").asText())) {
            throw new IOException("OSRM answered " + root.path("code").asText() + ": " + root.path("message").asText());
        }
        JsonNode sources = root.path("sources");
        if (sources.size() != locations.size()) {
            throw new IOException("OSRM returned " + sources.size() + " waypoints for " + locations.size() + " locations");
        }
        List<Optional<RoadSnap>> snaps = new ArrayList<>(locations.size());
        for (int i = 0; i < locations.size(); i++) {
            JsonNode waypoint = sources.get(i);
            JsonNode lonLat = waypoint.path("location");
            if (waypoint.isNull() || lonLat.size() != 2) {
                snaps.add(Optional.empty());
                continue;
            }
            Location snapped = new Location(lonLat.get(1).asDouble(), lonLat.get(0).asDouble());
            snaps.add(Optional.of(new RoadSnap(locations.get(i), snapped,
                    waypoint.path("distance").asDouble(), waypoint.path("name").asText(""))));
        }
        return snaps;
    }

    private static String key(Location location) {
        return location.getLatitude() + "," + location.getLongitude();
    }
}
