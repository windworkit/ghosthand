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
- Compares quantized per-cell RGB signatures and detected tray-shape/pickup signatures as well as occupancy after a drop. Special-object visual changes or tray-piece disappearance are not mistaken for rejected gestures and blindly retried; ambiguous transitions pause safely.
- Supports 8x8 and 9x9 boards fully inside the captured frame; framed-square calibration compares candidate cell seams instead of assuming one fixed size.
- Expands board-rail search ranges for both the Fold cover and inner display.
- Tracks capture-frame freshness.
- Stops the agent and capture service when screen projection ends or the captured display changes size.

### Tray-shape handling
- Uses edge connectivity (four-neighbour) rather than treating diagonal corner contact as one piece.
- Rejects ambiguous/disconnected tray detections instead of dropping cells from the detected mask.
- Keeps exact cell masks and pickup anchors for the solver and drag planner.

### Objective OCR and progress telemetry
- Preserves both token-level and line-level OCR results.
- Recognizes explicit textual labels for flowers, hearts, butterflies, apples, balloons, bombs, fireworks, gems, crates/boxes, and score.
- Parses explicit counters such as `2/5` when they appear in the same OCR line as a recognized objective.
- Retains unlabelled numeric tokens (including split ratios) for diagnostics instead of guessing their meaning.
- Tracks explicitly labeled counter changes by objective type and approximate screen position. A progress/regression event is reported only after the new value appears in two separate captured frames, reducing one-frame OCR false positives.
- Adds a reference-only mechanic catalogue for hearts, balloons, apples, and score. Public gameplay examples describe marked-tile collection, balloon blocks that persist until their release count is exhausted, and apples that must fall to the bottom.
- Counter telemetry and mechanic metadata are diagnostic only: neither identifies board icons nor alters move scoring.

### Objective move-impact foundation
- Added a pure evaluator for hypothetical placements when cells have already been confidently labeled.
- Geometry calibration now supports both 8x8 and 9x9 boards and scores internal seam alignment before choosing a size.
- Scores explicitly recognized heart/marked-tile collection on completed rows and columns.
- Models apples surviving a line clear, falling through newly empty cells, and collecting only when they reach the bottom.
- Reports balloon-block contacts without guessing how many balloons were released.
- Unknown flower/butterfly/bomb/firework/gem/crate mechanics receive no invented objective bonus.
- The evaluator is not connected to live move selection yet because board icon recognition still returns unclassified occupied cells; existing live solver behavior is unchanged.

### Script backup and restore
- Export scripts into a portable, versioned JSON backup through Android's document picker.
- Restore scripts after reinstall; imports are validated and merged by script ID.
- Validate UUID IDs, normalized coordinates, editor-supported pass/jitter/duration/loop settings, loop balance, duplicate IDs, schema version, and backup size before writing anything.
- Added `tools/backup-legacy-scripts.ps1` for the older installation: it uses ADB `run-as` to extract the existing private `files/scripts` folder and converts it into the JSON backup format accepted by the new in-app Restore Scripts button.
- The legacy helper requires Android Platform-Tools, one authorized USB-debugging device, and an installed debuggable `com.ghosthand` package. It does not uninstall or modify the existing app.

### Automated tests and CI
- Runs JVM unit tests before building the debug APK.
- Tests solver line clearing, piece masks, board bounds, shape connectivity, objective-label parsing, debounced objective-counter progress, isolated heart/apple objective scoring, and board-color classification.
- Caches a stable CI debug signing key and increments Android `versionCode` using the GitHub Actions run number.

## Not implemented yet

These are deliberately listed as incomplete; they must not be advertised as working features.

1. **Icon-level object recognition.** Flower, heart, butterfly, apple, balloon, bomb, fireworks, gem, and crate icons are not yet classified from board pixels. Current occupied cells remain `UNCLASSIFIED_OCCUPIED`.
2. **Special-object state tracking.** Balloon release counts, apple falling/support, flower/butterfly/heart collection, bomb timers, and obstacle health/removal rules are not simulated yet; the new mechanic catalogue is reference metadata only.
3. **Objective-aware search.** `ObjectiveMoveScorer` evaluates known heart/apple impacts in isolation, but is not yet wired into the live solver. OCR objective summaries remain diagnostic until icon recognition can supply trustworthy typed board cells.
4. **Complete piece catalogue validation.** The tray reader supports fixed-orientation polyomino masks up to 5x5, but the visual catalogue still needs confirmation against real in-game samples for every shape.
5. **Device/gameplay verification.** Automated JVM tests and APK compilation do not prove screen recognition or touch calibration on a Galaxy Z Fold 7. Real Woodoku Blast frames and an actual successful placement will ultimately be required.

## Safety rule

Never issue an automated drag when the board geometry, occupancy background, detected shape, pickup anchor, frame freshness, or post-drop result is ambiguous. Prefer pausing with a useful diagnostic to making a speculative move.
