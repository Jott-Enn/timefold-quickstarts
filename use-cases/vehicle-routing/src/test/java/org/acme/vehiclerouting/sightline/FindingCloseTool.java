package org.acme.vehiclerouting.sightline;

import java.nio.file.Path;
import java.time.Clock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Closes a finding without the app running, through the same {@link FindingStore#close} the findings page uses
 * (so index.md / index.json are regenerated, never hand-edited):
 * <pre>
 * mvn test -Dtest=FindingCloseTool -Dsightline.close=finding-YYYYMMDD-HHMMSS \
 *     -Dsightline.verdict=fixed -Dsightline.reference=&lt;commit&gt; "-Dsightline.cause=one-line cause"
 * </pre>
 * Verdicts: fixed (reference = commit), intended, superseded, duplicate (reference = other stem), answered.
 * With the app running, POST /sightline/findings/{stem}/close {verdict, cause, reference} does the same.
 */
class FindingCloseTool {

    @Test
    @EnabledIfSystemProperty(named = "sightline.close", matches = ".+")
    void close() {
        Path dir = Path.of(System.getProperty("sightline.dir", "docs/findings"));
        FindingStore store = new FindingStore(dir, Clock.systemDefaultZone(), FindingRebuild.MAPPER);
        ObjectNode closed = store.close(System.getProperty("sightline.close"), System.getProperty("sightline.verdict"),
                System.getProperty("sightline.cause"), System.getProperty("sightline.reference"));
        System.out.println("Closed " + closed.path("name").asText() + ": " + closed.path("verdict").asText()
                + " - " + closed.path("closingNote").asText());
    }
}
