package org.acme.vehiclerouting.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.geo.RoadSnap;
import org.acme.vehiclerouting.domain.geo.RoadSnapper;
import org.junit.jupiter.api.Test;

/**
 * Regression for finding-20261009-155741: demo locations are random points in a bounding box and must be moved onto
 * a road, with the applied correction kept for the popup. Plain JUnit with a stub snapper (no network).
 */
class VehicleRouteDemoRoadSnapTest {

    /** Stub: "the road" is always 0.0001 degrees north, named after the original latitude. */
    private static final RoadSnapper NORTH_BY_A_BIT = locations -> locations.stream()
            .map(location -> Optional.of(new RoadSnap(location,
                    new Location(location.getLatitude() + 0.0001, location.getLongitude()), 11.1, "Road")))
            .toList();

    @Test
    void findingVisit16IsSnappedAndKeepsItsOriginalPosition() {
        VehicleRoutePlan plan = new VehicleRouteDemoResource(NORTH_BY_A_BIT)
                .build(VehicleRouteDemoResource.DemoData.PHILADELPHIA);

        Visit visit16 = plan.getVisits().stream().filter(v -> v.getId().equals("16")).findFirst().orElseThrow();
        assertThat(visit16.getName()).isEqualTo("Gus Jones");
        // The position marked in the finding is the original one ...
        assertThat(visit16.getLocationSnap().originalLocation().getLatitude()).isEqualTo(40.61874158464826);
        assertThat(visit16.getLocationSnap().originalLocation().getLongitude()).isEqualTo(-75.07658771957155);
        // ... and the visit now sits on the road.
        assertThat(visit16.getLocation()).isSameAs(visit16.getLocationSnap().snappedLocation());
        assertThat(visit16.getLocation().getLatitude()).isEqualTo(40.61874158464826 + 0.0001);
    }

    @Test
    void everyLocationIsSnappedBeforeTheDrivingTimesAreComputed() {
        VehicleRoutePlan plan = new VehicleRouteDemoResource(NORTH_BY_A_BIT)
                .build(VehicleRouteDemoResource.DemoData.PHILADELPHIA);

        for (Vehicle vehicle : plan.getVehicles()) {
            assertThat(vehicle.getHomeLocationSnap()).isNotNull();
            assertThat(vehicle.getHomeLocation()).isSameAs(vehicle.getHomeLocationSnap().snappedLocation());
        }
        for (Visit visit : plan.getVisits()) {
            assertThat(visit.getLocationSnap()).isNotNull();
            assertThat(visit.getLocation()).isSameAs(visit.getLocationSnap().snappedLocation());
            // The plan's driving-time matrix was built from the snapped locations.
            assertThat(visit.getLocation().getDrivingTimeSeconds()).isNotNull();
            assertThat(visit.getLocationSnap().originalLocation().getDrivingTimeSeconds()).isNull();
        }
    }

    @Test
    void unsnappableLocationsStayWhereTheyAre() {
        RoadSnapper nothingNearby = locations -> locations.stream().map(l -> Optional.<RoadSnap> empty()).toList();
        VehicleRoutePlan snapped = new VehicleRouteDemoResource(NORTH_BY_A_BIT)
                .build(VehicleRouteDemoResource.DemoData.PHILADELPHIA);
        VehicleRoutePlan unsnapped = new VehicleRouteDemoResource(nothingNearby)
                .build(VehicleRouteDemoResource.DemoData.PHILADELPHIA);

        List<Visit> visits = unsnapped.getVisits();
        for (int i = 0; i < visits.size(); i++) {
            assertThat(visits.get(i).getLocationSnap()).isNull();
            assertThat(visits.get(i).getLocation().getLatitude())
                    .isEqualTo(snapped.getVisits().get(i).getLocationSnap().originalLocation().getLatitude());
        }
    }
}
