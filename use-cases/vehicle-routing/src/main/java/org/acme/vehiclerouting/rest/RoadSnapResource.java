package org.acme.vehiclerouting.rest;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.geo.RoadSnap;
import org.acme.vehiclerouting.domain.geo.RoadSnapper;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Tag(name = "Road snapping", description = "Moves a location onto the nearest drivable road.")
@Path("road-snap")
public class RoadSnapResource {

    private final RoadSnapper roadSnapper;

    @Inject
    public RoadSnapResource(RoadSnapper roadSnapper) {
        this.roadSnapper = roadSnapper;
    }

    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The location on the nearest road and the applied correction.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RoadSnap.class))),
            @APIResponse(responseCode = "204", description = "The location could not be snapped; use it as it is.") })
    @Operation(summary = "Snap a location onto the nearest drivable road.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response snap(@Parameter(required = true) @QueryParam("latitude") double latitude,
            @Parameter(required = true) @QueryParam("longitude") double longitude) {
        return roadSnapper.snap(List.of(new Location(latitude, longitude))).get(0)
                .map(snap -> Response.ok(snap).build())
                .orElseGet(() -> Response.noContent().build());
    }
}
