package org.acme.vehiclerouting.domain.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.Visit;

/**
 * Moves locations onto the nearest drivable road.
 */
public interface RoadSnapper {

    /**
     * @param locations locations to snap
     * @return one entry per location, in the same order; empty where the location could not be snapped
     *         (no road nearby, service unreachable, snapping disabled)
     */
    List<Optional<RoadSnap>> snap(List<Location> locations);

    /**
     * Moves every vehicle home location and visit location onto the nearest road and records the applied correction
     * on the vehicle or visit. Locations that cannot be snapped stay unchanged.
     * <p>
     * Call it before building the {@link org.acme.vehiclerouting.domain.VehicleRoutePlan}: the plan computes its
     * driving-time matrix from these locations.
     */
    default void snapToRoads(List<Vehicle> vehicles, List<Visit> visits) {
        List<Location> locations = new ArrayList<>(vehicles.size() + visits.size());
        vehicles.forEach(vehicle -> locations.add(vehicle.getHomeLocation()));
        visits.forEach(visit -> locations.add(visit.getLocation()));
        List<Optional<RoadSnap>> snaps = snap(locations);
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle vehicle = vehicles.get(i);
            snaps.get(i).ifPresent(snap -> {
                vehicle.setHomeLocation(snap.snappedLocation());
                vehicle.setHomeLocationSnap(snap);
            });
        }
        for (int i = 0; i < visits.size(); i++) {
            Visit visit = visits.get(i);
            snaps.get(vehicles.size() + i).ifPresent(snap -> {
                visit.setLocation(snap.snappedLocation());
                visit.setLocationSnap(snap);
            });
        }
    }

}
