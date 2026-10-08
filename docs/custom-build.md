# Custom build

This branch (`custom`) is Barberfish 4.0 with the developer's unreleased wind work
(`worktree-wind-sock`) merged in, plus one addition: **Show speed** on the Wind HUD slot.

## Show speed

Tap a HUD column, pick **Wind**, and turn on **Show speed**. The slot then stacks:

- top row: ride speed, with the slot's own speed smoothing
- bottom row: the Wind slot's arrow and headwind number (bare into the wind, minus with it)

It follows the Wind slot's rules: colour stays on the wind number, on the threshold scale
(red into a headwind, green with a tailwind); speed and arrow take the header colour. The
header reads SPEED with the speed and wind icons. When Headwind has no data the slot is a
plain Speed slot, and the column never drops while speed is valid. While the GPS fix has no
course the wind reading is held, as the Wind slot does, and speed keeps updating.

## What differs from upstream

New files, no conflict surface:

- `app/src/main/kotlin/.../datatype/SpeedWindSlot.kt`: combining speed and wind states
- `app/src/main/kotlin/.../datatype/shared/SpeedWindBitmap.kt`: the two-row renderer
- `app/src/main/kotlin/.../screens/HUDWindCard.kt`: the Wind slot's controls
- `app/src/test/kotlin/.../SpeedWindSlotTest.kt`

Small edits to upstream files:

- `extension/Settings.kt`: `HUDSlotConfig.windShowSpeed`, default off
- `datatype/shared/FieldState.kt`: `speedRow`
- `datatype/BarberfishView.kt`: the first branch of the value bitmap chain, and a
  `@Suppress` on `makeFieldRemoteViews`
- `datatype/HUDField.kt`: `.withSpeed(...)` on the Wind slot flow, `.withSpeedPreview(...)` on
  its preview
- `screens/HUDConfigSection.kt`: the Wind slot shows `HUDWindCard`

`docs/screenshots/all_fields.png` is 4.0's, which predates the Wind field, so it shows 39 of
the 40 fields. Regenerating it needs a device (`AllFieldPreviewsRenderTest`).

`windShowSpeed` is a plain key, so a build without it ignores it. A Wind slot itself is not
in official 4.0: going back to 4.0 resets a HUD that uses one.

## Updating from upstream

1. `git fetch upstream` and merge `upstream/master` (and the wind branch while it is
   unmerged) into `custom`.
2. Check that `SPEED_WIND_DIGIT_FILL` still equals `TWO_ROW_DIGIT_FILL` in `BitmapValue.kt`,
   that the Show speed branch is still first in the value bitmap chain, and that
   `renderWindArrowValueBitmap` keeps its signature.
3. Run `./gradlew spotlessApply test detektDebug lintDebug assembleDebug`.
