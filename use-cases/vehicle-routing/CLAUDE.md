# Agent instructions: vehicle-routing quickstart

## Findings (Sightline)

The developer reports defects with the in-app **Finding** button (navbar, or Alt+Shift+F outside text fields;
dev builds only: `mvn quarkus:dev`). A finding is a defect report on the whole program (solver result, constraint,
backend, data), with the screen as evidence.

- **Where:** `docs/findings/` (configurable: `sightline.findings-dir`). Per finding: `<stem>.png` (annotated
  screenshot), `<stem>.thumb.png`, `<stem>.json` (sidecar). Stem: `finding-YYYYMMDD-HHMMSS[-N]`.
- **Entry point:** `docs/findings/index.md` (generated, never edit it). "The latest finding" = the newest **open**
  stem, i.e. the first entry under "Open".
- **Reading a finding:** look at the PNG itself first. Then the sidecar: `marks` (each `{n, kind, colour, width,
  points, label}` in image pixels, with `objects` = the domain ids it encloses/crosses/points at, e.g. `visit:28`,
  `vehicle:2`, `leg:2:8` = vehicle 2's leg into its 9th stop), `objects` (data per marked id: assignment, arrival,
  time window, lateness, constraint impact, `placedBy` = solver phase and best-solution event that placed it),
  `state.routePlan` (the full plan as the app loaded it), `backend` (solver jobs, HTTP requests, warnings of the last
  10 minutes), `journal` (the developer's last 200 UI actions), `build` (commit, dirty files). The JSON alone is not
  the finding.
- **Words arrive in the conversation**, not necessarily in the note field: pair the finding with what the developer
  says about it ("mark 2 is wrong" = the mark with `n: 2`).
- **Diagnose along the sightline:** rebuild the result outside the UI first:
  `mvn test -Dtest=FindingRebuildTest#rebuildFinding -Dsightline.finding=docs/findings/<stem>.json`
  (fresh JVM; recomputes shadow variables and score from the captured plan and compares with what was on screen).
  Confirm the marked defect appears in the rebuilt plan, find the stage that produced the marked objects (constraint
  provider, shadow-variable supplier in `Visit`, driving-time calculator, demo-data generator, REST resource) and
  measure there. If the result cannot be rebuilt, say what the sidecar is missing and fix the capture
  (`src/main/resources/sightline/client.js`, `FindingEnricher`) before the defect. Note: re-running the solver is not
  guaranteed to reach the same assignment (wall-clock termination); the captured plan holds the assignment itself.
- **Turn the finding into a check** that fails before the fix and passes after, using the sidecar's `state.routePlan`
  as the fixture (see `FindingRebuildTest`). When the marks judge a heuristic or constraint weighting, keep each marked
  object as a labeled example so later tuning cannot silently undo it.
- **Close it when the fix lands**, through the same code path as the findings page (regenerates the indexes):
  `mvn test -Dtest=FindingCloseTool -Dsightline.close=<stem> -Dsightline.verdict=fixed -Dsightline.reference=<commit> "-Dsightline.cause=<one line>"`
  (or `POST /sightline/findings/<stem>/close` while the app runs). Verdicts: fixed (commit), intended, superseded,
  duplicate (other stem), answered. Nothing is deleted.
