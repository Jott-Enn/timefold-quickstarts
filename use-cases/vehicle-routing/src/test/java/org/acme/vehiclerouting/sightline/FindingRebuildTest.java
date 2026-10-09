package org.acme.vehiclerouting.sightline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Rebuilds findings outside the UI. Plain JUnit (no Quarkus), so each Maven run is a fresh process.
 * <p>
 * Rebuild any filed finding:
 * {@code mvn test -Dtest=FindingRebuildTest#rebuildFinding -Dsightline.finding=docs/findings/<stem>.json}
 */
class FindingRebuildTest {

    /** Regression fixture: a finding filed through the real button (solved PHILADELPHIA plan, 3 marks). */
    @Test
    void fixtureRebuildsToWhatWasOnScreen() throws Exception {
        JsonNode sidecar = FindingRebuild.MAPPER.readTree(getClass().getResourceAsStream("/sightline/finding-fixture.json"));
        FindingRebuild.Report report = FindingRebuild.compare(sidecar, FindingRebuild.rebuild(sidecar));
        System.out.println(report);
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.matches()).anyMatch(s -> s.startsWith("score ="));
    }

    @Test
    @EnabledIfSystemProperty(named = "sightline.finding", matches = ".+")
    void rebuildFinding() throws Exception {
        Path path = Path.of(System.getProperty("sightline.finding"));
        JsonNode sidecar = FindingRebuild.read(path);
        FindingRebuild.Report report = FindingRebuild.compare(sidecar, FindingRebuild.rebuild(sidecar));
        System.out.println("Finding " + sidecar.path("name").asText() + " (" + path + ")");
        System.out.println(report);
        assertThat(report.mismatches()).as("rebuilt result differs from the screen; the capture misses an input").isEmpty();
    }
}
