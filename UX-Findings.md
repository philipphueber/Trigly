# Trigly UX sweep

Read-only review of `ui/src/main/kotlin/app/phueber/trigly/ui/`, `ui/src/main/res/values/strings.xml`,
and the docs (`README.md`, `docs/architecture.md`, `docs/conditions.md`, `docs/triggers.md`) against
that code. No build, no emulator, no tests were run — everything below was found by reading source.

One piece of context that affects how several findings should be read: the working tree currently
has uncommitted changes (`git status`) building the `shortcut` trigger, the emoji-icon config field,
and the fold of `location_check` into `location`. Where a finding concerns files in that uncommitted
set, it is called out explicitly — it describes in-progress work, not a shipped regression. Findings
against files with no working-tree changes (e.g. `GateEditor.kt`, `RuleEditorScreen.kt`, `Blocks.kt`,
`Palette.kt`) describe the app as it would actually ship today.

Severity: **blocker** (breaks the feature or fails silently) / **significant** (real problem, not
fatal) / **polish** (worth doing, not urgent).

---

## Status — what has been fixed since the sweep

Re-audited against HEAD (`d2fc1bf`) by reading each finding's target file directly, not by reading
commit messages. The previous version of this section was wrong about at least one item (it called
`rules_empty` unfixed; it was fixed). Findings below are in the same order as the report. Of the 19
tagged items, 18 are fixed and 1 (the emoji cell's touch target) is unresolved — it's inside
`EmojiPicker.kt`, which another agent is actively editing right now, so it isn't re-checked here beyond
noting that.

**Rule editor — the gate and condition tree**
- **The "Only if" section is gone — fixed, and the doc claim is now true.** `GateEditor.kt` prints no
  section label of its own; every passive slot is offered and labelled as a trigger ("Add trigger" /
  "Add a trigger that must also be true"), and the picker dialogs for a passive slot are titled "Add
  trigger" / "Change trigger" — the same titles the edge picker uses, per `RuleEditorScreen.kt`'s
  `Picking.NewCondition` / `Picking.ConditionType` branches. `docs/conditions.md`'s phase-6 claim that
  the separate section and vocabulary are gone now matches the shipped editor.

**Home-screen shortcut trigger**
- **Fixed end to end**, all three dead ends the finding named:
  - The id is no longer a typed field. `ConfigField.GeneratedId` (`core/ConfigField.kt`) is a new
    field kind the editor mints once, invisibly, when the component is added
    (`RuleDraft.defaultConfigFor` → `RuleJson.newId()`; `ConfigFieldEditor.kt` draws nothing for it).
  - `ShortcutTriggerFactory.configFields` now declares a `ConfigField.Emoji` icon field
    (`triggers/ShortcutTrigger.kt`).
  - A "pin to home screen" control exists and is wired to `ShortcutPinning.requestPinShortcut` —
    but by a more general route than the finding suggested (a footer button hardcoded to this one
    trigger). `ComponentTool.PinShortcut` was added to the same closed set as `Test` and
    `InspectNotifications` (`core/ComponentRequirement.kt`); `ShortcutTriggerFactory.toolsFor` returns
    it once the id exists; `RuleEditorScreen`'s generic `ComponentTools` renders whatever tools a
    factory declares, calling through to `MainActivity.pinShortcut()`. Any future component that needs
    a footer action gets this for free, rather than needing its own editor special-case.

**Rules list**
- **Fixed, by a different route than suggested.** `RulesScreen.kt`'s `summarise()` now joins every
  trigger edge with "or" (naming each, rather than the "+N more" count the finding proposed) and
  appends a condition count — "+1 condition" / "+N conditions" — when the gate has any. A rule with a
  second OR-edge or a condition tree no longer reads as if it had neither.

**Color roles**
- **Fixed exactly as suggested.** Both flagged spots now use `MaterialTheme.extra.accent`:
  `NotificationInspectorScreen.kt`'s app-name label and `PatternTester.kt`'s "MATCHES" verdict text.
  Re-swept `colorScheme.primary` across the UI module: every remaining use is a `Surface`/background
  fill (`BlockHeader`, `SectionLabel`, `ComponentPicker`'s category header, `BlockToggle`), never a
  bare `Text`/`Icon` color. No new instance of the bug.

**`CaveatBadge`**
- **Fixed, by a different route than suggested.** Rather than `Modifier.minimumInteractiveComponentSize()`
  or a plain 48dp box, `Blocks.kt` adds a purpose-built `OverflowingTouchTarget`: it reports a 22dp
  footprint to whatever lays it out (so no row anywhere reflows) while the actual clickable region is
  48dp and allowed to overhang that reported footprint. Same outcome the finding asked for — a real
  48dp hit area — reached without growing any list row.

**Pickers**
- **`AppPackageField` — fixed exactly as suggested.** It now guards with
  `packageName?.takeIf { apps.labelFor(it) != it }`, the same guard `BluetoothAddressField` already had.
- **`PickerRow` touch target — fixed exactly as suggested.** `Modifier.heightIn(min = 48.dp)` was added.
- **Notification button selectable-but-invalid — fixed exactly as suggested.**
  `NotificationButtonPicker.kt` now passes `enabled = !takesText`; `PickerRow` disables the click,
  dims the row (alpha 0.38, matching Material's disabled-content convention), and marks it disabled in
  the semantics tree.

**Coordinates and time fields**
- **"Use where I am now" grant wording — fixed exactly as suggested.** The failure message no longer
  promises a "below" that may not exist; it now reads "Grant Trigly's location permission in system
  settings, then try again."
- **`DurationField` comma decimal — fixed exactly as suggested.** The input is normalized with
  `typed.replace(',', '.')` before `toDoubleOrNull()`.
- **Longitude / time asterisk — fixed exactly as suggested.** Both labels are now routed through
  `fieldLabel(...)`, same as latitude and the date box.

**Rule-reference picker**
- **Fixed, but by a deliberately different route than suggested — the finding's proposed fix would
  have been wrong.** `RulePicker.kt` now explains in its own KDoc that a rule referencing itself is not
  a bug to filter out: `SetRuleEnabledAction`'s "point a rule at itself to make it turn itself off after
  running once" depends on exactly this being offered. What was actually missing was honesty about
  which row it is, so the fix adds a `LocalCurrentRuleId` composition local and appends
  "· the rule you're editing" to that row wherever it appears (the dialog and the value box), and
  corrects the placeholder copy from "No other rules yet" to "No rules yet. Save one first, then come
  back." — since the old wording promised an exclusion that was never going to happen.
- **"on"/"off" casing — fixed exactly as suggested.** Both spots now render uppercase "ON"/"OFF",
  with a comment pointing at `BlockToggle` as the convention being matched.

**Pattern tester and regex highlighter**
- **Anchor/error same red — fixed, and further than the finding asked.** Rather than leaving the
  "minor tension" the finding flagged, `RegexHighlight.kt` adds a third, distinct color role,
  `extra.caution`, for the anchor token, so it no longer shares `colorScheme.error` with an actual
  compile failure.

**Emoji picker**
- **The reachability premise is no longer true — no longer applicable.** `ShortcutTriggerFactory` now
  declares `ConfigField.Emoji`, so the picker is reachable from a real rule (the shortcut trigger's
  icon field), contradicting the finding's "unreachable from any real rule" claim, which was accurate
  only before that field was declared.
- **The `EmojiCell` touch-target spot-check — measured, and it was real. Now fixed.** On a
  phone-width dialog (API 35, ~411dp screen) a cell rendered **32.8dp** square, two thirds of the
  minimum — the finding was right to flag it and right not to assert it without a measurement. Six
  fixed columns was the cause, and `Modifier.minimumInteractiveComponentSize()` could not have fixed
  it: `GridCells.Fixed` hands each item a fixed *maxWidth* equal to the column width, and no min-size
  modifier grows a layout past the maximum its parent imposes. The grid is now
  `GridCells.Adaptive(minSize = 56.dp)` — 48dp plus the cell's own 4dp padding either side — so the
  column width itself can never be narrower than a touch target, at any width the dialog renders at.
  `EmojiPickerTest` measures the rendered cell and is what holds this; it passes on API 30 and 35.

**Requirement text**
- **Fixed, further than the finding asked.** `RequirementText.kt` now does a version-name lookup:
  `"Needs $name (API $api) or newer"` when a name is known, falling back to the original API-only
  wording otherwise — the "version-name lookup table" the finding suggested as a future nice-to-have.

**Notification inspector**
- **Discoverability — fixed, by a different route than suggested.** Instead of a line of help text
  pointing at the screen by name, every component that reads notifications (`NotificationTriggers.kt`,
  `NotificationWatchdogTrigger.kt`, `NotificationControlActions.kt`) now declares
  `ComponentTool.InspectNotifications`, so an "Inspect" button sits directly on its own block in the
  editor — the tool appears where the confusion happens, not on a separate screen.
- **Circularity — fixed for the new path, unchanged on the old one.** The per-block "Inspect" button
  opens the inspector as a full-screen `Dialog` over the editor (not a navigation destination, so it
  can't discard a half-written rule), with hint text that says the Grant button is "right behind this
  screen" — a real improvement, since granting and inspecting are now on the same block. But
  `RulesScreen.kt`'s bottom-bar "Inspect" button is still there too, unchanged: it still navigates to
  the inspector as a standalone destination and still shows the original, more circular hint ("Grant
  notification access from a rule that needs it, then come back"). Both routes coexist; only the new
  one resolves the finding.

**Strings and wording**
- **`rules_empty` — fixed. This is the item the previous version of this section got wrong.** The
  string now reads "No rules yet. Add one below to get started." with a comment explaining it
  deliberately stopped quoting `rules_new`'s rendered text, for exactly the staleness reason the
  finding raised.

No new problems were found while re-checking these files, beyond the one noted above (the old,
still-circular `RulesScreen.kt` Inspect entry point sitting alongside the new, better one — not a
regression, just an old path that was never removed once the new one shipped).

---

## Rule editor — the gate and condition tree

**[significant] The condition tree still has its own "Only if" section, which an uncommitted doc
edit says was removed.** `GateEditor.kt:54` renders `SectionLabel("Only if")` as a section distinct
from "When" (triggers) and "Then" (actions) in `RuleEditorScreen.kt:238,315,335`. Its picker dialogs
are titled "Add a condition" / "Change condition" (`RuleEditorScreen.kt:420-432`), separate from
"Add trigger" / "Change trigger". This is the actual, committed, shipped state.

`docs/conditions.md`'s phase 6 — itself an **uncommitted** edit right now (`git diff docs/conditions.md`) —
claims the opposite already happened: *"An earlier build grew a separate 'Only if' section with its
own 'add a check' affordance — a second vocabulary for the same underlying thing... That section is
gone; grouping now happens under the same trigger tree, transparently."* That unification was never
built. If this doc edit is committed as-is, it will misdescribe the app from the moment it lands.
Either finish the editor unification it describes, or revert the doc claim to match what's actually
there (a version of phase 6 already existed and said exactly this — see commit `2825b3d`).

Judged purely as UX rather than as a doc-accuracy issue, the current three-section layout ("When" /
"Only if" / "Then") is not unreasonable — the labels are informative and the picker titles are
unambiguous. This is flagged mainly because it directly contradicts a stated design decision, per the
review brief's instruction to surface that.

**[Checked and fine]** Within the condition tree itself, the promote/un-promote logic
(`RuleDraft.kt`'s `addCondition`/`replaceCondition`), the path-addressing scheme, and the fold/caveat
state persistence across rotation are all careful and, as far as reading them shows, correct. A lone
condition never grows AND/OR chrome; a group that shrinks to one child un-wraps correctly; removing
the last condition clears the section rather than leaving an empty group. The first-trigger vs.
added-trigger UI distinction (no footer, no "OR" copy until there's a second edge) works as documented.

---

## Home-screen shortcut trigger — unusable end to end

**[blocker]** Picking "Home screen shortcut" as a trigger and trying to actually use it hits three
separate dead ends. (This entire feature is uncommitted work in progress — `ShortcutTrigger.kt`,
`ShortcutPinning.kt`, `ShortcutTargetActivity.kt`, `EmojiPicker.kt` are untracked new files, so this
describes the current working tree, not something already shipped to users. It's worth fixing before
any of it is committed, since right now it would ship a trigger that can never fire.)

1. `ShortcutTriggerFactory.configFields` (`triggers/.../ShortcutTrigger.kt:101-114`) declares
   "Shortcut ID" and "Shortcut label" as plain, required `ConfigField.Text` fields. The ID field's own
   help text says *"Generated automatically; identifies this rule's shortcut without needing the
   rule's own id"* — but nothing generates it. `ConfigField.Text` renders as an ordinary empty
   required text box (`ConfigFieldEditor.kt`), so the actual UI asks a user to type an opaque ID by
   hand, with no way to produce a correct one. This is exactly the "field whose valid input nobody
   could guess" trap the review brief calls out.
2. `docs/triggers.md:217-234` documents the icon as "an emoji icon chosen from a picker." The `Emoji`
   config-field kind and its `EmojiField`/`EmojiPickerDialog` (`EmojiPicker.kt`) are fully built and
   wired into `ConfigFieldEditor.kt`. But `ShortcutTriggerFactory.configFields` never declares an
   `Emoji` field (its own comment says "declared elsewhere (another agent owns it) — deliberately not
   added here", but nowhere else declares it either). Grepping the whole `:triggers`/`:actions` tree
   for `ConfigField.Emoji(` turns up zero factories. The emoji picker — a "newly built surface" the
   review brief specifically asks about — is consequently **unreachable from any real rule**; there is
   currently no way to open it during normal use.
3. `ShortcutPinning.requestPinShortcut` (`ShortcutPinning.kt:73`) is a complete, careful implementation
   — it checks `isRequestPinShortcutSupported`, renders an emoji to a bitmap icon, builds the
   `ShortcutInfoCompat`. Grepping the entire repo for callers finds none outside its own file. Nothing
   in `RulesScreen.kt`, `RuleEditorScreen.kt`, or `ComponentBlock` ever calls it — there is no "pin to
   home screen" button anywhere.

Net effect: a user could build a whole rule around this trigger, save it, and it would never fire —
silently, because nothing ever creates the launcher shortcut carrying the ID the trigger is waiting
for. This is the single worst instance of the "fails silently" failure mode the review brief says
matters most, sitting on a feature the README already advertises as delivered ("a home-screen
shortcut you tap yourself").

Suggested fix, in order: generate the shortcut ID with a UUID at the moment the trigger is added
(hide the ID field from the form entirely — nothing about it is a decision the user should make), add
an `Emoji` field to `ShortcutTriggerFactory.configFields`, and add a "Pin to home screen" action to the
trigger block's footer that calls `ShortcutPinning.requestPinShortcut` with the current label/emoji —
surfacing `PinShortcutResult.UnsupportedByLauncher` as visible text rather than doing nothing.

---

## Rules list (`RulesScreen.kt`, `RulesViewModel.kt`)

**[significant] The one-line rule summary silently drops every trigger edge but the first, and never
mentions conditions at all.** `summarise()` (`RulesScreen.kt:218-225`) builds its string from
`rule.trigger.type` — `Rule.trigger` (`core/Rule.kt:45`) is `gate.triggers.first()`, a compatibility
accessor kept for the pre-gate call sites. Once a rule has a second OR-edge trigger or an "Only if"
condition tree — both real, shipped features per `docs/conditions.md` — the list, which is the
primary place a user scans to remember what a rule does, shows only the first edge and gives no
indication anything else is gating the rule. "Doorbell → Sound alarm" in the list could actually mean
"doorbell *or* a knock, only if it's dark and I'm home, sound alarm," and the list says nothing about
the difference. The rule still works correctly — only the description of it is incomplete — but that
is exactly the kind of thing a monospaced, scannable summary line exists to prevent, and it is silent.
Fix: extend `summarise()` to append something like "+N more" when `gate.hasSeveralTriggers`, and an
"if…" marker when `conditions != null` — mirroring the "(trigger N)" / "(condition N)" labelling
`RuleEditorViewModel.validate()` already uses for the equivalent problem in the editor.

**[Checked and fine]** The empty state, `RequirementCell`'s error-colored / resolvable-only "Grant"
button, and the "a requirement that is met is not shown at all" rule are all implemented exactly as
`docs/architecture.md`'s "Requirements" section describes. Rule name uppercased as chrome, summary
line monospaced and lined up in a scannable column — both match the documented rationale, and the
summary's uppercasing is of display names (via `describeComponent`), not raw data, so it doesn't hide
meaningful casing the way, say, uppercasing a package name would.

---

## Color roles — `primary` used as text, not just as a fill

**[significant]** `Palette.kt`'s own documentation states the rule in bold: *"If you are about to
write `color = MaterialTheme.colorScheme.primary`, you want [`extra.accent`] instead. `primary`
belongs in `Surface(color = …)` and `containerColor`, never in a `Text` or an `Icon` on the page"* —
and gives the reason: `#EC6206` measures 3.23:1 on the page, which fails WCAG AA for normal text
(needs 4.5:1); `extra.accent` exists specifically as the darker, text-safe version of the same hue.
Two places break that rule:

- `NotificationInspectorScreen.kt:115-119` — the app-name label above each notification block is
  `Text(..., color = MaterialTheme.colorScheme.primary)`.
- `PatternTester.kt:191-192,195` — the "MATCHES · N HITS" / "MATCHES · NOTHING TO HIGHLIGHT" verdict
  text colors itself with `MaterialTheme.colorScheme.primary` (the "NO MATCH" / "PATTERN DOES NOT
  COMPILE" branches correctly use `colorScheme.error` right beside it, so the inconsistency is visible
  in the same function).

Both are `labelMedium` text (12sp in the default M3 scale) — the exact size the KDoc calls out as
failing contrast at this value. Fix is mechanical: swap both to `MaterialTheme.extra.accent`. Worth a
quick repo-wide grep for the same pattern before considering it closed; I checked every
`colorScheme.primary` reference across all 30 UI files and found no other instance of it applied to a
`Text` or `Icon` (every other use is a `Surface`/`background`/`containerColor`, which is correct).

---

## `CaveatBadge` — the caveat control's touch target

**[significant]** `CaveatBadge` (`Blocks.kt:475-490`) is a fixed `Modifier.size(22.dp)` box. Android's
own accessibility guidance calls for a 48dp minimum touch target; 22dp is well under half of that.
This is the *only* control that reveals a component's caveat prose anywhere in the app (picker rows,
trigger/action/condition block headers) — per `docs/architecture.md`'s "Warnings are not errors", it
is deliberately the sole way in, with nothing else acting as a fallback. A small, easy-to-miss target
being the only path to safety information (battery cost, background suppression, etc.) is a real
accessibility and usability gap. Fix: wrap it in a larger clickable region (e.g. `Modifier.minimumInteractiveComponentSize()`
or an explicit `.size(48.dp)` with the 22dp glyph centered inside), the same way Material's own
`IconButton` reserves a bigger hit area than its icon.

---

## App / Sound / Bluetooth / Notification-button pickers (`ValuePicker.kt` family)

**[significant] `AppPackageField` shows an unresolved package name twice.** `AppPicker.kt:110-120`:
`primary = apps.labelFor(packageName)`, and `labelFor` (`InstalledApps.kt:118-119`) falls back to the
raw package name itself when the app isn't found; `secondary = packageName` is set unconditionally
right below it. So for any unresolved app — an imported rule, an uninstalled app, a package typed
manually via the escape hatch — the value box renders the same string in both the "primary" and
"secondary" line. Compare `BluetoothAddressField` (`BluetoothPicker.kt:128`), which explicitly guards
this exact case with `address?.takeIf { devices.nameFor(it) != it }` so an unresolved address shows
once, not twice. Both fields are implementing the same documented decision
(`docs/architecture.md:753-757`: "a stored package always shows its label with the raw package
beneath, including when the app is not installed") — one does it correctly, its sibling doesn't. Fix:
give `AppPackageField` the same guard `BluetoothAddressField` already has.

**[polish] Single-line picker rows can land under the 48dp touch-target minimum.** `PickerRow`
(`ValuePicker.kt:150-178`) is a plain `Modifier.clickable()` Row with 12dp vertical padding and no
minimum-size enforcement. Two-line rows (primary + secondary) clear 48dp; single-line rows — the
"clear" row, "No button chosen," a sound with no distinguishing secondary text — land around 38-40dp.
`ComponentPicker.kt`'s own `ComponentRow` uses more generous padding and doesn't have this problem.
Fix: bump the vertical padding or add `Modifier.heightIn(min = 48.dp)`.

**[polish] A notification button that can't be used is selectable, not disabled.**
`NotificationButtonPicker.kt:143` marks a `takesText` button "· needs typed text, cannot be pressed",
but the row itself is still tappable, and the actual rejection only shows up later at Save (via the
factory's `create()` throwing). This matches the project's stated "validate at Save, not in the
picker" philosophy elsewhere, so it's not a bug — but it is a rougher edge than disabling the row,
since the person only learns their choice was invalid after closing the dialog.

**[Checked and fine]** The three pickers correctly share behavior through `ValuePicker.kt` as
documented. The typed-escape-hatch asymmetry is implemented exactly as `docs/architecture.md`
justifies it: Sound has none, App and Bluetooth both do, each gated by its own deliberately-loose
`looksLikeA…` check. Blank-meaning hints correctly suppress themselves on picker-kind fields (the
picker already shows the blank meaning as a value row). Dialog titles consistently strip the
required-field `" *"` suffix in three separate files. `TextPatternField`'s regex mode matches
"Matching text, and matching it loosely" point for point — live `regexErrorOrNull` on every keystroke,
highlighting only in regex mode, the Test button beside the mode toggle. `SliderField`'s fallback to
the field's default (not its minimum) on missing/unparseable data is correct. `BluetoothAddressField`
correctly distinguishes "no paired devices" from "not allowed to see paired devices."

---

## Coordinates and time fields (`CoordinatesField.kt`, `TimeFields.kt`)

**[significant] "Use where I am now" can point the user at a Grant button that will never exist.**
`CoordinatesField.kt:69-88` renders this convenience button on every `Coordinates` field
unconditionally. On a missing-permission failure it says: *"Grant the location permission below, then
try again"* (`CoordinatesField.kt:132-134`). But `SolarTriggerFactory` (`SolarTrigger.kt:159-201`)
declares **no** `ComponentRequirement` at all — its own help text says *"Typed, not sensed — which is
why this trigger needs no location permission."* Since the component has no unmet requirement,
`ComponentBlock` never renders a requirements section, so there is no "below" for this message to
point to. A user who taps the convenience button on a Solar trigger without already having granted
location access elsewhere is told to look for a control that doesn't exist on that block. Fix: either
suppress the button when the descriptor carries no location requirement, or word the failure without
promising a specific location for the fix ("Grant Trigly's location permission in system settings,
then try again").

**[significant] `DurationField` silently discards a comma decimal keystroke.** `TimeFields.kt:78-91`
uses `KeyboardType.Decimal`, which shows a comma as the decimal key on many non-English locales.
`onValueChange` only updates the stored value when `typed.toDoubleOrNull()` parses — and Kotlin's
`toDoubleOrNull()` only accepts a period, never a locale comma. Because the field is controlled
(`value = shown`, derived from the stored millis), a rejected keystroke doesn't even show up in the
box momentarily — it's as if the keypress did nothing, with no hint anywhere about the expected
format. A real, silent, locale-dependent trap. Fix: normalize `,` to `.` before parsing, or show an
inline error instead of quietly eating the character.

**[polish] Longitude and the time-of-day "time" box never get the required asterisk, latitude and
date do.** `CoordinatesField.kt:56` routes the latitude label through `fieldLabel(field.label,
field.required)` (which appends `" *"`); line 62 hardcodes `label = "LONGITUDE"` with no asterisk.
The same shape repeats in `TimeFields.kt` between the date box (via `fieldLabel`) and the hardcoded
`"TIME"` label. Since the docs justify these as one indivisible two-key field ("an hour without a
minute... [is] not half an answer"), showing the asterisk on only one half implies the other is
optional when it isn't. Fix: route both boxes through `fieldLabel(...)`.

---

## Rule-reference picker (`RulePicker.kt`)

**[significant] The picker never excludes the rule currently being edited, contradicting its own copy.**
`RulePickerDialog`'s empty-state text says *"No other rules yet. Save one first, then come back"*
(`RulePicker.kt:60`), which reads as a promise that the rule being edited is excluded from its own
picker. Neither `RulePickerDialog` nor `RuleRefField` takes an "exclude this id" parameter, and
`LocalRules` is populated from every saved rule unconditionally (confirmed at its provisioning site in
`MainActivity.kt`). So: editing a rule that has an action pointing at another rule shows the
currently-open rule itself as a choosable option, with no marker distinguishing it — a rule can be set
to turn itself on/off, silently, and the "No other rules yet" placeholder can in practice never appear
for an existing (already-saved) rule, since the list always contains at least itself. Fix: filter
`LocalRules` (or the options passed into the dialog) by the id of the rule currently open.

**[polish] "on"/"off" breaks the app's uppercase-chrome convention.** `RulePicker.kt:56,94` renders a
referenced rule's state as lowercase `"on"`/`"off"`, while `BlockToggle` (`Blocks.kt:394`) — the
control that actually represents this same state everywhere else — renders `"ON"`/`"OFF"`. `secondary`
in `ValuePicker.kt` is documented as reserved for raw identifiers precisely because those must *not*
be uppercased (a MAC address, a package name); a state word isn't one of those, so this is the one
place the convention slips. Fix: uppercase it, to match `BlockToggle`.

---

## Pattern tester and regex highlighter (`PatternTester.kt`, `RegexHighlight.kt`)

**[polish] An anchor token and an actual compile error share the same red.** `RegexHighlight.kt` colors
the `^`/`$` anchor tokens with `colorScheme.error`, which is also the color used one line above for
"PATTERN DOES NOT COMPILE" (`PatternTester.kt:189,196`). A perfectly valid, compiling pattern that uses
an anchor therefore shows the "error" color inside the box that is, at that exact moment, correctly
reporting "MATCHES". Minor — the words next to it disambiguate — but it's the kind of small tension
that undercuts a design built around "warnings are not errors" using color deliberately.

**[Checked and fine]** Everything else about the tester matches `docs/architecture.md`'s "Matching
text, and matching it loosely" precisely: the verdict is provably drawn from `TextFilter.of(...).matches`
itself rather than a re-implementation, the empty-pattern / non-compiling / zero-width-match states are
each named rather than folded into a plain yes/no, and the highlighter cannot throw on partial/invalid
input (every branch traced). `withMatchesMarked` correctly uses `primaryContainer`/`onPrimaryContainer`
for the highlight, not bare `primary`.

---

## Emoji picker (`EmojiPicker.kt`)

Covered above under "Home-screen shortcut trigger" for the reachability problem — right now, no
factory declares an `Emoji` config field, so this screen cannot be opened during normal use of the
shipped app. Read on its own terms once wired up: the picker's design (a curated grid, no search, no
typed escape hatch) is internally consistent and well-argued in its own KDoc (an emoji is chosen, not
typed, the same instinct as the sound and Bluetooth pickers). One thing worth spot-checking once it's
reachable: `EmojiCell` (`EmojiPicker.kt:136-156`) sizes each cell as roughly (dialog width ÷ 6) minus
padding, which on a typical phone width is plausibly under the 48dp touch-target minimum — this
couldn't be confirmed without measuring a rendered layout, so flagging as a **polish**-level thing to
check rather than a confirmed bug.

---

## Requirement text (`RequirementText.kt`)

**[polish] Some requirement wording assumes technical literacy.** `"Needs Android API $api or newer"`
names an API level a typical user has never seen associated with their phone (most people know "Android
14," not "API 34"). This is low-severity because it's only shown for permanent, unresolvable blockers —
there's no dead "Grant" button attached, just a slightly jargon-heavy explanation of something the user
can't act on anyway. Worth a version-name lookup table if this screen gets revisited.

---

## Notification inspector (`NotificationInspectorScreen.kt`)

Aside from the color-role bug already covered above, this screen matches its design brief closely: the
two empty states (notification access not granted vs. granted-but-nothing-posted) are clearly and
separately worded, the "no history" limitation is stated rather than left implicit, and the "Text
filters match" field is correctly sourced from the same `notificationHaystack` function the matcher
itself uses, per `docs/architecture.md`'s description of the screen.

**[polish] Reaching a *working* inspector can feel circular.** When notification access isn't granted,
the screen says "Grant notification access from a rule that needs it, then come back" — accurate, and
consistent with the app's requirement model, but it means the tool that helps you debug a notification
rule can only be unlocked by first building a notification rule far enough to hit its own Grant
button. Not a bug, just a friction point worth knowing about if a more direct grant path is ever added.

**[polish] "Inspect" as a label doesn't hint at what the screen is for.** It's always visible in the
rules list's bottom bar, so it isn't hidden — but nothing else in the app (no trigger's help text, no
caveat) tells a user stuck on a silently-non-firing notification rule that this screen exists to
explain why. Consider a line in the notification-trigger family's help text pointing at it by name.

---

## Strings and wording (`strings.xml`)

**[polish] `rules_empty`'s copy hardcodes another string's rendered text.** `rules_empty` reads *"No
rules yet. Tap "NEW RULE" to make one."* — the quoted "NEW RULE" is a literal copy of what
`rules_new` ("New rule") looks like once `BlockButton` uppercases it, not a reference to that
resource. Currently accurate, but fragile: if `rules_new`'s wording or casing convention ever changes,
this string has no mechanism to follow it and would quietly go stale. Low stakes given the string
count in this file (27 lines total, mostly independent), but worth a comment noting the coupling if
it's not going to be fixed outright.

**[Checked and fine]** The rest of `strings.xml` is small and consistent: the engine's ongoing
notification strings are deliberately sentence-case (with a comment explaining why — it's read in the
system shade, where the app's own uppercase-chrome convention doesn't apply), and `engine_watching`'s
plural correctly reflects the count of rules actually running rather than the count merely enabled.

---

## Navigation, activity, boot, and engine plumbing

`MainActivity.kt`, `TriglyApp.kt`, `Screen.kt`, `OnFreshEntry.kt`, `BootReceiver.kt`, `EngineService.kt`,
`Theme.kt` — checked, no findings. `backTarget`, `ScreenSaver`, and the new-rule-draft-reset-on-entry
mechanism all work as `docs/architecture.md`'s "Navigation" section describes; the sole `IconButton` in
this whole surface (the editor's back arrow) carries a `contentDescription`; the foreground-service
notification strings report the count of rules actually running, not merely enabled, matching what the
copy claims. `Theme.kt` declares one corner radius for all five Material shape roles, exactly as
documented.

---

## Severity summary

| Severity | Count at time of sweep | Fixed now | Still open | No longer applicable |
|---|---|---|---|---|
| Blocker | 1 | 1 | 0 | 0 |
| Significant | 8 | 8 | 0 | 0 |
| Polish | 10 | 10 | 0 | 0 |

All 19 tagged findings are fixed, several by a materially different route than the fix each one
suggested (noted case by case in "Status" above). The last of them — whether `EmojiCell` clears the
48dp touch-target minimum, the one item the sweep itself could not confirm "without measuring a
rendered layout" — was settled by measuring it: 32.8dp, and now fixed with a test that measures
rather than reasons.

**What I'd double-check next, now that the list above is mostly closed:**

1. **The other pickers' rows were not measured.** App, Bluetooth and Sound rows are full-width rather
   than divided into columns, so the emoji grid's failure mode is unlikely there — but "unlikely" is
   what the emoji cell was until someone measured it. `PickerRow` now carries an explicit 48dp
   minimum; a test that measures one would be the thing that keeps it.
2. **The now-duplicated notification-inspector entry points.** `RulesScreen.kt`'s bottom-bar "Inspect"
   button still opens the inspector as a standalone destination with the original, more circular
   hint text, alongside the new per-block "Inspect" button that opens it as a dialog with a better
   hint. Not a regression, but worth a deliberate decision on whether the old entry point should be
   retired, reworded to match, or left as a second, faster route for someone who isn't mid-edit.
3. **Nothing else outstanding from this sweep** — every tagged finding was verified fixed against the
   code, `d2fc1bf` for eighteen of them and the emoji cell on top.
