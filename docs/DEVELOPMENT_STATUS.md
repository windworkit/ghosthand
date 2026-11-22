# GhostHand development status

**Updated:** 2026-11-22  
**Baseline:** Build #53, `ed54ce5a60c85bb46294510b54faba83fa005a4b`  
**Working branch:** `fix/coordinate-pipeline`  
**Release state:** Debug validation build only. Real-device acceptance is not complete.

## Build #53 failure diagnosis

Build #53 did not have one coordinate space shared by capture and touch:

- `ScreenCaster` used app/window metrics to size MediaProjection's virtual display.
- `AutoPlayerService` independently queried window metrics to size AccessibilityService touch coordinates.
- The agent normalized bitmap points and the motor scaled those fractions using the second measurement.
- The alignment test compared aspect ratios only. Equal aspect ratios do not prove equal dimensions, origin, crop, rotation, or target display.
- The motor silently clamped invalid coordinates to 1%-99% of the display.
- Gesture cancellation and rejected dispatch were reported as successful completion.
- Any board change was accepted as a successful placement without comparing the expected board state.
- Geometry allowed a board lattice to extend beyond the bitmap and rounded the detected cell pitch to an integer.

## Changes on this branch

### Capture and coordinate mapping

- Added `ActiveDisplayResolver` for an immutable active-default-display snapshot (display ID, dimensions, rotation, density).
- Added `CapturedFrame` to keep each bitmap and its capture transform/timestamp together.
- Added one explicit `ScreenCoordinateTransform` for frame-pixel to display-pixel mapping, including source size, viewport translation, crop metadata and full-display eligibility.
- Smart Play rejects stale display IDs, dimensions, rotation, density, non-uniform scaling, cropped frames, non-finite points and out-of-frame/out-of-display points.
- Removed Smart Play's coordinate clamping and aspect-ratio-only acceptance.
- Smart Play taps and drags now use the same transform. API 30+ gestures are bound to the captured display ID.

### Geometry, pieces and solving

- Geometry now requires a fully contained 8x8 lattice, consistent grid-line spacing and near-square cell pitch.
- Cell pitch is retained as a float so rounding does not accumulate across eight cells.
- All cell centers are calculated by one helper and invalid cells are rejected.
- Tray recognition was refactored through a pure Kotlin RGB/projection-mask analyzer. The reader records detected tray bounds, grab point and the grabbed cell's anchor within the shape.
- Unknown/invalid pickup points are rejected; there is no guessed tray-slot fallback.
- Solver output is revalidated immediately before a gesture. Bounds, shape integrity, occupied-cell collisions and every occupied destination cell are checked.
- Simultaneously completed rows and columns are determined from the same post-placement board before either axis is cleared.

### Gesture and placement verification

- Accessibility dispatch now distinguishes dispatch rejection, cancellation, timeout and callback completion.
- A callback completion is not considered proof of placement.
- After a move, the agent waits for a newer MediaProjection frame, recalibrates the board, re-reads the tray and compares the actual grid against the solver's expected post-clear grid.
- The selected tray piece must also be consumed (or the final tray must refresh) before another move is attempted.
- Any mismatch stops Smart Play rather than continuing from stale state.
- The floating pill and Logcat tags expose planned frame/touch endpoints, board/tray bounds, solver target and transform metadata.
- Smart Play now honors the pill's Pause control. If paused during planning, it discards the old move and refreshes the board after resume.
- The floating accessibility pill window is temporarily marked non-touchable during every dispatched gesture, then restored in a cancellation-safe `finally` block so it cannot intercept a tray pickup or board drop.

## Automated regression coverage

The current JVM test suite covers:

- Capture-to-touch mapping at screen corners, center, and downscaled cover/inner display dimensions.
- Display ID, resolution, rotation, density, crop, non-uniform scale, invalid and non-finite point rejection.
- 8x8 bounds, fractional pitch, square-cell pitch, uniform line spacing and cell-center calculations.
- Legal/illegal placements, occupied collisions, malformed boards/shapes, simultaneous row/column clears and consecutive moves.
- Blue, red, pink, orange and special-color tray pixels; one/two/three occupied slots; empty slots; 3x3 and larger supported shapes; malformed/noisy masks.

**Important:** tray-mask tests are synthetic. No Woodoku Blast screenshot fixtures were available in the repository or accessible conversation files during this work. These tests are not a substitute for real-game screenshots or Fold 7 device testing.

## Known limitations and unverified acceptance items

- No actual Samsung Galaxy Z Fold 7 is connected to this development environment. Installation, live capture alignment, real gesture endpoints, landed pieces and complete gameplay are unverified.
- The board model currently records cells as occupied/empty. It does not yet classify distinct obstacle/special-tile types. The current luminance-based board reader could misclassify a dark special tile; this requires representative screenshots and dedicated fixtures.
- Diagnostics are available in the floating status pill and Logcat (`GhostHandPlan`, `GhostHandTouch`). A drawn spatial overlay on the game surface is not implemented; adding one requires care because it could contaminate the captured frame or intercept touches.
- A successful Gradle task or APK artifact is only a build result. It does not demonstrate real placement or a completed game sequence.

## Device validation protocol

Use the folded cover display in portrait orientation:

1. Install the newest debug APK from the successful workflow artifact for the current branch head.
2. Open Woodoku Blast and enable screen capture and GhostHand accessibility.
3. Start Smart Play with the floating pill visible. Before allowing extended play, capture a screenshot showing the board and tray plus the status/plan text.
4. Allow one placement. Capture the resulting board/tray screenshot and share the pill status.
5. If available, collect Logcat entries for `GhostHandPlan` and `GhostHandTouch`. Those records include the exact intended frame points, transformed touch endpoints, display snapshot and gesture callback result.
6. Do not continue a sequence after any rejection or mismatch. The agent should stop and report the failed verification.

The next engineering step is to compare those real screenshots/diagnostics with the detected 8x8 cell lattice and actual touch result, then add them as regression fixtures. Do not mark the project complete until the full acceptance sequence has been demonstrated on the Fold 7.
