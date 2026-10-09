package org.acme.vehiclerouting.sightline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindingStoreTest {

    private static final Clock SAME_SECOND = Clock.fixed(Instant.parse("2026-10-09T11:05:01Z"), ZoneId.of("Europe/Berlin"));

    @TempDir
    Path dir;

    private FindingStore store(Clock clock) {
        return new FindingStore(dir, clock, FindingRebuild.MAPPER);
    }

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    static ObjectNode sidecar(String note, String source) {
        ObjectNode s = FindingRebuild.MAPPER.createObjectNode();
        s.put("note", note);
        s.put("complete", true);
        s.putObject("source").put("name", source).put("view", "map");
        ObjectNode mark = s.putArray("marks").addObject();
        mark.put("n", 1).put("kind", "ellipse").put("colour", "red").put("width", 4);
        mark.putArray("points").add(FindingRebuild.MAPPER.createArrayNode().add(1).add(1))
                .add(FindingRebuild.MAPPER.createArrayNode().add(5).add(5));
        mark.putArray("objects").addObject().put("id", "visit:7").put("relation", "encloses");
        return s;
    }

    @Test
    void twoFilingsInTheSameSecondGetDistinctStemsAndNothingIsOverwritten() throws Exception {
        FindingStore store = store(SAME_SECOND);
        String a = store.file(png(10, 10), sidecar("first", "page")).path("name").asText();
        String b = store.file(png(10, 10), sidecar("second", "page")).path("name").asText();
        String c = store.file(png(10, 10), sidecar("third", "page")).path("name").asText();
        assertThat(List.of(a, b, c)).containsExactly("finding-20261009-130501", "finding-20261009-130501-2",
                "finding-20261009-130501-3");
        assertThat(store.read(a).path("note").asText()).isEqualTo("first");
        assertThat(store.read(b).path("note").asText()).isEqualTo("second");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("finding-"))).hasSize(9);
        }
        assertThat(store.read(a).path("created").asText()).isEqualTo("2026-10-09T13:05:01+02:00");
    }

    @Test
    void aStemIsNeverReusedEvenIfOnlyAnImageIsLeftOver() throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("finding-20261009-130501.png"), new byte[] { 1 });
        String stem = store(SAME_SECOND).file(png(10, 10), sidecar("x", "page")).path("name").asText();
        assertThat(stem).isEqualTo("finding-20261009-130501-2");
        assertThat(Files.readAllBytes(dir.resolve("finding-20261009-130501.png"))).containsExactly(1);
    }

    @Test
    void sizeCapAndThumbnail() throws Exception {
        FindingStore store = store(SAME_SECOND);
        assertThatThrownBy(() -> store.file(png(2561, 20), sidecar("", "page")))
                .isInstanceOf(FindingStore.InvalidFindingException.class).hasMessageContaining("2560");
        String stem = store.file(png(2560, 1600), sidecar("", "page")).path("name").asText();
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(dir.resolve(stem + ".thumb.png"))));
        assertThat(thumb.getWidth()).isEqualTo(360);
        assertThat(thumb.getHeight()).isEqualTo(225);
    }

    @Test
    void refusesNamesOutsideTheStemPattern() {
        FindingStore store = store(SAME_SECOND);
        for (String bad : List.of("../pom.xml", "..\\pom.xml", "finding-20261009-130501.exe", "finding-20261009-130501-1.png",
                "finding-2026109-130501.png", "index.md", "finding-20261009-130501.png/../../x", "")) {
            assertThatThrownBy(() -> store.file(bad)).as(bad).isInstanceOf(FindingStore.InvalidFindingException.class);
        }
        assertThatThrownBy(() -> store.read("../../etc")).isInstanceOf(FindingStore.InvalidFindingException.class);
        assertThat(store.file("finding-20261009-130501-2.thumb.png").getParent()).isEqualTo(dir.toAbsolutePath().normalize());
    }

    @Test
    void refusesUnknownSourcesNonPngAndMarksOutsideTheImage() throws Exception {
        FindingStore store = store(SAME_SECOND);
        assertThatThrownBy(() -> store.file(png(10, 10), sidecar("", "../evil")))
                .isInstanceOf(FindingStore.InvalidFindingException.class).hasMessageContaining("Unknown source");
        assertThatThrownBy(() -> store.file(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, sidecar("", "page")))
                .isInstanceOf(FindingStore.InvalidFindingException.class);
        ObjectNode outside = sidecar("", "page");
        ((ObjectNode) outside.path("marks").get(0)).putArray("points")
                .add(FindingRebuild.MAPPER.createArrayNode().add(50).add(1));
        assertThatThrownBy(() -> store.file(png(10, 10), outside)).hasMessageContaining("outside the image");
    }

    @Test
    void indexListsOpenFirstNewestFirstThenClosedWithVerdict() throws Exception {
        Clock t1 = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneId.of("Europe/Berlin"));
        Clock t2 = Clock.fixed(Instant.parse("2026-10-09T10:00:05Z"), ZoneId.of("Europe/Berlin"));
        String older = store(t1).file(png(10, 10), sidecar("older one", "page")).path("name").asText();
        String newer = store(t2).file(png(10, 10), sidecar("", "map")).path("name").asText();
        String md = Files.readString(dir.resolve("index.md"));
        assertThat(md.indexOf(newer)).isLessThan(md.indexOf(older));
        assertThat(md).contains("(no note)").contains("visit:7").contains(newer + ".png");

        store(t2).close(older, "fixed", "capacity check used < instead of <=", "76993a35");
        md = Files.readString(dir.resolve("index.md"));
        assertThat(md.indexOf("## Closed")).isLessThan(md.indexOf(older)).isGreaterThan(md.indexOf(newer));
        assertThat(md).contains("verdict: fixed (76993a35)").contains("capacity check used < instead of <=");
        assertThat(store(t2).read(older).path("status").asText()).isEqualTo("done");

        Files.delete(dir.resolve("index.md"));
        Files.delete(dir.resolve("index.json"));
        store(t2).rebuildIndexIfMissing();
        assertThat(dir.resolve("index.md")).exists();
        assertThat(FindingRebuild.MAPPER.readTree(dir.resolve("index.json").toFile()).get(0).path("name").asText())
                .isEqualTo(newer);
    }

    @Test
    void closingValidatesVerdictAndReference() throws Exception {
        FindingStore store = store(SAME_SECOND);
        String a = store.file(png(10, 10), sidecar("a", "page")).path("name").asText();
        String b = store.file(png(10, 10), sidecar("b", "page")).path("name").asText();
        assertThatThrownBy(() -> store.close(a, "wontfix", "x", null)).hasMessageContaining("Verdict");
        assertThatThrownBy(() -> store.close(a, "fixed", "x", null)).hasMessageContaining("commit");
        assertThatThrownBy(() -> store.close(a, "duplicate", "x", a)).hasMessageContaining("another existing finding");
        assertThatThrownBy(() -> store.close(a, "answered", "two\nlines", null)).hasMessageContaining("one-line");
        assertThat(store.close(a, "duplicate", "same visit as b", b).path("duplicateOf").asText()).isEqualTo(b);
        assertThat(Files.exists(dir.resolve(a + ".png"))).isTrue(); // closed findings stay
    }
}
