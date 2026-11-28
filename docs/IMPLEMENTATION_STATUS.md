# GhostHand implementation status

This document tracks what the objective-aware core does and, just as importantly, what it does not yet claim to do.

## Implemented on `codex/objective-aware-core`

### Placement and solver safety
- Uses the detected piece pickup tile as the board destination anchor.
- Keeps drag calibration separate for each exact piece mask and pickup anchor.
- Rejects coordinates outside the captured frame instead of clamping them to an edge.
- Treats Android gesture cancellation and dispatch rejection as failures.
- Learns a correction only when the resulting board uniquely identifies the actual landing.
- Requires a stable, fresh board read before accepting a placement.
- Validates board dimensions, piece footprints, connectivity, and placement bounds.
- Detects all completed rows and columns from the same pre-clear board state.

### Vision and device lifecycle
- Uses cell-center RGB/luminance against a background estimated from cell perimeter samples.
- Reports the support/confidence for that background estimate and refuses to move when it is weak.
- Preserves occupied-but-unclassified cells instead of pretending to know their object type.
- Requires an 8x8 board fully inside the captured frame.
- Expands board-rail search ranges for both the Fold cover and inner display.
- Tracks capture-frame freshness.
- Stops the agent and capture service when screen projection ends or the captured display changes size.

### Tray-shape handling
- Uses edge connectivity (four-neighbour) rather than treating diagonal corner contact as one piece.
- Rejects ambiguous/disconnected tray detections instead of dropping cells from the detected mask.
- Keeps exact cell masks and pickup anchors for the solver and drag planner.

### Objective OCR groundwork
- Preserves both token-level and line-level OCR results.
- Recognizes explicit textual labels for flowers, butterflies, apples, balloons, bombs, fireworks, gems, crates/boxes, and score.
- Parses explicit counters such as `2/5` when they appear in the same OCR line as a recognized objective.
- Retains unlabelled numeric tokens (including split ratios) for diagnostics instead of guessing their meaning.

### Script backup and restore
- Export scripts into a portable, versioned JSON backup through Android's document picker.
- Restore scripts after reinstall; imports are validated and merged by script ID.
- Validate UUID IDs, normalized coordinates, step durations, loop balance, duplicate IDs, schema version, and backup size before writing anything.
- Backup/restore is intended to protect local scripts if an app-signing transition requires a one-time reinstall.

### Automated tests and CI
- Runs JVM unit tests before building the debug APK.
- Tests solver line clearing, piece masks, board bounds, shape connectivity, objective-label parsing, and board-color classification.
- Caches a stable CI debug signing key and increments Android `versionCode` using the GitHub Actions run number.

## Not implemented yet

These are deliberately listed as incomplete; they must not be advertised as working features.

1. **Icon-level object recognition.** Flower, butterfly, apple, balloon, bomb, fireworks, gem, and crate icons are not yet classified from board pixels. Current occupied cells remain `UNCLASSIFIED_OCCUPIED`.
2. **Special-object state tracking.** Balloon stages, apple falling, flower/butterfly collection, bomb timers, and any obstacle health/removal rules are not simulated yet.
3. **Objective-aware search.** The solver still optimizes ordinary placement/line-clearing outcomes. OCR objective summaries are diagnostic only; they do not yet affect move scoring.
4. **Complete piece catalogue validation.** The tray reader supports fixed-orientation polyomino masks up to 5x5, but the visual catalogue still needs confirmation against real in-game samples for every shape.
5. **Device/gameplay verification.** Automated JVM tests and APK compilation do not prove screen recognition or touch calibration on a Galaxy Z Fold 7. Real Woodoku Blast frames and an actual successful placement will ultimately be required.

## Safety rule

Never issue an automated drag when the board geometry, occupancy background, detected shape, pickup anchor, frame freshness, or post-drop result is ambiguous. Prefer pausing with a useful diagnostic to making a speculative move.
