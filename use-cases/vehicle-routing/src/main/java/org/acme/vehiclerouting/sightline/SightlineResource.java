package org.acme.vehiclerouting.sightline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Sightline: the in-app findings tool (capture, annotate, file). Development builds only: the class only exists in
 * the "dev" and "test" Quarkus build profiles, so a production build ships neither the button script nor the
 * filing endpoint.
 */
@IfBuildProfile(anyOf = { "dev", "test" })
@Tag(name = "Sightline (dev only)", description = "Findings: annotated screenshots filed with the state that produced them.")
@Path("sightline")
public class SightlineResource {

    private static final Map<String, String> ASSETS = Map.of(
            "client.js", "application/javascript",
            "findings.js", "application/javascript");

    private final FindingStore store;
    private final FindingEnricher enricher;
    private final ObjectMapper mapper;

    @Inject
    public SightlineResource(ObjectMapper mapper,
            SolutionManager<VehicleRoutePlan, HardMediumSoftScore> solutionManager,
            Instance<SolverConfig> solverConfig,
            @ConfigProperty(name = "sightline.findings-dir", defaultValue = "docs/findings") String findingsDir) {
        this.mapper = mapper;
        java.nio.file.Path workingDir = java.nio.file.Path.of("").toAbsolutePath();
        this.store = new FindingStore(workingDir.resolve(findingsDir), Clock.systemDefaultZone(), mapper);
        this.enricher = new FindingEnricher(solutionManager,
                solverConfig.isResolvable() ? solverConfig.get() : null, mapper, workingDir);
    }

    // ************************************************************************
    // Page and scripts
    // ************************************************************************

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "The findings page.")
    public Response page() {
        return classpath("findings.html", MediaType.TEXT_HTML);
    }

    @GET
    @Path("assets/{name}")
    public Response asset(@PathParam("name") String name) {
        String type = ASSETS.get(name);
        if (type == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return classpath(name, type);
    }

    private Response classpath(String name, String type) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sightline/" + name)) {
            if (in == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(in.readAllBytes(), type + (type.startsWith("text/") || type.endsWith("javascript")
                    ? ";charset=UTF-8" : "")).header("Cache-Control", "no-store").build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ************************************************************************
    // Findings
    // ************************************************************************

    @GET
    @Path("findings")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "index.json: every finding, open first, newest first.")
    public Response index() throws IOException {
        store.rebuildIndexIfMissing();
        java.nio.file.Path index = store.dir().resolve("index.json");
        if (!Files.exists(index)) {
            return Response.ok("[]").build();
        }
        return Response.ok(Files.readAllBytes(index)).header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("findings/{file}")
    @Operation(summary = "One finding file: <stem>.png, <stem>.thumb.png or <stem>.json.")
    public Response findingFile(@PathParam("file") String fileName) throws IOException {
        java.nio.file.Path p;
        try {
            p = store.file(fileName);
        } catch (FindingStore.InvalidFindingException e) {
            return badRequest(e.getMessage());
        }
        if (!Files.isRegularFile(p) || Files.size(p) == 0) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String type = fileName.endsWith(".png") ? "image/png" : MediaType.APPLICATION_JSON;
        return Response.ok(Files.readAllBytes(p), type).header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("findings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "File a finding: {png: base64, sidecar: {...}}. The server picks the name.")
    public Response file(JsonNode body) {
        if (body == null || !body.path("png").isTextual() || !body.path("sidecar").isObject()) {
            return badRequest("Expected {png: <base64>, sidecar: {...}}.");
        }
        byte[] png;
        try {
            String b64 = body.path("png").asText();
            int comma = b64.indexOf(',');
            png = Base64.getDecoder().decode(b64.startsWith("data:") ? b64.substring(comma + 1) : b64);
        } catch (IllegalArgumentException e) {
            return badRequest("png is not valid base64.");
        }
        ObjectNode sidecar = (ObjectNode) body.get("sidecar");
        try {
            enricher.enrich(sidecar);
            ObjectNode written = store.file(png, sidecar);
            ObjectNode reply = mapper.createObjectNode();
            reply.put("name", written.path("name").asText());
            reply.set("image", written.get("image"));
            reply.put("dir", store.dir().toString());
            reply.set("objects", written.get("objects"));
            return Response.status(Response.Status.CREATED).entity(reply).build();
        } catch (FindingStore.InvalidFindingException e) {
            return badRequest(e.getMessage());
        } catch (UncheckedIOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("findings/{stem}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Close a finding: {verdict, cause, reference} (reference = commit for fixed, stem for duplicate).")
    public Response close(@PathParam("stem") String stem, JsonNode body) {
        try {
            ObjectNode closed = store.close(stem, body.path("verdict").asText(null), body.path("cause").asText(null),
                    body.path("reference").asText(null));
            return Response.ok(Map.of("name", closed.path("name").asText(), "status", "done",
                    "verdict", closed.path("verdict").asText())).build();
        } catch (FindingStore.InvalidFindingException e) {
            return badRequest(e.getMessage());
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON).build();
    }
}
