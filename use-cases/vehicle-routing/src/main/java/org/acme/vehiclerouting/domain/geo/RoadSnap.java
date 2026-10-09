package org.acme.vehiclerouting.domain.geo;

import org.acme.vehiclerouting.domain.Location;

/**
 * The correction applied when a location was moved onto the nearest drivable road.
 *
 * @param originalLocation where the location was before snapping (random demo point or map click)
 * @param snappedLocation the point on the road, used as the visit or home location from then on
 * @param distanceMeters how far the location was moved
 * @param roadName name of the road it was moved onto, empty if the road has no name
 */
public record RoadSnap(Location originalLocation, Location snappedLocation, double distanceMeters, String roadName) {
}
