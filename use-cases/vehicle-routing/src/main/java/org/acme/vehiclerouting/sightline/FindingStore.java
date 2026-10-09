package org.acme.vehiclerouting.sightline;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sightline finding store: three files per finding ({@code <stem>.png}, {@code <stem>.thumb.png},
 * {@code <stem>.json}) plus the derived {@code index.md} / {@code index.json}.
 * <p>
 * Plain Java (no CDI) so that the REST endpoint, tests and command-line tools (closing a finding after a fix)
 * all go through the same code path. Every name coming from outside is validated against {@link #STEM}.
 */
public final class FindingStore {

    /** finding-YYYYMMDD-HHMMSS, optionally -2, -3, ... on collision. */
    public static final Pattern STEM = Pattern.compile("finding-(\\d{8})-(\\d{6})(?:-([2-9]|[1-9]\\d{1,3}))?");
    public static final Pattern FILE = Pattern.compile("(" + STEM.pattern() + ")(\\.png|\\.thumb\\.png|\\.json)");
    public static final Set<String> SOURCES = Set.of("page", "clipboard", "map");
    public static final Set<String> VERDICTS = Set.of("fixed", "intended", "superseded", "duplicate", "answered");
    public static final int MAX_EDGE = 2560;
    public static final int THUMB_EDGE = 360;
    public static final int MAX_NOTE = 2000;
    public static final int MAX_MARKS = 200;

    private static final DateTimeFormatter STEM_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");

    private final Path dir;
    private final Clock clock;
    private final ObjectMapper mapper;

    public FindingStore(Path dir, Clock clock, ObjectMapper mapper) {
        this.dir = dir.toAbsolutePath().normalize();
        this.clock = clock;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path dir() {
        return dir;
    }

    public static boolean isStem(String name) {
        return name != null && STEM.matcher(name).matches();
    }

    /** Thrown for anything the client got wrong; mapped to HTTP 400. */
    public static final class InvalidFindingException extends RuntimeException {
        public InvalidFindingException(String message) {
            super(message);
        }
    }

    // ************************************************************************
    // Filing
    // ************************************************************************

    /**
     * Files a finding: validates the PNG and sidecar, picks a fresh stem, writes the three files and regenerates
     * the indexes. Returns the sidecar as written.
     */
    public synchronized ObjectNode file(byte[] png, ObjectNode sidecar) {
        BufferedImage image = readPng(png);
        if (Math.max(image.getWidth(), image.getHeight()) > MAX_EDGE) {
            throw new InvalidFindingException("Image %dx%d exceeds the %d px longest-edge cap."
                    .formatted(image.getWidth(), image.getHeight(), MAX_EDGE));
        }
        validateSidecar(sidecar, image);
        try {
            Files.createDirectories(dir);
            OffsetDateTime now = OffsetDateTime.now(clock);
            String base = "finding-" + now.format(STEM_TIME);
            String stem = null;
            Path jsonPath = null;
            for (int i = 1; stem == null; i++) {
                String candidate = i == 1 ? base : base + "-" + i;
                Path candidateJson = resolve(candidate + ".json");
                if (Files.exists(resolve(candidate + ".png")) || Files.exists(resolve(candidate + ".thumb.png"))) {
                    continue; // never reuse a stem, even a half-written one
                }
                try {
                    Files.createFile(candidateJson); // atomic reservation of the stem
                    stem = candidate;
                    jsonPath = candidateJson;
                } catch (FileAlreadyExistsException e) {
                    // collision: try the next suffix
                }
            }
            sidecar.put("name", stem);
            sidecar.put("created", now.toString());
            ObjectNode imageNode = sidecar.withObject("/image");
            imageNode.put("file", stem + ".png");
            imageNode.put("thumb", stem + ".thumb.png");
            imageNode.put("width", image.getWidth());
            imageNode.put("height", image.getHeight());
            imageNode.put("bytes", png.length);
            if (!sidecar.hasNonNull("status")) {
                sidecar.put("status", "open");
            }
            sidecar.putNull("verdict");
            sidecar.putNull("closingNote");
            Files.write(resolve(stem + ".png"), png, StandardOpenOption.CREATE_NEW);
            Files.write(resolve(stem + ".thumb.png"), thumbnail(image), StandardOpenOption.CREATE_NEW);
            writeJson(jsonPath, sidecar);
            rebuildIndex();
            return sidecar;
        } catch (IOException e) {
            throw new UncheckedIOException("Filing failed: " + e.getMessage(), e);
        }
    }

    private void validateSidecar(ObjectNode sidecar, BufferedImage image) {
        JsonNode source = sidecar.path("source").path("name");
        if (!source.isTextual() || !SOURCES.contains(source.asText())) {
            throw new InvalidFindingException("Unknown source name: " + source + " (known: " + SOURCES + ").");
        }
        if (sidecar.path("note").asText("").length() > MAX_NOTE) {
            throw new InvalidFindingException("Note longer than " + MAX_NOTE + " characters.");
        }
        if (!sidecar.path("complete").isBoolean()) {
            throw new InvalidFindingException("Sidecar field 'complete' must be true or false.");
        }
        JsonNode marks = sidecar.path("marks");
        if (!marks.isArray() || marks.size() > MAX_MARKS) {
            throw new InvalidFindingException("Sidecar 'marks' must be an array of at most " + MAX_MARKS + ".");
        }
        for (JsonNode mark : marks) {
            if (!mark.path("n").isInt() || !mark.path("kind").isTextual() || !mark.path("points").isArray()) {
                throw new InvalidFindingException("Malformed mark: " + mark);
            }
            for (JsonNode p : mark.path("points")) {
                double x = p.path(0).asDouble(Double.NaN);
                double y = p.path(1).asDouble(Double.NaN);
                if (!(x >= 0 && y >= 0 && x <= image.getWidth() && y <= image.getHeight())) {
                    throw new InvalidFindingException("Mark " + mark.path("n") + " has a point outside the image: " + p);
                }
            }
        }
        // The client may not choose these.
        sidecar.remove(List.of("name", "created", "verdict", "closingNote"));
        sidecar.put("status", "open");
    }

    private static BufferedImage readPng(byte[] png) {
        if (png == null || png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new InvalidFindingException("Not a PNG image.");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new InvalidFindingException("Unreadable PNG image.");
            }
            return image;
        } catch (IOException e) {
            throw new InvalidFindingException("Unreadable PNG image: " + e.getMessage());
        }
    }

    static byte[] thumbnail(BufferedImage image) throws IOException {
        double scale = Math.min(1.0, (double) THUMB_EDGE / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = thumb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // Two-step downscale keeps text legible-ish without a dependency.
        BufferedImage src = image;
        while (src.getWidth() / 2 > w * 1.5) {
            BufferedImage half = new BufferedImage(src.getWidth() / 2, src.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
            var hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(src, 0, 0, half.getWidth(), half.getHeight(), null);
            hg.dispose();
            src = half;
        }
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(thumb, "png", out);
        return out.toByteArray();
    }

    // ************************************************************************
    // Reading
    // ************************************************************************

    /** Resolves a client-supplied file name; refuses anything that is not stem + known suffix. */
    public Path file(String fileName) {
        if (fileName == null || !FILE.matcher(fileName).matches()) {
            throw new InvalidFindingException("Refused file name: " + fileName);
        }
        return resolve(fileName);
    }

    private Path resolve(String validatedName) {
        Path p = dir.resolve(validatedName).normalize();
        if (!p.getParent().equals(dir)) {
            throw new InvalidFindingException("Refused path: " + validatedName);
        }
        return p;
    }

    public ObjectNode read(String stem) {
        if (!isStem(stem)) {
            throw new InvalidFindingException("Refused finding name: " + stem);
        }
        Path p = resolve(stem + ".json");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            if (Files.size(p) == 0) {
                return null; // reserved, being written
            }
            return (ObjectNode) mapper.readTree(p.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All readable sidecars, newest first. */
    public List<ObjectNode> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ObjectNode> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".json") && isStem(name.substring(0, name.length() - 5))) {
                    ObjectNode n = read(name.substring(0, name.length() - 5));
                    if (n != null) {
                        result.add(n);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing((ObjectNode n) -> sortKey(n.path("name").asText())).reversed());
        return result;
    }

    /** Sort key that orders -10 after -9 (plain text sorting would not). */
    static String sortKey(String stem) {
        Matcher m = STEM.matcher(stem);
        if (!m.matches()) {
            return stem;
        }
        int suffix = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
        return m.group(1) + m.group(2) + "%05d".formatted(suffix);
    }

    // ************************************************************************
    // Closing
    // ************************************************************************

    /**
     * Closes a finding with a verdict and a one-line cause. {@code reference} is the commit for "fixed" and the other
     * finding's stem for "duplicate". Nothing is deleted.
     */
    public synchronized ObjectNode close(String stem, String verdict, String cause, String reference) {
        ObjectNode sidecar = read(stem);
        if (sidecar == null) {
            throw new InvalidFindingException("No such finding: " + stem);
        }
        if (verdict == null || !VERDICTS.contains(verdict)) {
            throw new InvalidFindingException("Verdict must be one of " + VERDICTS + ".");
        }
        if (cause == null || cause.isBlank() || cause.contains("\n") || cause.contains("\r") || cause.length() > 300) {
            throw new InvalidFindingException("A one-line cause (max 300 characters) is required.");
        }
        sidecar.remove(List.of("commit", "duplicateOf"));
        if (verdict.equals("fixed")) {
            if (reference == null || !COMMIT.matcher(reference).matches()) {
                throw new InvalidFindingException("Verdict 'fixed' needs the commit (7-40 lowercase hex characters).");
            }
            sidecar.put("commit", reference);
        } else if (verdict.equals("duplicate")) {
            if (!isStem(reference) || reference.equals(stem) || read(reference) == null) {
                throw new InvalidFindingException("Verdict 'duplicate' needs the stem of another existing finding.");
            }
            sidecar.put("duplicateOf", reference);
        }
        sidecar.put("status", "done");
        sidecar.put("verdict", verdict);
        sidecar.put("closingNote", cause.strip());
        sidecar.put("closed", OffsetDateTime.now(clock).toString());
        try {
            writeJson(resolve(stem + ".json"), sidecar);
            rebuildIndex();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sidecar;
    }

    // ************************************************************************
    // Indexes (derived, never edited by hand)
    // ************************************************************************

    public synchronized void rebuildIndexIfMissing() {
        if (Files.isDirectory(dir)
                && (!Files.exists(dir.resolve("index.md")) || !Files.exists(dir.resolve("index.json")))) {
            try {
                rebuildIndex();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public synchronized void rebuildIndex() throws IOException {
        Files.createDirectories(dir);
        List<ObjectNode> all = list();
        List<ObjectNode> open = all.stream().filter(n -> !"done".equals(n.path("status").asText())).toList();
        List<ObjectNode> closed = all.stream().filter(n -> "done".equals(n.path("status").asText())).toList();

        ArrayNode json = mapper.createArrayNode();
        for (ObjectNode n : open) {
            json.add(indexEntry(n));
        }
        for (ObjectNode n : closed) {
            json.add(indexEntry(n));
        }
        writeJson(dir.resolve("index.json"), json);

        StringBuilder md = new StringBuilder();
        md.append("# Findings\n\n");
        md.append("<!-- Generated by Sightline from the sidecars on every filing and status change. Do not edit. -->\n\n");
        md.append("The latest finding is the first entry under Open. Read the PNG first, then the sidecar.\n\n");
        md.append("## Open (").append(open.size()).append(")\n\n");
        if (open.isEmpty()) {
            md.append("(none)\n");
        }
        for (ObjectNode n : open) {
            appendMd(md, n);
        }
        md.append("\n## Closed (").append(closed.size()).append(")\n\n");
        if (closed.isEmpty()) {
            md.append("(none)\n");
        }
        for (ObjectNode n : closed) {
            appendMd(md, n);
            md.append("  - verdict: ").append(n.path("verdict").asText());
            if (n.hasNonNull("commit")) {
                md.append(" (").append(n.path("commit").asText()).append(")");
            }
            if (n.hasNonNull("duplicateOf")) {
                md.append(" (of ").append(n.path("duplicateOf").asText()).append(")");
            }
            md.append(" — ").append(oneLine(n.path("closingNote").asText(""))).append("\n");
        }
        writeAtomically(dir.resolve("index.md"), md.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode indexEntry(ObjectNode n) {
        ObjectNode e = mapper.createObjectNode();
        String stem = n.path("name").asText();
        e.put("name", stem);
        e.put("created", n.path("created").asText());
        e.put("note", n.path("note").asText(""));
        e.put("marks", n.path("marks").size());
        e.set("objects", markedObjects(n));
        e.put("source", n.path("source").path("name").asText());
        e.put("view", n.path("source").path("view").asText(""));
        e.put("complete", n.path("complete").asBoolean());
        e.put("status", n.path("status").asText("open"));
        e.set("verdict", n.path("verdict").isMissingNode() ? null : n.get("verdict"));
        e.set("closingNote", n.path("closingNote").isMissingNode() ? null : n.get("closingNote"));
        e.set("commit", n.get("commit"));
        e.set("duplicateOf", n.get("duplicateOf"));
        e.put("image", stem + ".png");
        e.put("thumb", stem + ".thumb.png");
        return e;
    }

    private ArrayNode markedObjects(ObjectNode n) {
        ArrayNode ids = mapper.createArrayNode();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (JsonNode mark : n.path("marks")) {
            for (JsonNode o : mark.path("objects")) {
                seen.add(o.path("id").asText());
            }
        }
        seen.forEach(ids::add);
        return ids;
    }

    private void appendMd(StringBuilder md, ObjectNode n) {
        String stem = n.path("name").asText();
        String note = n.path("note").asText("");
        ArrayNode objects = markedObjects(n);
        List<String> ids = new ArrayList<>();
        objects.forEach(o -> ids.add(o.asText()));
        md.append("- **").append(stem).append("** — ").append(n.path("created").asText())
                .append(" — ").append(note.isBlank() ? "(no note)" : "\"" + oneLine(note) + "\"")
                .append(" — ").append(n.path("marks").size()).append(" mark(s)")
                .append(" — objects: ").append(ids.isEmpty() ? "(none resolved)" : String.join(", ", ids))
                .append(" — source: ").append(n.path("source").path("name").asText());
        String view = n.path("source").path("view").asText("");
        if (!view.isEmpty()) {
            md.append(" (").append(view).append(")");
        }
        if (!n.path("complete").asBoolean(true)) {
            md.append(" INCOMPLETE");
        }
        md.append(" — image: ").append(stem).append(".png\n");
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 160 ? t.substring(0, 157) + "..." : t;
    }

    private void writeJson(Path p, JsonNode node) throws IOException {
        writeAtomically(p, mapper.writeValueAsBytes(node));
    }

    private static void writeAtomically(Path p, byte[] bytes) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
