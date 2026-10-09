package org.acme.vehiclerouting.sightline;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Journals every REST request (method, path, query, status, duration) except Sightline's own. Dev/test only. */
@IfBuildProfile(anyOf = { "dev", "test" })
@Provider
public class SightlineRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String START = "sightline.start";

    @Override
    public void filter(ContainerRequestContext request) {
        request.setProperty(START, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path.startsWith("/sightline") || path.startsWith("sightline")) {
            return;
        }
        Object start = request.getProperty(START);
        double ms = start instanceof Long s ? (System.nanoTime() - s) / 1_000_000.0 : -1;
        String query = request.getUriInfo().getRequestUri().getRawQuery();
        BackendJournal.record("http", "method", request.getMethod(), "path", path, "query", query,
                "status", response.getStatus(), "ms", Math.round(ms * 10) / 10.0,
                "requestBytes", request.getLength() >= 0 ? request.getLength() : null);
    }
}
