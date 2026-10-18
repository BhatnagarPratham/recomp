# Recomp: Technical Requirements Document

*Draft v1.1, 2026-10-18: the MVP as built, plus the v1.1 plan (§10). Implements [PRD.md](PRD.md) v1.1. Requirement IDs (WL-02, PO-05, PS-03…) refer to the PRD.*

## 1. Scope and constraints

- One user per install, on Android 14 or later. Each person builds the app (or installs a release APK) and brings their own AI key. No Play Store listing needed.
- **Offline-first.** Only AI features and Open Food Facts lookups (barcode and search) need the network.
- **AI is optional.** With AI off or unreachable, every feature still works (PRD §7.12). No code path may wait on AI without a fallback.
- **No backend.** Everything lives in a local SQLite database. Backups are files you control.
- The main design constraint is gym use: one hand, between sets, screen often locked. Tap count and reliability matter more than visual polish.
- **Configuration over code (PS-03).** Everything that varies by person comes from the persona (§5.0). Engines take policies as parameters and never branch on preset names.

## 2. Tech stack

| Concern | Choice | Notes |
|---------|--------|-------|
| Language | Kotlin 2.x | |
| UI | Jetpack Compose, Material 3 | Dark theme by default. |
| Pattern | Single activity, MVVM with unidirectional data flow | Each ViewModel exposes `StateFlow<UiState>`. Screens send intents. |
| Navigation | Navigation Compose, type-safe routes | |
| Dependency injection | Hilt | |
| Storage | Room (SQLite) for data; DataStore for settings | |
| Async | Coroutines, Flow | |
| Background work | WorkManager; a foreground service for the active workout | |
| Charts | Compose `Canvas` | M4's weight chart is a small hand-drawn chart (dots and a line), so it needs no library. The v1.1 charts reuse it as a shared component (M8). Vico is the candidate only if a chart needs pan and zoom. |
| Health data | Health Connect (`androidx.health.connect:connect-client`) | P1. Steps come from whichever app writes them; §6.3. |
| On-device OCR | An open-source on-device recogniser (e.g. Tesseract), picked after a trial on real labels | P1 (M18). Nutrition labels, without AI. Not ML Kit: it sends usage metrics to Google, which §7 doesn't allow (decided 2026-10-18). |
| Widget | Jetpack Glance | P1 (M12). |
| AI | Anthropic Java SDK (`com.anthropic:anthropic-java`); from M7, OpenAI, Gemini and OpenAI-compatible endpoints over OkHttp | Kotlin uses the Java SDK. Builds and passes R8 (M0). Its class-based structured outputs don't work on Android (§6.2). The other providers need no SDK: kotlinx.serialization and hand-written schemas, behind one `AiProvider` interface (M7, AI-05). |
| Barcode | CameraX with ZXing (on-device) | P1 (M18). ML Kit is ruled out for the same reason as OCR. |
| Serialization | kotlinx.serialization | Seed data and export files. |
| Secrets | Android Keystore with Tink | Encrypts the API key. Jetpack Security Crypto is deprecated, so not used. |
| Images | Coil | |
| Build | Gradle Kotlin DSL, version catalog, KSP | |
| SDK levels | `minSdk 34`, `compileSdk 37`, `targetSdk 37` (current AndroidX libraries require compiling against 37) | Android 14+ has Health Connect built in, and the newer foreground-service rules apply. |

**Why native Kotlin rather than Flutter or React Native:** the hard parts of this app are Android-specific: a foreground service with a lock-screen rest timer, Health Connect, home-screen widgets and notifications. Native makes all of them first-class, and there's no second platform to share code with.

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ ui        Compose screens + ViewModels (StateFlow<UiState>)  │
├──────────────────────────────────────────────────────────────┤
│ domain    pure Kotlin, no Android imports                    │
│           persona: Persona · GoalPolicy · ExperiencePolicy   │
│           models · ProgressionEngine · NutritionCalculator   │
│           TrendCalculator · AdaptiveTdee · use cases         │
│           repository + MealEstimator interfaces              │
├──────────────────────────────────────────────────────────────┤
│ data      Room DAOs · DataStore · ClaudeMealEstimator        │
│           HealthConnectSource · FoodDb · BackupManager       │
└──────────────────────────────────────────────────────────────┘
          service/ActiveWorkoutService   work/ (WorkManager jobs)
```

**Modules:** `:app` (ui, data, service, DI) and `:domain` (a pure Kotlin/JVM library). The engines carry the app's real logic. Keeping them in a JVM module means they're tested in milliseconds with no emulator. Don't split further unless build times start to hurt.

```
app/src/main/java/…/recomp/
  ui/{onboarding,today,workout,routines,exercises,nutrition,body,progress,settings,components,theme}
  data/{db,repo,seed,ai,food,health,backup,prefs}
  workout/ActiveWorkoutService.kt, RestTimer.kt
  assets/seed/{exercises,split_templates}.json
  work/{BackupWorker,HealthSyncWorker}.kt   reminders/ (AlarmManager, M12)
domain/src/main/kotlin/…/recomp/domain/
  persona/  model/  training/  progression/  nutrition/  trend/  usecase/
```

## 4. Data model (Room)

**Conventions**

- **Lift loads and body weight are stored as integer grams** (42.5 kg → `42500`). Comparisons in the progression engine are then exact, with no floating-point surprises.
- **Time** is stored two ways: `*_at` holds epoch milliseconds (UTC), and `log_date` holds an ISO `LocalDate`. `log_date` is computed once, at write time, using the "day starts at" setting (PR-03). Every daily rollup groups by `log_date`.
- **Food log rows snapshot their macros.** Editing a food later never rewrites history.
- **Referenced rows are archived, never deleted.** This applies to exercises and foods.
- `exportSchema = true`, with the schema JSON checked into git. Every schema change ships a tested `Migration`. **Destructive migration fallback is never enabled.**

```text
exercise            id, name, primary_muscle, secondary_muscles[], equipment, mechanic(COMPOUND|ISOLATION),
                    movement_pattern, load_type(WEIGHTED|BODYWEIGHT|BODYWEIGHT_PLUS|ASSISTED),
                    increment_g, default_rep_min, default_rep_max, default_rest_sec, is_unilateral,
                    setup_notes, is_custom, archived,
                    seed_key?             -- unique; ties a row to assets/seed/exercises.json
                    stall_reset_at?, stall_reset_keeps_baseline   -- PO-05 Hold or Deload (§5.1)
routine             id, name, kind(STRENGTH|YOGA|MOBILITY|CARDIO|SPORT), rotation_index, archived
                                          -- STRENGTH rotates; the other kinds are extra sessions
routine_exercise    id, routine_id, exercise_id, position, target_sets, rep_min, rep_max, rest_sec,
                    scheme(DOUBLE_PROGRESSION|REPS_ONLY|MANUAL), superset_group?
workout_session     id, routine_id?, kind(as routine), status(ACTIVE|COMPLETED|DISCARDED),
                    started_at, ended_at?, log_date, notes?, hc_record_id?
session_exercise    id, session_id, exercise_id, position,
                    target_sets, rep_min, rep_max, rest_sec   -- copied from the routine at start (RT-03)
                    notes?,
                    recommendation_json?  -- engine output frozen at workout start (M2)
                    user_overrode         -- PO-07
                    superset_group?       -- RT-05: copied from the routine at start, so grouping mid-workout
                                          -- leaves the template alone (M6); consecutive rows with one number
set_entry           id, session_exercise_id, set_index, set_type(WARMUP|WORKING|DROP|FAILURE),
                    load_g?, reps?, rir?, completed_at?
                                          -- rows exist from the start; null load/reps = not entered (ghost)
personal_record     id, exercise_id, session_id, type(MAX_LOAD|REPS_AT_LOAD|E1RM|SESSION_VOLUME),
                    value,                -- grams, reps, grams or gram-reps by type
                    load_g?, set_entry_id?  -- no single set for SESSION_VOLUME
                    achieved_at
yoga_detail         session_id (PK), style, duration_min, intensity?, focus_areas[], poses[]
                                          -- details for every extra-session kind, not only yoga
body_weight         id, log_date, measured_at, weight_g, source(HOME_SCALE|SCAN|GYM_SCALE),
                    scan_id?              -- the scan that recorded it (IB-01); deleted with the scan
body_composition    id, measured_at, log_date, source(MANUAL|SHARE_AI|HEALTH_CONNECT), weight_g,
                    skeletal_muscle_g, body_fat_g, body_fat_pct, visceral_fat_level, bmr_kcal?,
                    ecw_tbw_ratio?, inbody_score?, segmental_lean_json?,
                    mins_since_meal, workout_before_scan, mins_from_slot?, protocol_ok, image_path?, notes?  -- IB-01/02
measurement         id, log_date, measured_at, type(WAIST|CHEST|ARM|THIGH|HIP), value   -- cm; YG-04 adds mobility types
progress_photo      id, log_date, pose(FRONT|SIDE|BACK), file_path   -- app-private storage
food                id, name, brand?, source(SEED|INDB|CUSTOM|AI|OFF|LABEL_OCR), barcode?,
                    source_key?           -- unique: "seed:paneer", "indb:ASC001"; ties a row to its bundled file
                    diet(VEGAN|VEGETARIAN|EGG|FISH|MEAT), region(INDIAN|INTERNATIONAL)?   -- null = every region
                    kcal_100g, protein_100g, carbs_100g, fat_100g, fiber_100g,
                    caveat(FRYING_OIL)?, use_count, last_used_at?, archived, created_at
food_fts            FTS4 over food.name, external content: Room's triggers keep it in sync with food
food_unit           id, food_id, label("roti","katori","bowl"…), grams, calibrated   -- unique (food_id, label)
food_log            id, log_date, meal_slot (a slot key, §6.4), food_id?, name, quantity, unit_label, grams?,
                    kcal, protein_g, carbs_g?, fat_g?, fiber_g?, origin(HOME|ORDERED|PACKAGED),
                    source(MANUAL|SAVED_MEAL|QUICK_ADD|AI_TEXT|AI_PHOTO|LOCAL_PARSE|LABEL|BARCODE),
                    ai_request_id?, logged_at
                                          -- null carbs, fat or fibre = unknown (quick add); a copy keeps its source
meal_draft          id, created_at, log_date, meal_slot, text, photo_path?, origin,
                    reason?               -- AI-02: estimate later; reason = why it's a draft (a failure kind, §6.2)
saved_meal          id, name, default_slot, use_count, last_used_at?, created_at
saved_meal_item     id, saved_meal_id, position, food_id?, name, quantity, unit_label, grams?,
                    kcal, protein_g, carbs_g?, fat_g?, fiber_g?, origin
                                          -- with a food, logging recomputes from it; without one (quick add), from the snapshot
nutrition_target    id, effective_from (unique: one version per day), goal, kcal, protein_g, fat_g, carbs_g,
                    tdee_estimate, bmr_kcal, reason(INITIAL|CHECKIN|MANUAL), created_at
                                          -- protein_g is the full target; the ramp (§5.3) is applied on read
weekly_checkin      week_start (PK, the Monday), computed_at, window_days, logged_days, weigh_ins,
                    kcal_mean?, trend_start_g?, trend_end_g?, tdee_obs?, tdee_prev, tdee_new,
                    flag?(LOSS_SLOW|FLAT|DRIFT|GAIN_SLOW|GAIN_FAST), proposed_kcal?,
                    decision(PENDING|ACCEPTED|ADJUSTED|KEPT|LAPSED), decided_at?, target_id?
                                          -- NU-05 (§5.4): one row per week; decided rows are frozen;
                                          -- target_id is the version Accept or Adjust wrote (a same-day change replaces it);
                                          -- flag and proposed_kcal use the goal and body data as they were when the week was
                                          -- computed: weeks caught up together are judged by that day's goal, and the ones that
                                          -- close in the same catch-up keep no proposal
daily_activity      log_date (PK), food_incomplete, steps?, steps_synced_at?, sleep_hours?
                                          -- food_incomplete from the Food tab's day menu (M5); steps from HC, sleep typed in
ai_request_log      id, created_at, kind(MEAL_TEXT|MEAL_PHOTO|LABEL|INBODY|FIT_CHECK|COACH), model,
                    input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost_usd,
                    status (§6.2), latency_ms, response_json?   -- AI-03 spend + debugging; tokens summed over every attempt;
                                          -- written PENDING at an upper-bound cost before sending, then settled
```

**Seed data.** The ~80-exercise library and the split templates are JSON assets, parsed with kotlinx.serialization. Seeding runs on every launch and inserts only exercises whose `seed_key` is missing, so a fork can add exercises and existing installs pick them up. Each seed entry has a rep class (`COMPOUND | ISOLATION | HIGH_REP`, PRD §4.3) that sets its default rep range from the experience policy. Templates reference exercises by seed key and list the walkthrough's alternatives.

**Settings** live in DataStore, one key each:
- `persona`: the persona (§5.0) as JSON, including the goal plan, the training part (split, experience, strength days, extra-session schedules by routine id) and `onboarded_on`. Never body data, so PS-06 can export it as is.
- `profile`: body data from onboarding (sex, date of birth, height, current weight, activity level).
- `day_start_minutes` (PR-03), `keep_screen_on`, `target_overrides` (PR-02) and `protein_ramp` (§5.3).
- `meal_slots` (NU-10), `portion_sizes` (household measures, NU-12) and `food_data_version` (§6.4).
- `ai_mode` (`OFF|ON`), `ai_model`, `ai_monthly_cap_usd` (absent = no cap) and `ai_notice_seen` (§6.2). The API key is not here: it lives encrypted in its own `secrets` DataStore file.
- `scan_slot` (IB-02): the usual scan weekday and time of day.
- From v1.1, each milestone lists its own keys (§10). **Device state** (the reminder ledger, Health Connect change tokens, the backup folder's address) lives in its own `device` DataStore file, which exports and Android's own backup leave out, so a restored or transferred phone starts it fresh.

M1 and M2 stored a `TrainingSettings` JSON under `training`. A DataStore `DataMigration` moves it into `persona` and `keep_screen_on` on first read after the upgrade and deletes the old key. The install then has a training setup but no goal or profile, so onboarding opens, pre-filled from it (PRD §7.1).

A stored JSON value that no longer decodes (for example one saved by a newer build) reads as its default, so onboarding opens. It is never written over: before any write, it's copied untouched to `<key>_unreadable`, and the app says so once at launch. The API key is stored separately and encrypted (§6.2). `nutrition_target` rows also record the goal they were computed for, so history survives goal switches (PS-02, PS-04).

**Indexes:** `set_entry(session_exercise_id)`, `session_exercise(exercise_id)`, `workout_session(status, started_at)`, `food_log(log_date)`, `food_log(food_id)`, `food_unit(food_id, label)` (unique), `saved_meal_item(saved_meal_id)`, `meal_draft(log_date)`, `ai_request_log(created_at)`, `measurement(type, log_date)`, `body_composition(measured_at)`, `body_weight(log_date)`, `body_weight(scan_id)`. Food search uses an `@Fts4` table over `food.name`, filtered by `diet` and `region` (§6.4).

**v1.1 additions,** each landing with its milestone (§10), which updates this section when it merges:
- New tables: `programme_review` (M11), `scan_draft` (M13), `recipe` and `recipe_ingredient` (M14), `progress_photo` (M17, as above plus `taken_at`).
- New columns: `ai_request_log.provider` (M7), `exercise.deload_pending` (M11), `daily_activity.sleep_source` (M13).
- New enum values (stored as their names, so no migration): `TargetReason` `REVIEW` (M11), `BREAK` and `RESUME` (M19); `AiKind.GOAL` (M19); `FoodSource.RECIPE` (M14); the mobility `MeasurementType`s (M17).

**Hot query.** The query that runs most is "last N completed exposures of exercise X". It goes `session_exercise` → `workout_session WHERE status = COMPLETED ORDER BY started_at DESC LIMIT N`, skipping exposures with no completed sets (a skipped exercise), then pulls the sets for each.

## 5. Core algorithms (`:domain`)

### 5.0 Persona model (PRD §4, PS-01 to PS-03)

```kotlin
data class Persona(
    val goal: GoalPlan,                // policy + pace + goal weight + finish line + start date (§5.3, §5.7, §5.8)
    val experience: Experience,        // BEGINNER | INTERMEDIATE | ADVANCED; ExperiencePolicy.defaultFor (below)
    val diet: Diet,                    // VEGAN | LACTO_VEGETARIAN | EGGETARIAN | PESCATARIAN | NON_VEGETARIAN
    val region: FoodRegion,            // INDIAN | INTERNATIONAL: food database, portion units, AI conventions
    val split: SplitTemplate,          // PPL | FULL_BODY_AB | UPPER_LOWER | CUSTOM: seeds routines
    val strengthDaysPerWeek: Int,
    val extras: List<ExtraSession>,    // name, kind and weekdays, e.g. a yoga class on Saturdays
    val weightSource: WeightSource,    // HOME_SCALE | SCAN | GYM_SCALE
    val units: Units,                  // METRIC | IMPERIAL (display only; metric only until v1.1)
    val proteinRamp: Boolean,          // NU-03; defaults on for vegan, lacto-vegetarian and eggetarian
)

data class GoalPlan(
    val policy: GoalPolicy,            // FAT_LOSS | RECOMP | MAINTAIN | LEAN_BULK with editable rate and protein
    val pace: Pace?,                   // GENTLE | STANDARD | AGGRESSIVE; null for Maintain or custom values under Advanced
    val goalWeightKg: Double?,         // the one goal weight: protein's reference weight whatever the finish line
    val finishLine: FinishLine?,       // GoalWeight | BodyFat(pct) | ByDate(date); null = none
    val startedOn: LocalDate,
    val aggressiveAcknowledged: Boolean,
)

data class Profile(                    // body data (PR-01); kept apart from the persona so PS-06 never exports it
    val sex: Sex, val birthDate: LocalDate, val heightCm: Double, val weightKg: Double,
    val activity: ActivityLevel,       // SEDENTARY ×1.2 | MODERATE ×1.375 (default) | ACTIVE ×1.55
)

data class ExperiencePolicy(
    val level: Experience,
    val compoundReps: IntRange, val isolationReps: IntRange, val highRepReps: IntRange,
    val stallWindow: Int,               // +1 in a deficit
    val allowsDoubleJump: Boolean,
    val deloadEveryWeeks: IntRange?,    // null = only on a stall
)
```

- `Profile.weightKg` is the current weight typed in onboarding or Settings. From M4, once the primary source has a weigh-in, the trend weight (§5.2) replaces it wherever targets are computed: `PersonaRepository` previews and saves with it, and About you shows it. Weigh-ins never write a target version on their own.
- **Onboarding draft.** Presets and onboarding work on a `PersonaDraft` whose fields are nullable. A preset's *Asked* values are null, and the review step can't finish until each is set. `PersonaDraft.complete()` gives the `Persona`.

| Experience | Compound / isolation / high-rep reps | Stall window | Double jump | Deload |
|------------|--------------------------------------|--------------|-------------|--------|
| Beginner | 8–12 / 10–15 / 12–20 | 3 | Yes | On a stall only |
| Intermediate | 6–10 / 10–15 / 12–20 | 4 | No | Every 6–8 weeks |
| Advanced | 5–8 / 8–12 / 10–15 | 5 | No | Every 4–6 weeks |

- **Presets** bundle a `PersonaDraft` (PRD §4.4). They're Kotlin objects (`Presets`) in the MVP and JSON files under `app/src/main/assets/presets/` from v1.1 (PS-05), so a fork can add one without code. Every preset weighs in on a home scale, BM-01's default; the reference preset's scans are for body composition.
- **Who reads the persona:**
  - Calorie and protein targets read `goal` (§5.3).
  - The progression engine reads `experience` (§5.1).
  - Food search filters on `diet` and `region` (§6.4).
  - AI prompts are built from `diet` and `region` (§6.2).
  - The weekly plan reads `split` and `extras`.
- **No other code branches on persona values.**
- **Persona changes take effect from that day on.** A change that moves the targets writes a `nutrition_target` row effective today; a second change the same day replaces that row. Earlier rows are never rewritten (PS-02).
- **Experience, split and extras** change through Training setup (`TrainingRepository.applySetup`), because they also re-apply rep ranges and rebuild or swap routines.

### 5.1 Progression engine (PO-01 to PO-08)

**Definitions.** An *exposure* is one completed session's working sets for one exercise. The *top load* is the heaviest working load in that exposure. Only working sets at the top load count toward progression; back-off sets are ignored.

```kotlin
fun recommend(cfg: ExerciseConfig, history: List<Exposure> /* newest first */, ctx: Context): Recommendation

// cfg: repMin, repMax, targetSets, incrementG, loadType, scheme
// ctx: inDeficit (true when the current target is below estimated TDEE), experience (ExperiencePolicy, §5.0),
//      bodyWeightG (today's trend weight, read for assisted lifts only; null before the first weigh-in)

if (history.isEmpty())           -> BASELINE   "Pick a weight you could do for ${repMax} with ~2 reps left"
val last = history[0]
val jumpRatio  = cfg.incrementG / last.topLoadG
val effRepMax  = cfg.repMax + if (jumpRatio > 0.10) 2 else 0          // PO-06 big-jump guard
val allAtTop   = last.workingSets.size >= cfg.targetSets && last.workingSets.all { it.reps >= effRepMax }
val missedBottom = last.workingSets.any { it.reps < cfg.repMin }

when {
  allAtTop -> INCREASE   load = roundToStep(last.topLoadG + inc), reps = repMin × targetSets
              // PO-08 (M6): inc = 2 × incrementG if ctx.experience.allowsDoubleJump, every top set had RIR ≥ 3 and jumpRatio ≤ 0.05
  missedBottom && history.getOrNull(1)?.let { it.topLoadG == last.topLoadG && it.missedBottom } == true
           -> DELOAD     load = roundDownToStep(last.topLoadG × 0.9), reps = repMin × targetSets
  missedBottom -> REPEAT  same load, target = repMin on every set
  else     -> ADD_REPS   same load, target = last reps with +1 on the first set below effRepMax
}

// Stall check, layered on top of any action above (PO-05)
val window = ctx.experience.stallWindow + if (ctx.inDeficit) 1 else 0   // §5.0 experience policy
if (history.size > window && history.take(window).maxOf { it.bestE1rm } <= history.drop(window).maxOf { it.bestE1rm })
    rec.stall = StallOptions(DELOAD_10, SWAP, HOLD)   // offered, never auto-applied; Swap opens the picker (same-movement first from M6, EX-04)
```

- **Load types.** `BODYWEIGHT` uses the `REPS_ONLY` scheme: reps climb to `repMax`, then the engine suggests a harder variation or adding load. `BODYWEIGHT_PLUS` (e.g. weighted dips) progresses the added load; with no load yet the jump ratio is unbounded, so the big-jump guard asks for `repMax + 2` before the first plate. `ASSISTED` (M6) progresses by *reducing* the assistance load (below).
- **Assisted lifts (WL-09, M6).** The load is the assistance, so the *top* sets are those with the least of it.
  - `INCREASE` takes one step off (two with the double step), never below zero. At zero it suggests added load or a harder variation, like bodyweight.
  - The big-jump guard and the double step measure the step against the *effective load*: today's trend weight (§5.2) minus the assistance. Before the first weigh-in that's unknown, so neither applies and the lift moves one step at a time.
  - `DELOAD` adds the assistance that takes about 10% off the effective load, rounded *up* to the step; before a weigh-in, one step.
  - e1RM, PRs, the stall score and session volume use today's trend weight minus each set's assistance (`Loads.lifted`). Every session is judged at the same body weight, so in effect on assistance and reps, and a cut doesn't read as a stall.
  - Before the first weigh-in, less assistance is the "least assistance" record, ties go to more reps, and there's no e1RM or volume. Records keep the assistance in `load_g`.
  - So an assisted MAX_LOAD record's `value` changes meaning at the first weigh-in: the assistance before it, trend weight minus assistance after. Charts and anything else that reads assisted records use `load_g` (or recompute from the sets), never `value`.
  - With no assistance left, targets and history read "BW", and the next step is added load or a harder variation.
- **Double step (PO-08, M6).** Only the top sets need 3+ reps in reserve; a set logged without RIR doesn't count. The 5% limit keeps dumbbells and light isolation work to one step. The target line says why: "weight up (2 steps: every set had 3+ reps left)".
- **Steps.** `INCREASE` adds one increment to the top load. `DELOAD` takes 90% of the top load and rounds *down* to a multiple of the increment. Targets list one rep count per target set; `ADD_REPS` pads a short last exposure with its last set's reps.
- **Stall metric.** The best Epley score over every working set, with no rep cap. It's a poor absolute 1RM above 12 reps, but as a progress measure it's consistent between sessions and rises with reps; excluding or capping high-rep sets made 12–20 rep work look stalled while reps climbed. Bodyweight sets use reps. Displayed e1RMs and E1RM PRs still use sets of 12 reps or fewer.
- **Hold and Deload (PO-05).** Both store a reset point on the exercise: the start of the session where you chose. After a *Deload*, stall detection runs only on exposures after the reset, with the usual rule. After a *Hold*, the card returns once at least `window` exposures follow the reset and none beats the best from before it.
- **In a deficit.** `ctx.inDeficit` compares the target in force today with the TDEE estimate stored on the same `nutrition_target` row: true when the target sits below the ±5% maintenance band around TDEE (§5.3). With no target yet, it's false.
- **Rounding.** `roundToStep` uses the exercise's increment as the equipment step (e.g. dumbbells at 2.5 kg). With 1.25 kg plates, the barbell step is 2.5 kg total. Load is always per dumbbell, never the pair.
- **Estimated 1RM** uses Epley: `load × (1 + reps / 30)`. It's computed only for sets of 12 reps or fewer. Higher-rep sets still count for rep PRs.
- **PRs** are computed once, when the workout finishes, and written to `personal_record`. At most one PR of each type per exercise per session.
- **Freezing the recommendation.** It's written to `session_exercise.recommendation_json` when the workout starts, or when an exercise is added or swapped in mid-workout, as kotlinx.serialization JSON (`action`, `loadG`, `reps[]`, `stalled`, `harderVariation`, and from M6 `steps` and `assisted`, which default to 1 and false). The summary then grades against what you were actually shown, even if the history changes later. Choosing *Deload* or *Hold* rewrites it for that session. Working sets' ghost values come from it (PRD §7.4).
- **Overrides (PO-07).** `user_overrode` is true when the session's top completed working load (for an assisted lift, its least assistance) differs from the target load. It's recomputed whenever a set is ticked, unticked, retyped or deleted, so back-off sets and corrected typos don't count.
- **PRs.** Compared with every earlier completed set of the exercise. None are written for an exercise's first exposure.

### 5.2 Weight trend (BM-01)

Only weigh-ins from the primary source (BM-01) feed the trend. Readings from other sources are plotted as dots but left out, because each scale and setting reads differently.

```
Δd     = days since the previous primary weigh-in
α_eff  = 1 − (1 − 0.1)^Δd          // 0.10 for daily data, ≈ 0.52 for weekly
trend  = trend_prev + α_eff × (w − trend_prev)
```

The smoothing accounts for the gap between weigh-ins, so daily and weekly data both give a sensible trend. A weekly InBody reading moves the trend about halfway toward it, the same effect seven daily readings at that weight would have. The trend is seeded with the first weigh-in. Weekly rate = `trend_today − trend_7_days_ago`, interpolated between points and also shown as a percentage of body weight. The UI plots weigh-ins as dots and the trend as a line.

Implemented in `TrendCalculator` (`:domain`, `trend/`):
- **One reading per day.** Only the earliest primary weigh-in of each `log_date` counts, so `Δd` is a whole number of log days, at least 1. Later weigh-ins that day are plotted but excluded, like other sources.
- **The rate** is anchored at the latest primary weigh-in, not at today, so a gap in weighing doesn't read as a flat week. It's `trend(last) − trend_at(last − 7 days)`, where `trend_at` interpolates linearly between trend points. It's null until the first point is at least 7 days before the last.
- **Percentage** = rate ÷ the latest trend × 100.
- **The goal's pace** (`WeightPace.of(policy)`) is in % of body weight a week:
  - fat loss and recomp: `−lossRatePctPerWeek`;
  - lean bulk: 0.25 to 0.5 (PRD §4.2's success range);
  - Maintain: 0.
- **Storage.** Weigh-ins are integer grams. The trend is computed on read and never stored, so a changed primary source or an edited weigh-in recomputes it.

### 5.3 Starting targets (NU-01 to NU-04, IB-05)

```
BMR    = Katch-McArdle when an on-protocol body-composition scan ≤ 60 days old exists (IB-05, M15):
             370 + 21.6 × lean_kg              // lean_kg = scan weight − body fat mass
         otherwise Mifflin-St Jeor:
             10·kg + 6.25·cm − 5·age + 5   (male)   | … − 161 (female)
TDEE   = BMR × activity factor   (1.2 sedentary · 1.375 desk job + 3–4 sessions [default] · 1.55 active)
adjustment (from the goal policy and pace, PRD §4.2):
    FAT_LOSS, RECOMP:  −min(rate% × trend_kg × 7700 / 7,  25% of TDEE)
    MAINTAIN:          0
    LEAN_BULK:         +min(surplus fraction, 15%) × TDEE

pace → rate                 Gentle    Standard   Aggressive
    FAT_LOSS (% BW/week)      0.5       0.75       1.0
    RECOMP   (% BW/week)      0.25      0.5        0.75
    LEAN_BULK (% of TDEE)     5         10         15
    CUSTOM: any value under Advanced, still capped
kcal   = max(TDEE + adjustment, BMR, 1500 male / 1200 female), rounded to 10
reference_kg = goal weight if set, else current trend weight
protein_g = goal policy g/kg × reference_kg, then ramped (below)
fat_g  = max(0.25 × kcal / 9, 0.6 × reference_kg)
carbs_g = (kcal − 4·protein_g − 9·fat_g) / 4
```

Implemented in `NutritionCalculator` and `GoalPolicy` (`:domain`).

**Golden test vectors** (illustrative inputs, not anyone's real data):

| Vector | Inputs | BMR | TDEE | Adjustment | kcal | Protein | Fat | Carbs |
|--------|--------|-----|------|------------|------|---------|-----|-------|
| A: recomp, Standard | male, 30 y, 175 cm, 95 kg, goal 78 kg, ×1.375 | 1899 | 2611 | −523 | 2090 | 125 | 58 | 267 |
| B: recomp, Aggressive + scan | as A at 0.75%/week, scan lean mass 66 kg (Katch-McArdle); deficit capped at 25% <!-- pii-ok: illustrative test vector --> | 1796 | 2469 | −617 | 1850 | 125 | 51 | 222 |
| C: lean bulk, Standard | male, 30 y, 175 cm, 70 kg, no goal weight, ×1.55 | 1649 | 2556 | +256 | 2810 | 112 | 78 | 415 |
| D: fat loss, Standard | female, 35 y, 165 cm, 80 kg, goal 65 kg, ×1.375; deficit capped | 1495 | 2056 | −514 | 1540 | 130 | 43 | 159 |
| E: maintain | as A, goal Maintain | 1899 | 2611 | 0 | 2610 | 125 | 73 | 365 |

**Exercise calories are never added to the food budget.** The activity factor, and later the adaptive TDEE, already include training.

**Inputs from the persona.** `TargetInputs.from(profile, goalPlan, today)` takes age on today's date, height, current weight, the activity factor, the goal policy and `goalWeightKg`. Lean mass is null until M4 records scans (IB-05).

**Overrides (PR-02).** `TargetOverrides(kcal?, proteinG?, fatG?)` replace the computed value for that macro and stay until reset. Carbs always fill the remaining calories and are never overridden. An overridden `kcal` is still held at the floor. When `kcal` is overridden but fat isn't, fat is recomputed from the new `kcal`. Carbs never go below 0.

**Explanation.** `NutritionTargets` also returns what each number came from: the BMR formula, the activity factor, whether the deficit cap or the floor applied, the reference weight and whether it's the goal weight, and whether fat came from its 25%-of-calories share or its 0.6 g/kg floor. Each number on the Targets screen is one sentence built from these.

**Maintenance band and "in a deficit".** `EnergyBalance.band(kcal)` is ±5% of a number, rounded to 10. It gives Maintain's range on Today (PRD §4.8). `EnergyBalance.inDeficit(target, tdee)` is true when the target is below the band around TDEE, so rounding never counts as a deficit.

**Protein ramp (NU-03).** The first 7 days are a baseline week, which needs at least 5 logged days. During it, protein is shown but has no target. After that:

```
target(week n) = min(full_target, baseline_mean + 15 g × n)     // week 1 starts the day after the baseline week
```

- On by default for vegan, lacto-vegetarian and eggetarian diets.
- **Finding the baseline** (`ProteinRamp.baseline`): only days on or after `count_from` (the day the ramp was turned on) count. The window starts on the first day with food logged and runs 7 days. A finished window with 5 or more logged days is the baseline, and its mean protein over logged days is locked into the ramp state. A finished window with fewer slides to the next logged day. A window that hasn't finished is *measuring*. No logged day yet is *not started*: Today shows the full target with "the ramp starts when you log food".
- The ramp state lives in DataStore (`protein_ramp`: `count_from`, plus `baseline_end` and `baseline_mean_g` once locked).
- Editing the full target doesn't reset the ramp. Turning the ramp off jumps straight to the full target; turning it on again starts a fresh baseline from that day.
- A baseline mean at or above the full target goes straight to the full target.

### 5.4 Adaptive maintenance (NU-05, P1)

```
end         = the check-in week's Sunday (log dates, so "day starts at" applies)
window      = 21 days to end when the primary source has ≥ 10 counted weigh-ins in it (§5.2),
              else 28 days to end, which needs ≥ 4
logged      = days in the window with food logged and not marked incomplete; needs ≥ 80% (17 of 21, 23 of 28)
anchor      = the window's last primary weigh-in; from = anchor − window days, or the trend's first point if later
Δtrend      = trend(anchor) − trend_at(from)                      // trend_at interpolates, as in §5.2
tdee_obs    = mean(kcal over logged days) − Δtrend × 7700 / (anchor − from, in calendar days)
tdee_new    = tdee_prev + clamp(0.5 × (tdee_obs − tdee_prev), −150, +150)   // at most 150 kcal a week
              without enough data: no tdee_obs, and tdee_new = tdee_prev
new target  = §5.3 applied with TDEE = tdee_new
```

- **No estimate** when any check fails, in this order, and the stored row says which: too few weigh-ins (`WEIGH_INS`), too few logged days (`FOOD_DAYS`), or a trend that starts inside the window (`HISTORY`). The screen says what's missing, e.g. "Needs food on 17 of the last 21 days: 12 so far".
- **The screen's number** is `tdee_new`, the smoothed estimate the proposal uses. `tdee_obs` is a detail line ("this window measured 2,350").
- `tdee_prev` is the previous week's `tdee_new`, or for the first check-in the TDEE stored on the target in force.

Example: you average 2000 kcal over 21 days and the trend falls 1.0 kg, so `tdee_obs ≈ 2367`. The Monday check-in, for the week just ended, shows the estimate, the proposed target and the reason (PRD §12.1). Accepting it writes a new `nutrition_target` row.

If logging consistently under- or over-counts by some percentage, that error moves `tdee_obs` by the same amount, so the targets still steer you correctly.

**Settings uses the estimate too.** Once any check-in has an estimate, every target computed in Settings (the display, previews and saves) takes its TDEE from the latest such week's `tdee_new`, whatever was decided on it, instead of BMR × activity. BMR stays the formula's (Katch-McArdle from M15) and still sets the floor. An *Adjust* offset isn't carried over: a later Settings change starts from the estimate. Settings → Targets says where the calories came from ("from your check-in of 13 Oct; the formula says 2,610").

**Check-in flags per goal (GL-08, PRD §4.8).** The weekly check-in runs adaptive TDEE for every goal. A goal's flag decides when a change is proposed. Rates are in % of body weight a week:

```
rate(d)      = the weekly rate (§5.2) as of day d: anchored at the last weigh-in in the 7 days to d, else unknown
two weeks    = rate(end) and rate(end − 7); unknown if either is, which breaks "in a row"
four weeks   = trend(last weigh-in in the week) − trend_at(that − 28 days), as % of the trend
waist change = the reading nearest end (within the week to end) − the one nearest end − 28 (within 7 days either side)

FAT_LOSS   LOSS_SLOW  both weeks' rates > −lossRatePctPerWeek × 0.5   (losing less than half the pace, or gaining)
RECOMP     FLAT       |four weeks| < 0.5%  and  |waist change| < 1 cm; no waist pair → "log your waist"
MAINTAIN   DRIFT      |four weeks| > 1%
LEAN_BULK  GAIN_SLOW  both weeks < 0.1%;   GAIN_FAST  both weeks > 0.5%
```

A proposal is always §5.3 recomputed with `tdee_new` at the current pace, and isn't shown when it would move the target by less than 50 kcal. A cut that loses faster than planned isn't flagged (PRD §11, question 4).

### 5.5 Weekly volume per muscle (PO-10, P1)

Each working set counts as 1 set for the exercise's primary muscle and 0.5 for each secondary muscle. The weekly total per muscle is compared against an editable reference band (default 10–20). It's for information only and never triggers a notification.

### 5.6 InBody trends (IB-02, IB-04)

```
protocol_ok = |time_of_day − usual_slot| ≤ 90 min && mins_since_meal ≥ 120 && !workout_before_scan
Δ between consecutive protocol_ok scans:
    |Δ skeletal_muscle| < 1.0 kg   → "within noise"
    |Δ body_fat_pct|    < 1.0      → "within noise"
trend over ≥ 3 protocol_ok scans = least-squares slope per 4 weeks
```

Scans taken off protocol are drawn hollow and excluded from both the deltas and the slope. Lean mass for Katch-McArdle (§5.3) comes from the latest protocol-ok scan.

**The protocol check in M4** (`ScanProtocol`, `:domain`, `body/`):
- **Time of day.** The scan's time of day is compared with the usual slot's as a circular difference, so 23:30 and 00:30 are 60 minutes apart. Without a slot, the time part passes; in practice the first scan sets the slot (PRD §7.9).
- **Since eating.** The form's *Last ate* chips store fixed minutes: under 2 h = 60, 2–3 h = 150, 3 h or more = 180, fasted overnight = 600. Only the 120-minute threshold matters.
- **Workout before.** `workout_before_scan` is pre-set when a completed session on the scan's log date ended at or before the scan's time.
- **Stored result.** `protocol_ok` is stored with `mins_from_slot`, so the reasons it failed can be shown later even if the slot changes. Both are recomputed when the scan is edited, with the slot in force at that time.
- **Linked weigh-in.** A scan writes a `body_weight` row with `source = SCAN` and `scan_id`, in the same transaction. An edit rewrites it, and deleting the scan cascades to it.
- **Units.** Masses are integer grams. `body_fat_pct`, `visceral_fat_level` (some machines show halves) and `ecw_tbw_ratio` are REAL. `segmental_lean_json` holds `{rightArmG, leftArmG, trunkG, rightLegG, leftLegG}`.

### 5.7 Goal recommendation (PRD §4.6, GL-02, GL-03)

Runs only after "Describe your goal". Inputs: an intent (`LOSE_FAT | BUILD_MUSCLE | BOTH | MAINTAIN`), training age, sex, and body fat from a scan in the last 60 days. Without a scan, it falls back to BMI.

```
highFat(sex)     = bodyFat ≥ (20% male | 28% female)   else  BMI ≥ 27   // pii-ok: thresholds
veryHighFat(sex) = bodyFat ≥ (25% male | 32% female)   else  BMI ≥ 30   // pii-ok: thresholds
underweight      = BMI < 18.5

LOSE_FAT      → underweight ? MAINTAIN : FAT_LOSS
BOTH          → underweight ? MAINTAIN
              : (trainingAge < 1 year || highFat) ? RECOMP
              : LEAN_BULK   (reason: "recomposition is slow for lean, trained lifters; bulk now, cut later")
BUILD_MUSCLE  → (veryHighFat && !underweight) ? RECOMP : LEAN_BULK   // RECOMP is a deficit, so never below BMI 18.5
MAINTAIN      → MAINTAIN
```

- **Output:** `GoalAdvice(goal, reason, basis)`. `reason` is one of `UNDERWEIGHT | LOSE_FAT | BOTH_NEW_TO_LIFTING | BOTH_HIGHER_FAT | BOTH_LEAN_AND_TRAINED | MUSCLE_HIGH_FAT | MUSCLE | STAY`; `basis` says whether body fat or BMI decided it, with the value. The UI turns them into a one-line template.
- **Body fat** comes from a scan in the last 60 days (M4), or from a number typed in the third question. A typed number is used for this suggestion only and never stored. Otherwise BMI decides.
- **Training age** is the experience level: under a year is `BEGINNER`. The answer also pre-fills the persona's experience.
- **With AI on (GL-03):** Claude maps the free text to `{intent, follow_up_question?}` with a hand-written JSON schema (§6.2). The rules above then decide. AI never picks the goal directly, so the outcome is testable and identical offline.
- **Thresholds** live in one table in `:domain`, so they're easy to tune.

### 5.8 Finish line, projection and guardrails (PRD §4.7, §4.9)

```
projection_kg_per_week ≈ adjustment × 7 / 7700          // shown as approximate
weeks_to_finish        ≈ |trend − target| / |projection|  // recomputed weekly from the trend; none if moving away
reached                = trend crosses the target weight or body-fat %, or the date passes

maintenance_break_due  = deficit goal ∧ weeks_since_start ≥ 12 ∧ ¬reached ∧ no break in the last 12 weeks
```

- **The pace step** shows each pace's projection in kg a week, plus when the goal weight would be reached ("around mid-March"), using the suggested goal weight until you change it. For a date finish line it shows the expected change by that date.
- **Suggested goal weight** (`GoalWeights.suggest`): the BMI-25 weight for your height; if your BMI is already 25 or less, 5% below your current weight but not below BMI 20, and never above your current weight. Rounded to the nearest kg. A scan-based suggestion (IB-05) replaces it once scans exist.
- **Default finish lines:** fat loss and recomp reach the goal weight; lean bulk ends on a date 16 weeks out; Maintain has none.

**Guardrails** (`Guardrails.apply(profile, plan, today)`) are checked whenever a goal, pace or profile changes:
- Below BMI 18.5, deficit goals are rejected and the suggestion is Maintain.
- Under 18, the pace is clamped to Gentle, and a custom loss rate to Gentle's rate.
- An Aggressive pace needs one acknowledgement of a warning, stored on the goal plan.
- The intake floor (§5.3) always applies, overrides included.

Each rejected or clamped choice carries a `GuardrailNote` for the UI. A profile change in Settings that makes a deficit goal unavailable (e.g. a lower weight) switches the plan to Maintain, and the before-and-after preview says why.

### 5.9 Today's numbers (GL-07, GL-10, PRD §4.8)

```
main number, by goal:
    FAT_LOSS   calories left        = kcal_target − kcal_eaten        // below zero reads "over", never styled as a failure
    RECOMP     protein left         = protein_today − protein_eaten   // protein_today follows the ramp; none while measuring
    MAINTAIN   calories within band = band(kcal_target) − kcal_eaten  // "in range" once inside it
    LEAN_BULK  calories still to eat = max(0, kcal_target − kcal_eaten)
7-day average = mean over logged days in [today − 7, today − 1] of kcal and protein, against the mean of those days' targets
```

- Today's log date rolls over at the day boundary while the screen is open (`DayBoundary.observeToday`, which also re-checks every minute).
- The secondary line shows the other of calories and protein. The average line shows how many days were logged, and is replaced by "appears once you log food" when none were.
- Intake is `food_log` summed per `log_date` (`NutritionRepository.observeIntake`). A day counts as logged once it has any entry.

**Rest day (HM-01, `WeeklyPlan.restDay`).** Today says *Rest day* when one of these holds:
- a completed strength session is logged today (`DONE_TODAY`);
- this week's distinct strength days have reached the target (`WEEK_DONE`).

Otherwise it shows the next routine. On a rest day the next routine still shows, with *Start anyway*.

## 6. Key technical flows

### 6.1 Active workout lifecycle (WL-05, WL-06)

1. **Start.** Insert `workout_session(status = ACTIVE)`, its `session_exercise` rows (targets and superset groups copied from the routine, recommendations frozen from M2) and their `set_entry` rows: last session's warm-ups, then `target_sets` working sets, all with null values. Only one session can be ACTIVE at a time; a second start throws.
2. **Every ticked set is written to Room immediately** as a single `UPDATE` of load, reps, RIR and `completed_at`. Edits before the tick are written too. The workout UI is a Flow over the database, so process death loses nothing. Ghost values are computed in the ViewModel from the hot query (`Prefill` in `:domain`), never stored. On launch, an ACTIVE session triggers a "Resume workout?" prompt. If the session is more than 4 hours old, the prompt offers to finish it, using the last set's time as the end time.
   - **Tick order (RT-05, M6).** Ticking selects the next unticked set (`Supersets.afterTick` in `:domain`). An exercise on its own goes set by set, with its rest after each. A superset goes round by round: its warm-ups first, then the n-th set of each exercise in turn. Inside a round the next exercise's set follows with no rest (or an earlier exercise's, when the round started on a later one, unless that exercise has a set left in an earlier round: it's being skipped, so it isn't waited for), and any rest still running ends. The ongoing notification and the rest-over alert name the first unticked set in the same order. After the round's last exercise, rest starts with that exercise's rest time. There's no rest after the workout's last set.
   - **Superset edits (M6).** *Superset with next* joins two or three consecutive exercises, and *Split superset* frees them; groups are renumbered 1, 2, 3… in order on every save. *Move up/down* never breaks one: inside a superset an exercise swaps with its neighbour, at its edge the whole superset moves, and an exercise moving past a superset jumps it. A swap keeps its slot's group.
   - **Warm-ups (WL-11, M6).** `WarmupPlanner` suggests 40%, 60% and 80% of today's target load, as 8, 5 and 3 reps, for the first compound, weighted exercise with a target load. Loads round down to the exercise's step, and warm-ups under two steps are dropped. On a barbell a lighter warm-up is raised to the empty bar (20 kg); duplicates and any at the target are dropped. The line shows until the exercise has a warm-up set or a ticked set. *Add* inserts the suggestions as warm-up sets with their values entered, ahead of the working sets, and selects the first. It does nothing once the exercise has a warm-up or a ticked set, so a double tap can't add them twice.
3. **`ActiveWorkoutService`** is a foreground service that runs only while a session is ACTIVE. It observes the active session and the rest timer, and stops itself when there's no active session. Its ongoing notification shows elapsed time, the current exercise and set, and the rest countdown, with *Skip rest* and *+30 s* actions; tapping it opens the workout.
   - Android 14+ needs a declared service type. Use `health`, which requires the `ACTIVITY_RECOGNITION` runtime permission. **Confirmed in M0** on the reference phone (Android 15).
   - *Start* asks for `ACTIVITY_RECOGNITION` and `POST_NOTIFICATIONS` first. If they're refused, the workout still runs with the in-app rest banner, which vibrates when rest ends while the app is open.
   - `START_NOT_STICKY`: if the process dies, the resume prompt restarts the service on the next launch.
4. **Rest timer.** `RestTimer` stores an absolute end time and the rest length in a small DataStore, not a countdown, so it survives process death. The UI and the service both observe it. The service fires the high-priority "rest over" alert and vibration. A coroutine `delay` counts uptime, which stops while the CPU sleeps, so the service holds a `PARTIAL_WAKE_LOCK` for the rest's length plus a 10 s margin. A foreground service's wake lock is honoured even in Doze, so exact alarms (and their permission) aren't needed. "+30 s" after a rest has ended starts a fresh 30 s rest. M0 showed the alert channel's vibration supersedes a direct `Vibrator` call, so the service vibrates directly only when the channel can't: notifications are off for the app, the channel is blocked, or its vibration is turned off.
5. **Finish.** Delete unticked sets, set `status = COMPLETED` and `ended_at`, compute PRs and outcomes (M2), write a Health Connect `ExerciseSessionRecord` (P1), then stop the service. Exercises with no sets stay, as a record that they were skipped. **Discard** sets `status = DISCARDED`; discarded sessions never count as history.
6. **Keep screen on.** Use `FLAG_KEEP_SCREEN_ON` on the workout screen when the setting is enabled. No wake lock is needed.
7. **Save back (RT-03).** The session's plan (exercise ids in order, with sets, rep range, rest and normalized superset groups) is compared with the routine's. If they differ, the summary offers to rewrite the routine from the session in one transaction.

### 6.2 AI features (optional; PRD §7.12)

*From M7 (AI-05) the provider is a setting: Anthropic, OpenAI, Google Gemini or an OpenAI-compatible endpoint. This section describes Anthropic, the default, as built in M3c; M7 adds the others behind the same gate, statuses, pricing rules and validation.*

**Access: an API key, not a subscription login.**

- **A Claude.ai Pro or Max login can't authenticate a custom app.** Anthropic doesn't allow third-party apps to [offer claude.ai login](https://code.claude.com/docs/en/agent-sdk/overview), and developers [may not collect, store, or intermediate Claude.ai credentials or session tokens](https://code.claude.com/docs/en/legal-and-compliance). There's no exception for an app you build for yourself.
- **The app uses a Claude API key** from the Claude Console. API usage is billed separately from the subscription, based on actual monthly usage.
- **Max plans can fund it.** [Max includes monthly API credits](https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers): $100 on Max 5x, $200 on Max 20x. They're usable for your own apps, and this app's expected spend (≈ $4–6 a month on Opus 5.5) is a small fraction of that. Without Max, the key is pay-as-you-go.
- **Setup, done once:**
  1. Enable the credits following that page.
  2. In the Console, create a workspace for the app and set a spend limit on it.
  3. Create an API key in that workspace.
  4. Paste the key into the app's Settings.
- **Spend control:** the workspace limit is the hard stop. The in-app monthly cap (AI-03) is a second guard.
- **Considered and rejected:** handing off to the Claude Android app through a share intent. It needs manual copy and paste each time and returns nothing the app can parse.

**Modes and fallback (AI-01 to AI-03)**

```
AiGate.state = OFF | NO_KEY | OVER_CAP | OFFLINE | READY
```

- **How the state is decided** (`AiGate.state` in `:domain`, first match wins): `ai_mode` off → `OFF`; no stored key → `NO_KEY`; this month's `cost_usd` sum at or above `ai_monthly_cap_usd` → `OVER_CAP`; no validated connection (the default network lacks `NET_CAPABILITY_INTERNET` or `NET_CAPABILITY_VALIDATED`) → `OFFLINE`; otherwise `READY`. "This month" is the local calendar month of `created_at`; `OVER_CAP` lifts on the 1st. `AiRepository.gate` is a Flow over the settings, the key store, the log and connectivity, so screens follow it live.
- **Gate first.** Every AI entry point checks `AiGate`, and `AiRepository.estimate` checks it again just before sending, so nothing leaves the phone unless the state is `READY`. When it isn't, the UI shows the non-AI path instead: search, saved meals and quick add, with *Save draft* for the description (PRD §7.7, *Not ready*). AI buttons are hidden when the state is `OFF`.
- **Failures.** A request times out after 20 s. The client (not the SDK, whose retries are off) retries once, and only when the request clearly wasn't processed: a 429, a 5xx (including 529 overloaded), or a connection that was never made. It waits what `retry-after` asks, but at most 2 s. A timeout, or a connection dropped after sending, isn't retried: the server may still be working on the request, and billing it, so sending it again could pay twice. The worst case is therefore about 22 s. The first live requests' `latency_ms` will show whether 20 s is right; a long reply that would end in `max_tokens` shows up as a timeout until then. Every request is logged with its status. Any status but `OK` saves the input as a `meal_draft` with that status as its `reason` and drops you into the non-AI path. The draft is written in the same non-cancellable step as the request's log row, so leaving the screen mid-request never loses it; estimating a draft again updates that draft in place. Logging is never blocked. *Cancel* only stops waiting: the request runs to completion in the background so its spend is still logged, its result is discarded, and no draft is kept. A failed or cancelled *Re-estimate* keeps no draft either: it returns to the earlier estimate, which was already paid for, and says why.

  | Status | From | The app says |
  |--------|------|--------------|
  | `PENDING` | Logged before sending, settled when the reply comes; one the app died during stays `PENDING` | — |
  | `OK` | `end_turn` with at least one item that passes validation | — |
  | `REFUSAL` | `stop_reason: refusal` on the final response (after any server-side fallback) | Claude declined to estimate this |
  | `MAX_TOKENS` | `stop_reason: max_tokens` | The estimate was cut off |
  | `INVALID` | Another stop reason, no text block, JSON that doesn't parse, or no item left after validation | Claude's reply couldn't be read |
  | `AUTH` | `UnauthorizedException` (401), `PermissionDeniedException` (403) | Your API key was rejected |
  | `BILLING` | HTTP 402 (`billing_error`) | Your Anthropic account is out of credit |
  | `RATE_LIMIT` | `RateLimitException` (429), after the retry | Claude is busy right now |
  | `SERVER` | `InternalServerException` (5xx, including 529 overloaded), after the retry | Claude is unavailable right now |
  | `BAD_REQUEST` | Any other 4xx | Claude couldn't take this request |
  | `TIMEOUT` | `AnthropicIoException` caused by an `InterruptedIOException`; not retried, and costed at the request's upper bound | Claude didn't answer in time |
  | `NETWORK` | Any other `AnthropicIoException`: retried once at no cost if the connection was never made (refused, no host, no route, TLS handshake), else costed at the upper bound | Couldn't reach Claude |
  | `UNEXPECTED` | Any other exception, caught so none escapes (some carry request headers, the key among them, in their message); only its class name is kept | Something went wrong sending it |
- **Drafts** show up in their meal on that day's Food tab, with *Estimate now* once the gate is `READY` again. They're never sent automatically. Saving an estimate made from a draft deletes the draft.
- **Spend.** `cost_usd` = Σ over every attempt in `usage.iterations` (a declined attempt and its fallback both count; without iterations, the top-level `usage`) of input × input rate + output × output rate + cache writes × 1.25 × input rate + cache reads × cache-read rate, each attempt at its own model's rates. `AiPricing` in `:domain` holds the table, updated by hand when prices change; a model it doesn't know is priced at the table's highest rates, so the cap errs on the early side. The monthly sum is checked against the cap before every call; a request that crosses the cap still completes, and the next one is paused.
- **Reservation.** The gate check and a `PENDING` row are written together, under one lock, before the request is sent. The row is costed at the request's upper bound (`AiPricing.upperBound`: the prompt at 3 characters a token plus 1,500 tokens of schema and framing, and all 8,000 allowed output tokens; about $0.17 on Opus 5.5). So requests made at the same time each count the others' reservations and can't get past the cap together, and a request the app died during stays logged. The reply settles the row at its real usage. A request that was sent but never answered (a timeout, a dropped connection) keeps the upper bound, since the server may have billed it. This month's spend in Settings includes rows still pending.

  | Model ($ per MTok) | Input | Output | Cache read |
  |--------------------|-------|--------|------------|
  | `claude-opus-5-5` | 4.00 | 20.00 | 0.20 |
  | `claude-sonnet-5-5` | 2.00 | 10.00 | 0.20 |
  | `claude-haiku-5-5` (prompts up to 100K tokens, as all of ours are) | 0.10 | 0.50 | 0.01 (assumed 0.1× input) |
  | `claude-opus-5`, `claude-opus-4-8` (Opus 5.5's refusal-fallback targets) | 5.00 | 25.00 | 0.50 |
  | `claude-sonnet-5` (Sonnet 5.5's refusal-fallback target) | 2.00 | 10.00 | 0.20 |
  | `claude-fable-5-1` (not a target: the highest rates, charged for an unknown model) | 10.00 | 50.00 | 0.25 |
- **Refusal fallback.** Opus 5.5 and Sonnet 5.5 requests go to the beta Messages endpoint with `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`), so a safety-classifier false positive is retried on the model Anthropic recommends, in the same round trip. Haiku 5.5 has no server-side fallback, so its requests don't send it. `ai_request_log.model` records the model that served the response.

**Uses and output schemas.** Every request uses structured outputs: `output_config.format` with a JSON schema written by hand (`JsonOutputFormat.Schema`, or `BetaJsonOutputFormat.Schema` on the beta endpoint). The reply is parsed with kotlinx.serialization. **Don't use the SDK's class-based `.outputConfig(SomeClass::class.java)`.** It derives the schema with `java.lang.reflect.AnnotatedType`, which Android doesn't have even at API 37, so it crashes on the phone (found in M0).

| Kind | Input | Output (abridged) |
|------|-------|-------------------|
| `MEAL_TEXT`, `MEAL_PHOTO`, `FIT_CHECK` | Text and/or photo, plus the origin tag | `items[]` (shape below), optional `clarifying_question` |
| `LABEL` (P1) | Label photo plus the on-device OCR text | `name`, `brand?`, `per_100g {kcal, protein_g, carbs_g, fat_g, fiber_g}`, `serving_g?` |
| ~~`INBODY`~~ | Not used: body data stays on the phone (decided 2026-10-18), so InBody images are never sent (M15) | — |
| `COACH` (P1, M11) | The weekly programme report, with only the sections you ticked | `summary`, `suggestions[] {kind, routine_ref, exercise_ref, replacement_ref, value, reason}`; at most 5, checked in code (M11) |

```json
{
  "items": [
    { "name": "Phulka", "quantity": 2, "unit": "piece", "grams": 70,
      "kcal": 210, "protein_g": 6.5, "carbs_g": 42, "fat_g": 1.5, "fiber_g": 4,
      "confidence": "high", "assumptions": "medium size, no ghee", "diet": "vegan" }
  ],
  "clarifying_question": "Was the dal tempered with ghee?"
}
```

Every property is required, as structured outputs expect with `additionalProperties: false`; `clarifying_question` is `""` when there's none. `confidence` is an enum of `high`, `medium` and `low`. `diet` (vegan, vegetarian, egg, fish or meat) is what the item contains, by its strictest ingredient (§6.4); a value that can't be read is treated as unknown. The meal schema is `MealSchema` in `data/ai`, and the reply is decoded with kotlinx.serialization, ignoring unknown keys.

**Model.** `claude-opus-5-5` with adaptive thinking at effort `low` (decided 2026-10-18), configurable in settings (`ai_model`: Opus 5.5, Sonnet 5.5 or Haiku 5.5). Splitting a meal into items is an extraction task, and `low` keeps a typical estimate to a few seconds, well inside the 20 s timeout and PRD §10's 45 s describe-and-review target. Thinking can't be disabled on Opus 5.5, so no `thinking` field is sent; `max_tokens` is 8,000, room for the thinking and a long meal. The eval set (§8) decides whether the effort or the model should change.

| | Opus 5.5 ($4 in / $20 out per MTok) | Haiku 5.5 ($0.10 / $0.50 per MTok) |
|---|---|---|
| Per text request (~2k tokens in, ~1.5k out) | ≈ $0.04 | ≈ $0.001 |
| Photo request | ≈ +$0.006 | Negligible |
| Per month, at about 4 AI logs a day | ≈ $4–6 | ≈ $0.15 |

Saved meals push the number of AI calls down over time. Opus 5.5 is the default. On Max credits cost hardly matters; on pay-as-you-go, Haiku 5.5 (≈ $0.15 a month) may be worth it. **The open question is latency.** A described meal should take under 45 s including review (PRD §10). The eval set (§8) measures both accuracy and response time. If Opus is too slow for text logging, text requests can move to Haiku 5.5 while photos stay on Opus.

**Meal request details.**

- **System prompt** (`MealPrompt.system` in `:domain`; PS-03: built only from the persona and your own food data, with per-value text tables and no other branching):
  - Role: split one meal into items with grams, macros, a confidence and the assumptions made; ask a clarifying question only when one answer would move the total a lot.
  - **Diet rule from the persona:**

    | Diet | Rule |
    |------|------|
    | Vegan | No meat, fish, egg, dairy (milk, curd, paneer, ghee, butter, cream) or honey; assume the vegan version of ambiguous dishes |
    | Lacto-vegetarian | No meat, fish or egg; dairy is fine; assume the vegetarian version of ambiguous dishes ("biryani", "momos") |
    | Eggetarian | No meat or fish; egg and dairy are fine; assume the vegetarian version unless egg is named |
    | Pescatarian | No meat; fish, seafood, egg and dairy are fine; assume the vegetarian version unless fish or seafood is named |
    | Non-vegetarian | Anything; take dishes as named, and say in the assumptions which version an ambiguous one was taken as |

    A dish named outright that the diet excludes is estimated as named, with a note in its assumptions, never silently swapped.
  - **Region conventions from the persona:** Indian household portions (phulka, roti, katori, glass, piece, plate) and home-style Indian cooking, or international ones (cup, slice, piece, tbsp).
  - **Origin handling** for all three tags, so the prompt stays the same whichever applies: *Home* is home-style cooking with moderate oil; *Ordered* is restaurant or delivery food with restaurant levels of oil, ghee, butter and cream and restaurant portions; *Packaged* uses typical label values.
  - Your household measures at your calibrated weights (`portion_sizes` over the region's defaults, e.g. "katori = 180 g").
  - Your top 30 own foods (custom and AI-learned, unarchived, allowed by the diet and region, by use count, then last used), each with its values per 100 g and its own units.

  The system prompt is the same between calls until your foods or settings change, so it carries `cache_control` (5-minute TTL). Below the model's minimum cacheable length (512 tokens on Opus 5.5) it simply isn't cached; a re-estimate after a clarifying question, within 5 minutes, reads it from cache.
- **User message:** which origin applies, the description, and, for a re-estimate, the question asked and your answer. Each estimate is a fresh single-turn request, so there's no history to keep byte-identical (preserved thinking never applies).
- **Photos** are downscaled to about 1500 px on the long edge as JPEG before sending.
- **Client-side validation** (`MealCheck` in `:domain`):
  - Items with non-positive grams or quantity, or a negative or non-finite number, are rejected and named in a note. No item left is `INVALID`.
  - An item is flagged when its kcal is more than 15% away from `4P + 4C + 9F`, ignoring differences under 10 kcal so small garnishes don't flag.
  - Totals are recomputed from the kept items; nothing from the model is summed as given.
  - `stop_reason` (`refusal`, `max_tokens`) is checked before content is read; errors are handled with the SDK's typed exceptions (table above); the server-side refusal fallback is on (above).
- **Saving.** One `food_log` row per item, `source = AI_TEXT`, with `ai_request_id` and the meal's origin. *Save + My meals* also saves those rows as a saved meal (NU-13), and a draft the estimate came from is deleted: rows, learned foods, the saved meal and the draft change in one transaction (`FoodLogRepository.saveAiMeal`), so it's all or nothing. A second tap on *Save* does nothing.
- **Learning loop.** An item whose name, grams or numbers you changed before saving becomes a `food(source = AI)`: values per 100 g from your item, no region, and its unit (unless grams) as a calibrated `food_unit` at grams ÷ quantity.
  - **Diet tag:** the item's own `diet` from the reply, so a lacto-vegetarian's edited "chicken biryani" is `MEAT` and stays out of their search and prompts, and a meat eater's dal stays vegan. Only when the reply has no readable tag does the food fall back to the persona's own-food tag (`Diet.ownFoodTag`).
  - **No duplicates of your foods:** one of your own unarchived foods with the same name (ignoring case; custom first, then AI-learned) is updated instead. An AI-learned food also takes the new diet tag (when there is one), so a wrong tag is corrected. A custom food keeps its name and the tag you gave it.
  - **Bundled foods are never changed.** The basics and INDB are re-imported from their files, so an edited dish named like one becomes your AI-learned version beside it, marked *AI-learned* in search and ranked above it. The log row links to the food, so it's counted in use counts and appears in search, in future prompts and later in the local parser; repeat dishes converge on your numbers. Unedited items are logged with no food, from their snapshot.
- **Privacy.** Meal requests (text, and from M18 meal and label photos) send the meal text and origin, any photo, your diet and region rules, your household measures, and your top 30 own foods (names, values and units). They never send your name, body data, targets or log history. Other kinds send only what PRD §12 lists for them: the weekly programme review (M11) only the sections you tick, and goal interpretation (M19) only the text you typed. InBody images are never sent. Before a kind's first request to a provider, the app shows what it sends (`ai_notice_seen`, per provider and kind from M7); M11's full-text preview counts as its notice.
- **API key storage:**
  - Entered once in Settings → AI. Only printable ASCII without spaces is accepted, which is all a key is made of: a zero-width space, BOM or en dash picked up in a copy would make OkHttp reject the header with the whole key in its error message. A stored value that fails the same check reads as no key.
  - Encrypted with Tink AEAD (AES-256-GCM, associated data naming the key) whose keyset is wrapped by an Android Keystore master key (`AndroidKeysetManager`; the keyset lives in its own shared-preferences file). The ciphertext is stored in the `secrets` DataStore, apart from the settings, so a settings export can never include it.
  - Never logged: the app never prints it, and the SDK client is built in memory per request. Excluded from export, and from cloud backup and device-to-device transfer: `data_extraction_rules.xml` includes only the database and the `settings` DataStore, so the `secrets` DataStore and the keyset file are left out (§6.5). A restore onto another phone couldn't decrypt it anyway, since the Keystore key never leaves the phone; it then reads as `NO_KEY`.
  - Each person enters their own key into their own install, so no key ever ships inside an APK. A build distributed with a shared key would need a proxy, because a key inside an APK can be extracted.

**Local describe parser (AI-04, P1).** This is plain rules, not AI. The same component powers offline *Will it fit?*.

```
"2 roti, 1 katori dal makhani and salad"
→ split on  ,  "and"  +  newline
→ per chunk: quantity (2, ½, "half", "one"…) + unit (food_unit labels + synonyms: bowl→katori, chapati→roti)
             + name
→ FTS-match name against the foods the persona's diet and region allow, ranked by use_count;
  unit → grams via food_unit, else the food's default serving
→ unmatched chunks listed as "Couldn't find: search or quick add"
```

### 6.3 Health Connect (P1)

- **Step sources:** whichever app writes steps to Health Connect. On a phone without a watch, that's typically Google Fit or Samsung Health.
  - Google has deprecated the Google Fit APIs in favour of Health Connect, so the app never calls Fit directly.
  - Google Fit writes the phone's step count into Health Connect when its Health Connect sync is on. **M0 finding:** Fit declares `WRITE_STEPS`, but its Health Connect access was off on the reference phone. It has to be turned on in Google Fit's settings.
- **Read:** daily step totals (`StepsRecord` aggregates).
- **InBody import (P1, IB-07):** read body records whose data origin is the InBody app (`com.inbody2014.inbody`). Records with the same timestamp are grouped into one `body_composition` draft for you to complete.
  - **M0 finding:** the InBody app (v2.8.40) can write only `WeightRecord`, `BodyFatRecord` and `BasalMetabolicRateRecord` (plus steps and blood pressure). It doesn't write lean mass or body water, and Health Connect has no types for skeletal muscle mass or visceral fat.
  - Lean mass for Katch-McArdle is derived as weight × (1 − body fat %).
  - Skeletal muscle mass and visceral fat are always typed in, with a shared image beside the form if you have one (§6.7, M15).
  - The InBody app's Health Connect access was off; turn it on in the InBody app.
- **Sleep (AR-02):** typed in at the morning weigh-in, since the reference phone has no tracker. When a tracker writes `SleepSessionRecord`, it prefills the typed value (M13).
- **Write (DA-04):** `ExerciseSessionRecord` (strength training, yoga), a `NutritionRecord` per meal, and `WeightRecord` for manual weigh-ins, so they show up in Google Fit. Each record carries a `clientRecordId` (e.g. `meal:2026-10-18:lunch`), so a re-sync updates it in place and a delete can follow (M16).
- **Phone steps without another app:** on Android 14 with SDK extension 20 or later, Health Connect counts the phone's own steps once any app holds `READ_STEPS`, under a device data origin found with `getCurrentDeviceDataSource()`, never hard-coded. Its daily aggregate de-duplicates across apps by the user's priority list. Only on an older Health Connect does M13 read the `TYPE_STEP_COUNTER` sensor itself, sampled at launch and by a periodic worker, with reboot resets handled. M13 checks the reference phone's extension level.
- **Sync** runs when the app opens, plus a daily WorkManager job. Reads are incremental using change tokens. Records whose data origin is this app are skipped.
- **The app works fully without Health Connect.**

### 6.4 Food data (database P0, scanning P1)

- **Region databases (P0, NU-11).** Each `food` row carries a diet tag, and search shows only the tags the persona's diet allows (`Diet.allows`): vegan → `VEGAN`; lacto-vegetarian → plus `VEGETARIAN` (dairy); eggetarian → plus `EGG`; pescatarian → plus `FISH`; non-vegetarian → everything. A recipe takes its strictest ingredient's tag.
- **Indian region: INDB**, the Indian Nutrient Databank ([GitHub](https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-), [paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC11277795/)).
  - **What's in it:** `INDB.xlsx` holds 1,014 recipes with per-100 g and per-serving values for energy (kcal), protein, carbs, fat, fibre and more, plus each recipe's serving unit. The raw items INDB was built from come from IFCT 2017, which isn't in the authors' repository, so they aren't imported; the protein-rich basics below cover the staples.
  - **Never committed to this repo.** `:app:fetchIndb` downloads `INDB.xlsx` and `recipes.xlsx` (each recipe's ingredients, for the diet tags) from the authors' repository at commit `fbdb62bec519c6e2468582a799fb47e194b42558` and checks each file's SHA-256 (pinned in `app/build.gradle.kts`). Downloads are cached in Gradle's user home. The converter (`app.recomp.domain.food.indb`, run with `JavaExec` on the `:domain` classpath so it's tested with the domain) writes `food/indb.json` under `app/build/generated/indb/`, and the build adds that folder to the APK's assets.
  - **Why:** the authors' repository declares no licence, so redistributing the data from a public repo isn't safe. Fetching it from the source at build time is fine for personal builds. `-Precomp.indb=false` builds without it (search then has the basics, your own foods and quick add); any APK published from this repo must be built that way unless the authors agree.
  - **Diet tags** come from the ingredients, because INDB has no vegetarian flag. Ingredients carry IFCT food-group codes: `L` (milk products) is dairy, `M` egg, `N` and `O` meat, `P`, `Q`, `R` and `S` fish and shellfish. Exceptions by code: butter and ghee (`T500`, `T501`, `T506`), ice cream (`I501`), honey (`I507`, so not vegan), chocolate (`I509`), drinking chocolate (`V504`), white sauce, sour-cream dips, cook-in sauce and biscuits or wafers (`X506`, `X508`, `X500`, `U500`, `U509`) are dairy; gelatine (`K504`), chicken stock (`K506`) and jelly (`W501`) are meat; Worcestershire sauce (`X507`, anchovies) is fish; mayonnaise (`L507`) and thousand island (`X509`) are egg. INDB sometimes codes an animal ingredient as a vegetarian food ("chicken stock" as vegetable stock, "egg/milk" as milk), so egg, fish or meat named in the recipe's own wording (`ingredient_name_org`) lifts a coded ingredient; dairy words don't, or plain milk dishes would lose their vegan tag for nothing. Uncoded ingredients (essences, colours) are judged by both names: vegan, except jelly crystals (meat, for the gelatine). As a safety net, a recipe whose name says chicken, mutton, fish, prawn and the like is lifted to meat or fish. At the pinned commit: 288 vegan, 386 vegetarian, 173 egg, 25 fish and 142 meat recipes.
  - **Fried recipes.** 132 recipes list oil "for frying", and INDB counts all of it as eaten, so fried dishes read two to three times too high. Those above 250 kcal per 100 g get the `FRYING_OIL` caveat (125 at the pinned commit): the UI warns, and search ranks them below other matches. INDB's yield and serving data don't allow a reliable correction (tried and dropped in M3b).
  - **Indian units** come from INDB's serving unit for each recipe (bowl, plate, piece, parantha…), with grams = per-serving energy ÷ per-100 g energy × 100. A unit over 500 g (`Portions.MAX_UNIT_GRAMS`: a pot or platter, not a serving) or one that just means grams (gm, ml) is dropped, and a database food whose own unit is still implausible prefills 100 g. Some kept units still look odd; the portion sheet shows the grams and recalibrates them (`calibrated`, which re-imports leave alone).
  - **Licensing (checked 2026-10-17):** the paper is CC BY 4.0, and the authors describe the files as "publicly and freely available". Settings → Food → Food data cites INDB. Ask the authors before ever bundling the data in a distributed APK.
  - **IFCT 2017 (NIN):** a free PDF with no stated open licence, covering raw foods only. It's a cited reference, not a data source. The community CSV port is AGPL-3.0.
- **International region:** no bundled dataset in the MVP. Open Food Facts lookups (barcode and search, M18), custom foods, AI-learned items and quick add. A bundled generic dataset (e.g. USDA FoodData Central, public domain) isn't planned for v1.1; it's a P2 candidate.
- **Protein-rich basics** (`assets/seed/foods.json`, committed): our own list, per 100 g, rounded from USDA FoodData Central (public domain), or from typical Indian pack labels where USDA has no entry (paneer, soya chunks, whey). Each has a diet tag, a region only where it's regional, and household units (a scoop of whey, an egg). They're `source = SEED`, so the protein nudge (NU-23) can rank them by protein per 100 kcal.
  - Vegetarian: paneer (full-fat and low-fat), tofu, soya chunks, curd and hung curd, Greek yogurt, milk (whole, toned, double toned), whey, chana, rajma, moong, masoor, besan, peanuts.
  - Vegan: the same list without dairy and whey, plus soy milk and peanut butter.
  - Other diets: add eggs, fish and meat.
- **Import.** On launch, `FoodSeeder` upserts the basics and, when the build has it, INDB by `source_key`, but only when a file's version differs from `food_data_version`. Updates keep use counts, hidden foods and calibrated units. A food that disappears from a file is archived, never deleted, because log rows may point at it. Without INDB in the build, INDB rows already on the phone stay.
- **Search (NU-11).** `food_fts MATCH` with each typed word as a prefix (`pan* tik*`), joined to `food` and filtered to unarchived rows whose diet the persona allows and whose region is null or the persona's. Order: foods you've logged first (most often, then most recently), then custom, AI-learned, the basics and INDB, with caveat foods last among those; then names that start with the query, then shorter names. An empty query lists recently used foods, or the basics for the diet when nothing has been logged yet. Hiding a food archives it.
- **Units and portions (NU-12).** A food offers `g`, its own `food_unit` rows and the region's household measures (`portion_sizes`; Indian: katori 150 g, glass 250 g, tbsp 15 g; International: cup 240 g, tbsp 15 g; 1 ml counts as 1 g). Recalibrating a unit for one food writes a `calibrated` `food_unit` row, which beats the household default. A log row snapshots grams and macros: `kcal = kcal_100g × grams / 100`, and so on.
- **Meal slots (NU-10)** are `{key, name, removed}` in `meal_slots`. `food_log.meal_slot` stores the key, so renaming keeps history. A removed slot still shows on days that have entries in it.
- **Saved meals and copy (NU-13).** Logging a saved meal inserts one row per item in a transaction (`source = SAVED_MEAL`); an item with a food recomputes from the food's current values. Copying a day or a meal re-inserts its rows on the target date with a new `logged_at`, keeping their macros, origin and source. Both bump the foods' `use_count` and `last_used_at`, and return the new ids for *Undo*.
- **Label scan (P1, NU-19):**
  1. CameraX captures the label.
  2. An open-source on-device text recogniser reads it (M18's choice; not ML Kit, see §7).
  3. Tolerant regexes parse the per-100 g column of the FSSAI nutrition table: `Energy (kcal)`, `Protein (g)`, `Carbohydrate (g)`, `Total Fat (g)`.
  4. An editable form is pre-filled.

  With AI on, the photo and OCR text also go to your AI provider (`LABEL`) for a cleaner result.
- **Barcode (P1, NU-21):** Open Food Facts API, with a custom User-Agent as its terms require. A result is cached as a `food` row. Coverage of Indian brands is patchy, so a miss goes straight to label scan.
- **Search ranking:** recent and frequent foods first (`use_count`, `last_used_at`), then custom foods, then AI-learned items, then the bundled database.

### 6.5 Backup and restore (DA-01, DA-02)

- **Export:** a versioned (`schemaVersion`) JSON of every table plus settings, without the API key, zipped with photos optionally included. Every table includes M3c's `meal_draft` and `ai_request_log`; the `secrets` DataStore is never read. Written through the system "create document" picker, so Google Drive works as a target.
- **Auto-backup (P1):** pick a folder once (the permission persists). A weekly WorkManager job writes `recomp-backup-YYYY-MM-DD.zip` and keeps the last 8.
- **Restore:** validate `schemaVersion`, migrate if the file is older, then replace the data in a single transaction after explicit confirmation.
- **Safety net:** Android Auto Backup is enabled for the database file, with the key store excluded (`data_extraction_rules.xml`, below).
- **Round-trip test:** export, then import, must give identical data. It's a required test (§8).

**The file (M4, `data/backup/`).** A zip with three JSON entries:

| Entry | Contents |
|-------|----------|
| `manifest.json` | `format: "recomp-backup"`, `formatVersion` (the zip layout, 1), `schemaVersion` (the Room version that wrote it), `exportedAt`, `appVersion`, and a row count per table |
| `database.json` | `schema`: every `CREATE` statement from `sqlite_master` (tables, indexes, triggers), and `tables`: per table, `columns` and `rows` as arrays of typed values |
| `settings.json` | every key in the `settings` DataStore, each with its type (`string`, `int`, `long`, `boolean`, `float`, `double`, `string_set`) |

- **Generic over the schema.**
  - Export reads `sqlite_master`, so a table added by a later milestone (M3c's `meal_draft` and `ai_request_log`, for example) is included with no code change.
  - Left out: SQLite's and Room's own tables (`sqlite_*`, `android_metadata`, `room_master_table`), plus FTS virtual tables and their shadow tables. Those are rebuilt from their content table.
  - Values keep their SQLite storage class: integers stay integers, REAL values round-trip exactly as JSON numbers, and BLOBs are base64.
  - The snapshot is read inside one transaction, so it's consistent.
- **Secrets.** The AI key is ciphertext in its own `secrets` DataStore, and Tink's keyset is in the `recomp_ai_keyset` shared preferences (§6.2). Export reads neither, so the key can never be in a backup, and import never writes them. A safety net catches any setting that ever lands in `settings` by mistake: `SettingsKeys.NOT_EXPORTED` is a deny-list, and any key whose name looks secret (`api_key`, `secret`, `token`, `password`, `keyset`) is skipped too. Tests check that a key saved the app's way, or planted in `settings`, never appears in the export.
- **Restore** (`BackupManager`):
  1. Decode the zip's three entries straight from the stream, reading at most 16 MB in all (several times any real backup; a year of daily use is about 1 MB of JSON), and check `format`, `formatVersion`, and `schemaVersion` (between 1 and the app's version). A newer backup is refused with "made by a newer version of Recomp". Every known setting must have its type and decode with the app's own decoders, in range (`SettingsPrefs.problems`).
  2. Build a scratch database in the cache directory from the backup's own `schema`, insert its rows, and set `user_version` to its `schemaVersion`. The schema may only create tables, indexes and triggers under plain names.
  3. Open the scratch database with Room and the real `MIGRATIONS`. An older backup is migrated by the same tested migrations as an upgraded phone. Room then validates every table. `PRAGMA foreign_key_check` must find no broken link, and every row of every table must read back through Room's converters (`BackupCheckDao`, which a test keeps in step with the tables), so an unknown enum name or a bad date fails here. A damaged, hand-edited or newer-than-claimed file is refused before anything on the phone is touched.
  4. Write the safety copy: the current data, exported to `files/backups/before-import.zip`.
  5. In one Room transaction with `PRAGMA defer_foreign_keys = ON`: delete every user table's rows, copy every row from the scratch database by column name, rebuild each FTS index, and run `foreign_key_check` once more before the commit, so a broken link rolls back instead of failing the commit.
  6. Replace the `settings` DataStore with the backup's settings, keeping any setting that never leaves the device. The DataStore migrations (`LegacyTrainingMigration`) run on old settings first.
- **Ordering.** The settings can't share the database's transaction, so the database is replaced first; the scratch database has already proved the backup valid. Restore runs to the end even if the screen goes away (`NonCancellable`).
  - **An interrupted import.** Inside the transaction, just before the commit, the database header's `application_id` is set to a random token, and `files/backups/pending-settings.json` is written with that token and the backup's settings. The token commits or rolls back with the rows.
  - **At launch,** `BackupManager.finishPendingRestore` looks for that file:
    - when the header carries its token, the commit happened, so the settings are written now ("Finishing the last import");
    - when it doesn't, nothing was replaced, and the file is dropped ("The last import didn't happen").
  - **A failed settings write** keeps the file for the next launch. The message says the data was replaced, and points to the safety copy, which is always there from step 4.
- Import is refused while a workout is ACTIVE on the phone. A backup taken during a workout restores it as active, and the resume prompt picks it up.
- **Android Auto Backup** (`res/xml/data_extraction_rules.xml`) lists only `recomp.db` (and its WAL) and `datastore/settings.preferences_pb`, for cloud backup and for device transfer. Everything else, including the key store (the `secrets` DataStore and Tink's keyset file), is left out by omission. Lint rejects an `<exclude>` outside an included path, so the key files can't also be excluded by name.

### 6.6 Notifications

- **Channels:** *Workout* (ongoing), *Rest timer* (high importance), *Reminders*, *Weekly check-in*.
- **Scheduling:** reminders use AlarmManager's inexact alarms that may fire while the phone dozes (`setAndAllowWhileIdle`; no exact-alarm permission), since Doze holds WorkManager's jobs until maintenance windows that grow rarer the longer the phone is idle. A few minutes either way is fine. WorkManager stays for sync and backups (M13, M16).
- **Permission:** `POST_NOTIFICATIONS` is requested at the first opt-in.

### 6.7 Share target (IB-03, NU-19, NU-20)

The app registers as an Android share target for images (`ACTION_SEND`, `image/*`). A shared image opens a picker: *InBody result*, *Nutrition label* or *Meal photo*. Each choice leads into its own flow. That's how a screenshot from the InBody app gets in without saving files around. *InBody result* always opens the scan form with the image alongside; AI never reads it (M15, PRD §12.11).

## 7. Non-functional requirements

| Area | Requirement |
|------|-------------|
| Performance | Cold start to the Today screen < 1.5 s on a mid-range phone. Ticking a set updates the UI in < 100 ms. Food search over 10k items returns in < 150 ms. |
| Reliability | No data loss on process death or reboot mid-workout. Every migration has a test. |
| Offline | Everything except AI features and Open Food Facts lookups (barcode and search) works offline. Health Connect and label OCR run on-device. |
| Gym usability | Set controls are at least 56 dp. Primary actions sit in the bottom half of the screen. High-contrast dark theme. Layout holds up at 130% font size. |
| Privacy | No analytics or crash SDKs. The only network calls go to the AI provider you chose (AI-05) and Open Food Facts. |
| Battery | The foreground service runs only during an active workout. |
| Accessibility | Content descriptions on every control. TalkBack can complete the set-logging flow. |

## 8. Testing strategy

| Layer | What | How |
|-------|------|-----|
| Domain (highest value) | Every branch of `ProgressionEngine`: baseline, add reps, increase, big-jump guard, repeat, deload, stall (with and without a deficit), bodyweight, bodyweight plus load, assisted. `NutritionCalculator`, `GoalPolicy` and pace mapping (golden vectors A–E in §5.3, both caps, the BMR floor), goal recommendation rules (every branch of §5.7, scan and BMI fallbacks), guardrails and projection (§5.8), `ExperiencePolicy` wiring into the stall window, protein ramp, `TrendCalculator` (daily, weekly and mixed spacing, seeding, primary-source filter), InBody noise and protocol rules (§5.6), `AdaptiveTdee` (synthetic data with a known TDEE). The local describe parser, against a corpus of your real meal phrasings. | Table-driven JUnit on the JVM |
| Data | DAOs and repositories (setup, rotation, logging, resume after a simulated process death, finish, save-back), onboarding and Settings saves (target versions, overrides, same-day replace), the `training` → `persona` DataStore migration, migrations, seed data, backup round-trip, food DB import (vegetarian-only filter holds), food search ranking and FTS sync, food logging, saved meals, copy, and Today's intake. The INDB converter and diet tags are domain tests over a synthetic workbook, so no test needs the real file. | Robolectric with a file-backed Room database. Migration tests build each old version from its exported schema JSON and let Room migrate and validate it; `MigrationTestHelper` can't read schemas under Robolectric because unit tests don't merge test assets. |
| AI | `AiGate` states and every fallback path (off, no key, over cap, offline, timeout, 429). `MealEstimator` contract via a fake. The Claude client's request shape, error mapping, retry and timeout against a local mock server. Prompt building, schema parsing, validation, pricing, drafts, saving and the learning loop. An **eval set of ~20 of your typical meals**, home and ordered, with reference values, used to compare models and prompts. | Fakes in JUnit; OkHttp's `MockWebServer` for the SDK client. The eval runs manually before changing the model or prompt. |
| UI | Set logging (prefill, tick, edit, rest timer), meal review screen, logging a meal in airplane mode with AI on | Compose UI tests |
| Manual | A gym dogfood checklist before each release | |

## 9. Build and distribution

- Public Git repository (MIT). CI runs on GitHub Actions for every push and pull request: the privacy scan, then unit tests, Android Lint and a debug build. ktlint isn't set up yet. Each milestone also builds the release APK locally (§10).
- Release APKs are signed with a personal keystore that stays out of git. Install with `adb` or from a GitHub Release. Play Console internal testing is optional later.

## 10. Milestones

| Milestone | Contents | PRD coverage |
|-----------|----------|--------------|
| **M0: Setup and spikes** | Project skeleton, theme, navigation, Room, Hilt, CI, privacy pre-commit hook. **Spikes:** (a) the Anthropic Java SDK running on Android, including R8 keep rules; (b) foreground service type and lock-screen rest timer on a real phone; (c) whether Google Fit's steps show up in Health Connect; (d) licensing and format of the IFCT and INDB food data; (e) which records the InBody app writes to Health Connect. Goal-driven `NutritionCalculator`. | — |
| **M1: Training core** | Exercise seed data with your PPL routine pre-loaded, onboarding equipment check, rotation, active workout (prefill, steppers, RIR, rest timer, service, resume), summary, yoga log | EX, RT, WL, YG P0, PR-04 |
| **M2: Progression** | `ExperiencePolicy`, engine and its tests, targets in the workout UI, live feedback, PRs, stall card, exercise history | PO P0, HM-02 |
| **M3a: Personas and targets** | `Persona` and `GoalPlan` models, presets and onboarding (PS-01 to PS-03), the goal flow with cards, guided suggestion, pace, finish line and guardrails (GL P0), Settings, versioned targets (Room v4) with overrides and the protein ramp, "day starts at", and a goal-driven Today screen; M1's training setup folded in; `inDeficit` wired into progression. | PS, PR-01 to PR-03, GL P0, NU-01 to NU-04 |
| **M3b: Food logging** | The INDB download task and regional food database with diet filter; foods and regional units; saved meals; copy day; quick add; origin tags; daily view. | NU-10 to NU-16 |
| **M3c: AI logging** | `AiGate` with AI Describe it, drafts and the spend cap. | NU-17, AI P0 |
| **M4: Body, Today and data** ✅ | Weight sources with the time-aware trend, waist, InBody manual entry with the conditions check, Today screen, export and import. **MVP complete; start dogfooding.** | BM P0, IB P0, HM-01, DA-01 |
| **M5: Weekly check-in** ✅ | Adaptive maintenance estimate, the weekly check-in with per-goal flags and a proposed target, incomplete days (Room v8) | NU-05, GL-08 (flags) |
| **M6: In the gym** | Supersets, warm-up suggestions, assisted exercises, RIR-aware jumps, substitutes (Room v9) | RT-05, WL-11, WL-09 (assisted), PO-08, EX-04 |
| **M7: AI providers** | Anthropic, OpenAI, Google Gemini or any OpenAI-compatible endpoint, each with its own key (Room v10) | AI-05 |
| **M8: Weekly review** | The full review, consistency calendar, body-composition chart with noise labels | HM-03, HM-04, IB-04 |
| **M9: Faster food logging** | Local describe parser, Will it fit?, snack allowance | AI-04, NU-18, NU-06 |
| **M10: Training history** | Edit or delete past workouts, per-exercise charts, weekly sets per muscle | WL-10, PO-09, PO-10 |
| **M11: Programme review** | A weekly report you check, sent to your AI provider, with up to 5 suggested changes you accept one by one (Room v11) | HM-06 |
| **M12: Nudges, reminders, widget** | Opt-in reminders on AlarmManager (2 a day at most), the goal's evening nudge with protein suggestions, workout-day and scan-slot reminders, home-screen widget | RM-01, NU-23, GL-08 (nudges), PO-11, IB-06, HM-05 |
| **M13: Health Connect in** | Steps with a goal, sleep at the morning weigh-in, other apps' workouts, InBody records as scan drafts (Room v12) | AR-01, AR-02, AR-04, IB-07 |
| **M14: Recipes** | Recipe builder; a recipe is a food (Room v13) | NU-22 |
| **M15: InBody share, scan targets** | The share target (§6.7) with *InBody result* (no AI), Katch-McArdle BMR, the body-fat finish line | IB-03, IB-05 |
| **M16: Data out** | Weekly automatic backup to a folder, CSV export, Health Connect write-back | DA-02, DA-03, DA-04 |
| **M17: Body extras** | Tape measurements, progress photos, focus areas, mobility markers (Room v14) | BM-03, BM-04, YG-03, YG-04 |
| **M18: Camera food** | Barcode with Open Food Facts, label scan, AI meal photos, Open Food Facts search for the International region | NU-21, NU-19, NU-20, NU-11 (search) |
| **M19: Goals** | Goal switch, Goal reached card, maintenance breaks, AI goal interpretation, pregnancy toggle, presets as data files | PS-04, PS-05, GL-03, GL-06, GL-09 (pregnancy) |
| **M20: lb and in** | Imperial display and entry everywhere | PR-05 |

The v1.1 rows are the plan settled on 2026-10-18; the details, waves and rules are under *The v1.1 plan* below, and the UX for each is in PRD §12.

**M0 results (2026-10-17, reference phone on Android 15):**

| Spike | Result |
|-------|--------|
| (a) Anthropic SDK | ✅ Builds, and passes R8 with broad keep rules. On the phone, the minified build's request reached the API and came back as a typed `401` error. ⚠️ The SDK's class-based structured outputs can't work on Android (no `AnnotatedType`), so schemas are hand-written. A live meal estimate with your key is still to do. |
| (b) Rest timer | ✅ The `health` foreground service runs, the ongoing notification counts down and is visible on the lock screen, and the "rest over" alert fires and vibrates on time. Checking the lock screen by eye is up to you. |
| (c) Steps | ⚠️ Google Fit can write steps, but its Health Connect access is off. Turn it on, then re-check. |
| (d) Food database | ✅ INDB (§6.4). |
| (e) InBody → Health Connect | ✅ Weight, body fat % and BMR only (§6.3). The InBody app's Health Connect access is off. |

**M1 results (2026-10-17, reference phone on Android 15, release build):**

| Check | Result |
|-------|--------|
| Upgrade from the M0 install | ✅ Migration 1→2 ran on the phone; the library seeded 81 exercises. |
| Training setup and walkthrough | ✅ PPL, beginner, 3 days a week, a Saturday yoga class; picking the machine shoulder press swapped it into Push. |
| Full Push workout | ✅ Ghost values, steppers, RIR, rest banner, and the ongoing notification ("Push · resting" with a countdown, *Skip rest* and *+30 s*). Rotation moved to Pull and the week showed 1 of 3. |
| Killed mid-workout | ✅ `am force-stop` after 5 sets, then relaunch: *Resume workout?* restored every set and the remaining rest. The first run showed the service didn't restart on resume; fixed and re-checked. |
| Summary and save-back | ✅ Duration, sets, reps, volume and per-exercise sets; an added set was saved back to Push. |
| Extra session | ✅ Logged from Today, counted 1 of 1, and the next log prefilled from it. |
| Next session | ✅ Started Push again: every set showed last session's values as ghosts. Discarding it left the rotation alone and stopped the service. |

**M2 results (2026-10-17):** the engine has table-driven tests for every branch in §8 (baseline, add reps, increase, big-jump guard, repeat, deload, stall with and without a deficit, Hold and Deload resets, bodyweight, weighted bodyweight), plus PR detection, live feedback and target grading. Repository tests cover frozen targets, overrides, PRs at finish, stall choices, the Today preview and exercise history. The 2→3 migration is tested from the exported schema, and on the reference phone the release build upgraded over M1 data with history, rotation and the week intact. On the phone:
- Today previewed each exercise's target.
- Push showed "Today: 40 × 12/12/11 · add reps", and the shoulder press asked for 13 on its first set because of the big-jump guard.
- Three single-tap ticks showed "target hit", "+1 rep", "target hit".
- The summary showed a session-volume PR, the target graded as hit, and "add a rep at 40 kg" for next time.
- Exercise history listed both sessions with the PR badge.

The stall card needs four or more stalled sessions, so it's covered by tests only.

**M3a results (2026-10-17, reference phone on Android 15, release build over M2 data):**

| Check | Result |
|-------|--------|
| Upgrade from M2 | ✅ The `training` settings moved into the persona and migration 3→4 ran. The first launch opened onboarding with the training setup kept: 7 steps, no equipment step. |
| Onboarding | ✅ The three questions (both, under a year, BMI estimate) suggested a recomp with its reason. Pace cards showed projections, and Aggressive showed its warning. The finish line pre-filled the suggested goal weight. The review kept the existing Saturday yoga class. Targets for TRD vector A's inputs matched the golden vector: 2,090 kcal, 125 g protein, 58 g fat, 267 g carbs. |
| Today | ✅ Protein left as recomp's main number, with the ramp note and the 7-day average line; the rotation carried on. |
| Settings | ✅ A body-data change previewed 2,090 → 2,070 kcal and saved; changing it back replaced the same day's version. A protein override saved and reset. Settings → Goal to Maintain showed the ±5% range on Today, then back to recomp. |
| Reinstall | ✅ Onboarding stayed done. |
| Not run on the phone | A fresh install's equipment step and a split change, because both rebuild the phone's routines. The repository tests cover them. |

**M3b results (2026-10-18, reference phone on Android 15, release build over M3a data):**

| Check | Result |
|-------|--------|
| Upgrade from M3a | ✅ Migration 4→5 ran with targets and history intact. Today gained *Log food* and the bottom bar gained Food. The first launch imported INDB's 1,014 recipes and the 28 basics. |
| Diet filter | ✅ As the reference persona (lacto-vegetarian), the empty search listed only vegan and vegetarian basics, "chicken" found nothing, and "egg" found only eggless cakes and eggplant rice. |
| Logging | ✅ An INDB dal prefilled its own bowl; 1½ katori marked Ordered showed the oil note and logged 225 g at the database's values. A quick add was corrected from the day view. |
| Fried recipes | ✅ Every poori carried the frying-oil warning. |
| Saved meal | ✅ Lunch saved as a meal, then logged into Dinner in one tap. |
| Day view and Today | ✅ Per-meal totals, the macro split with "180 kcal from quick adds isn't split", and Today switching recomp's main number to the ramp's measuring week once food was logged. |
| Custom food | ✅ A per-serving food (1 piece = 60 g) opened its portion sheet at 210 kcal. |
| Settings → Food | ✅ Meals, portion sizes and the food data note. |
| Not run on the phone | Copy from another day (the phone had no earlier day with food) and the 7-day average with food; the repository tests cover both. The test entries and saved meal were deleted afterwards, and the test food hidden. |

**M3c results (2026-10-18, reference phone on Android 15, release build over M3b data):** Describe it is text only; meal photos stay P1 (decided with the user). Tests cover:
- `:domain`: every gate state and its order, pricing with cache tokens and fallback attempts, validation, and the prompt for each diet and region.
- The Claude client against a local mock server:
  - the request shape: the hand-written schema, effort `low`, the cached system prompt, and `fallbacks: "default"` for Opus but not Haiku;
  - refusal, `max_tokens` and unreadable replies;
  - 401, 402, 403 and 400 without a retry;
  - 429, 5xx and a refused connection retried once at no cost; a timeout or a connection dropped after sending not retried and costed at its upper bound;
  - a key OkHttp can't put in a header failing quietly, without the key in anything kept.
- The repository: off, no key, over cap and offline never send; every failure is logged and costed; the cap lifts on the 1st; the prompt's own foods; saving with the learning loop; *Save + My meals*; drafts; the encrypted key.
- The Describe screen: a failure keeps a draft, *Cancel* keeps nothing, *Save draft*, and *Estimate now* replaces its draft rather than adding one.
- Migration 5→6.

| Check | Result |
|-------|--------|
| Upgrade from M3b | ✅ Migration 5→6 ran with targets and food data intact. Settings gained an AI section, off by default. |
| Settings → AI | ✅ The model picker shows each model's rough cost (Opus 5.5 about 3¢ an estimate, Sonnet 5.5 about 1¢, Haiku 5.5 under 0.1¢). The key field is masked, and a saved key reads *Saved · Replace*. |
| Describe it with a dummy key | ✅ The privacy notice showed once, then "Estimating with Claude Opus 5.5…". The API's 401 came back as `AUTH`: the description became a draft in Snacks ("Your API key was rejected"), and the add-food screen was back with search, My meals and Quick add. *Estimate now* failed the same way, replaced the draft rather than adding one, and the Food tab said why. Settings showed $0.00 for 2 requests. The first run found that the outcome message never reached the screen it returned to (a screen's view model has a different saved-state handle from its nav entry); fixed and re-checked. |
| No key | ✅ The button reads *Describe it · keep a draft*; the screen says why, offers *Add key*, and its action is *Save draft*. |
| Not run on the phone | A real estimate, which needs your key. The offline and over-cap states (the tests cover them; the phone's airplane mode wasn't touched). Review edits and the learning loop (the tests cover them). The test draft was deleted and the dummy key removed; AI is left on with no key. |

**M4 results (2026-10-18, reference phone on Android 15, release build over M3c data). With M4 the MVP is complete; dogfooding starts (PRD §9: every workout and every day of food for 2 straight weeks).** The UX calls were settled with the user first (PRD v1.0): a Body tab, the rest-day rule, the trend weight only on a target change, and a safety copy on import. Tests cover:
- `:domain`:
  - `TrendCalculator`: daily, weekly and mixed spacing, seeding, the primary-source and first-of-day filters, interpolation, the rate anchored at the last weigh-in, and the pace for each goal.
  - `ScanProtocol`: the 90-minute edges, the midnight wrap, meal, workout, every reason at once, and no slot.
  - `WeeklyPlan.restDay`.
- Repositories:
  - weigh-ins and the trend, edit, delete and undo;
  - the trend weight in target previews, never a version on its own;
  - scan, slot and protocol, the linked weigh-in and its cascade, and trained-before from the workout log.
- Migrations: 5→6 (M3c), 6→7, and the 5→6→7 chain.
- Backup:
  - the round trip, with every table filled including M3c's;
  - the safety copy, and an older (v5) file upgraded on import;
  - refusals that leave the phone's data untouched: newer, not a zip, damaged JSON, a non-CREATE schema, a column mismatch, a broken foreign key, an unknown enum value, and settings of the wrong type or out of range;
  - an active workout blocks import;
  - the AI key is never exported.

An independent review before the phone run found that a backup with a broken foreign key could leave the database stuck in a transaction, and that out-of-range values could crash the app at launch. Both are now refused before anything is replaced (§6.5).

| Check | Result |
|-------|--------|
| Upgrade from M3c | ✅ The release build installed over the M3c (v6) data, migration 6→7 ran, and Today opened with the targets intact and a rest day ("Strength done for today", *Start Pull anyway*) after the day's earlier workout. The bottom bar gained Body. |
| Export → import | ✅ Settings → Data → *Export all data* saved `recomp-backup-2026-10-18.zip` to Downloads through the system dialog ("5 workouts, 0 food entries, 0 weigh-ins, 0 scans"). A weigh-in logged after it was gone after *Import a backup* → *Replace*. Today and Body refreshed without a restart, and *Restore the data from before the import* appeared. |
| Weigh-in sheet | ✅ It prefilled the typed weight, with the primary source's chip selected, and the trend started from it. |
| Body and Today with five weeks of invented data | ✅ Checked in a separate debug install (`app.recomp.m4dev`, uninstalled afterwards). The chart drew home-scale weigh-ins as filled dots on the trend line and gym-scale ones as hollow dots. Today's weight card read "−0.5 kg this week (−0.6%) · goal −0.5% a week". <!-- pii-ok: invented --> The scan table marked an off-protocol scan with its reasons. The phone run found that the table was too wide for the card; fixed so it fits at the default font size. |
| Scan form | ✅ Body fat % filled in from fat mass ÷ weight, and the live verdict read "Off protocol: 7 h 43 min from your usual time" for a scan typed in at night. The time dialog takes typed input. |
| Trend weight in Settings (dev install) | ✅ About you showed "82.3 kg · trend" with a note that the targets still used the typed weight. Tapping it previewed 2,100 → 1,980 kcal with protein unchanged (it follows the goal weight). After saving, the note went away. <!-- pii-ok: invented dev-install values --> |
| Not run on the phone | Saving a scan (the dev run backed out of the form) and the waist sheet; the tests cover both. A backup from an older version and the refusals (the tests cover them). Android's own Auto Backup restore. The test weigh-in was removed by the import; the backup file stays in Downloads, and the app's safety copy holds the state from just before the import. |

### The v1.1 plan (settled 2026-10-18)

It covers every P1 requirement in the PRD, in the 16 milestones of the table above. The UX for each is in PRD §12, settled with you on 2026-10-18.

**When code starts:** now. On 2026-10-18 you waived §11's rule of two weeks of daily use first, so v1.1 is built while the MVP is dogfooded. Fixes the dogfooding turns up go first.

**Order.** By value for someone using the app every day, within the dependencies, in pairs:
1. **M5 and M6.** The formula's maintenance guess is off by 10–15% for most people (PRD §8, #5), and every day's logging is judged against the target M5 corrects. M5 needs 3 weeks of data, which dogfooding provides by about 8 November. M6 changes every gym session.
2. **M7 and M8.** The provider choice comes before more AI features are built on it, and the review completes the v1.1 exit criterion (PRD §9).
3. **M9 and M10:** faster food logging, on any provider, and training history.
4. **M11 and M12:** the weekly programme review, and the nudges that get protein hit (PRD §8, #1).
5. Then Health Connect and recipes, InBody and data, body extras and camera food, goals, and imperial units last.

**Dependencies:**
- M8 extends M5's check-in screen. M5's flags ship with the adaptive target, because they decide when it proposes a change.
- M9 and M11 build on M7's providers, and M11 on M8's review. M10 uses M8's chart.
- M13 adds WorkManager for its daily sync, which M16's backups also use. M12's reminders use AlarmManager.
- M13 (Health Connect import) comes before M15 (scan-informed targets).
- M10 (editing past workouts) and M13 come before M16, whose Health Connect writes follow those edits.
- M15 brings the share target (§6.7); M18 adds its other two choices.
- M16 (automatic backup) comes before M17, which adds photos to it.
- M19 needs M5: Goal reached and the maintenance break appear in the check-in. Your current goal started on about 17 October, so its 12 weeks end around 9 January 2027. If M19 lands later, the break is suggested at the first check-in after it lands.
- M8's review and M12's nudge build their steps and sleep parts against `daily_activity` and hide them while it's empty; M13 tests that they appear. Other apps' workouts count through the weekly plan once M13 imports them.
- M20 changes every screen with a weight or a length, so it runs alone and last.
- M5 keeps its Today card in `ui/checkin`, so M11 changes the card without touching `TodayScreen.kt`. M8 still edits Today's weekly-plan card, which M7 doesn't touch.

**Every milestone:**
- Its own worktree and branch from `origin/main`, e.g. `git -C ~/Desktop/projects/recomp worktree add ~/Desktop/projects/recomp-m5 -b m5-checkin origin/main`, with a gitignored `local.properties`.
- One PR into `main`, never stacked on another branch: that's how #6 merged into its base instead of `main`.
- Real Room migrations, tested from the exported schemas, with the schema JSON checked in. Never a destructive fallback. A new table also gets a `BackupCheckDao` line and rows in `BackupTest.fill()`.
- Tests for the risky logic, listed per milestone below.
- `./gradlew :domain:test :app:testDebugUnitTest :app:assembleRelease :app:lintDebug` passing, with 0 lint errors.
- An on-phone check of the release build over the existing data, then a results note in this section.
- `python3 .githooks/sensitive_scan.py all` before every commit. Test data is invented, including the parser's corpus of meal phrasings. Nothing personal is committed.
- Any AI work uses hand-written JSON schemas decoded with kotlinx.serialization (§6.2) and keeps keys in the `secrets` store. Anthropic code loads the `claude-api` skill first; other providers' code follows their current API docs.
- New screens format weights and lengths through the shared formatters (`Labels`, `BodyText`), so M20 can change units in one place.
- Update §4 (data model) and any section the milestone changes, so the docs describe what's built.

**The phone now holds real data.** From M5 on, every install on the reference phone upgrades the app you use every day.
- Before installing a build with a schema change, export a backup on the phone (Settings → Data).
- The phone's database moves only along `main`'s chain. A schema change is installed only from a branch rebased onto a `main` that already has every lower version. A build whose version is lower than the phone's can't open its database (M3c's note).
- Each session records its installs (milestone, Room version, date) in `phone-ledger.md` in the shared notes folder, so parallel sessions don't install over each other.
- Screens that need weeks of data are checked in a separate debug install with invented data (`applicationIdSuffix`, never committed), as in M4.

**Room versions,** in the planned merge order: M5 takes 8, M6 9, M7 10, M11 11, M13 12, M14 13 and M17 14. The other milestones need none. If two merge in a different order, the later PR renumbers its migration and schema file before it merges.

**Parallel waves.** Two sessions at a time (agreed on 2026-10-18):

| Wave | Milestones | Why they don't collide |
|------|------------|------------------------|
| 1 | M5 ∥ M6 | Nutrition and the check-in, against the workout screen and the engine |
| 2 | M7 ∥ M8 | The AI layer and Settings → AI, against the review, Body's chart and Today's weekly-plan card |
| 3 | M9 ∥ M10 | Food screens and the parser (M9 stays out of Today), against the Train tab and history |
| 4 | M11 ∥ M12 | The programme review (the review screen, AI and routine edits), against notifications, the widget and Today's evening card |
| 5 | M13 ∥ M14 | Health Connect and Body, against the recipe editor |
| 6 | M15 ∥ M16 | Scans and the share target, against backups and Health Connect writes |
| 7 | M17 ∥ M18 | Body, against food |
| 8 | M19, then M20 | Goals touch the goal flow and Today; units touch every screen |

Parallel milestones share only the registration files, with small additive edits that are resolved on rebase: `RecompNavHost.kt` (routes), `SettingsScreen.kt` (rows), `SettingsPrefs.kt` (keys and `problems()`), `AppDatabase.kt`, `Migrations.kt`, `BackupCheckDao.kt` and the migration and backup tests (schema milestones only), `AndroidManifest.xml`, `app/build.gradle.kts`, `libs.versions.toml`, the README's status and these docs. Each rebases onto `main` before its PR and again before its phone check.

#### M5: Weekly check-in and adaptive target

*NU-05, GL-08 (check-in flags); PRD §12.1; Room v8.*
- **Scope:**
  - `AdaptiveTdee` and `CheckinFlags` in `:domain` (§5.4).
  - `CheckinRepository` computes a week's row on the first launch after the week ends, and catches up missed weeks in order.
  - The check-in screen (`ui/checkin`) and its Today card. *Accept* and *Adjust* write a `nutrition_target` row with `reason = CHECKIN` and `tdee_estimate = tdee_new`.
  - Incomplete days, from the Food tab's day menu, are left out of the window and out of the 7-day average.
- **Data (v8):**
  ```text
  weekly_checkin  week_start (PK, the Monday), computed_at, window_days, logged_days, weigh_ins,
                  kcal_mean?, trend_start_g?, trend_end_g?, tdee_obs?, tdee_prev, tdee_new,
                  flag?(LOSS_SLOW|FLAT|DRIFT|GAIN_SLOW|GAIN_FAST), proposed_kcal?,
                  decision(PENDING|ACCEPTED|ADJUSTED|KEPT|LAPSED), decided_at?, target_id?
  daily_activity  log_date (PK), food_incomplete, steps?, steps_synced_at?, sleep_hours?
                                        -- §4's planned table plus the flag; M13 adds sleep_source in its own migration
  ```
  - Setting `checkin_seen`: the Monday of the latest check-in you opened, so Today's card goes once it's seen. It's in `settings` and exported with the rows it refers to, and an import refuses one that isn't a date.
  - `tdee_prev` is the previous row's `tdee_new`, or for the first check-in the `tdee_estimate` of the target in force.
  - A decided row is frozen. A pending one is recomputed when opened, so late logging counts.
  - The window is 21 days when the primary source has 10 or more weigh-ins in it, else 28 days with at least 4 (§5.4).
- **Tests:**
  - synthetic intake and weigh-ins with a known TDEE: daily and weekly weighing, gaps, an early water drop, steady under-logging;
  - the 80% rule and the ±150 kcal clamp;
  - each flag at its edges;
  - catch-up over missed weeks, and a decided row staying frozen;
  - migration 7→8, and the backup round trip with the new tables.
- **Risks:**
  - The trend lags, so a diet's first weeks overstate the loss. The 0.5 step and the clamp limit the effect.
  - A real proposal needs 21 days of data, so the phone check of proposals uses a dev install with invented data.
  - `inDeficit` stays on the target row's TDEE (§5.1). An accepted check-in refreshes it.
- **Parallel:** with M6.

**M5 results (2026-10-18, reference phone on Android 15, release build over M4 data).** Built as §5.4 and PRD §12.1 describe, with these choices where they left room:
- **A calorie override you set** stays on *Accept*; *Use the new target* (shown only when you have one) resets it; *Adjust* sets the calories itself, so it replaces the override, and the screen says so.
- **A pending week is recomputed** one last time before it lapses, so food logged late still counts in the estimate the next week starts from.
- **Catch-up runs** at launch (after any unfinished import), after an import, whenever Today's log date changes, and before a check-in opens. So a Monday that starts while the app is open computes the week too, and an older pending week lapses rather than being answered from a stale estimate.
- **The guardrails apply as in Settings** (PRD §4.9): every week is judged and proposed with the stored goal after `Guardrails.apply` at the trend weight. *Accept* writes the guarded goal and stores it, as a Settings save does, and the screen shows the note.
- **A clock that ran ahead:** catch-up drops unanswered rows (pending, or lapsed by the app) for weeks that haven't ended *and* that were computed later than now. It keeps your answers, and reopens the week those rows had lapsed. A log date that only moves back, e.g. "day starts at" changed on a Monday morning, leaves every row as it was.
- **One answer at a time:** the buttons wait while an answer saves. A second tap is refused quietly, because the screen already shows the first answer. A failure after the target is saved says so rather than "unchanged".
- **Settings → Calories** shows what a change would save. When that differs from today's target, as it does while a proposal waits or after *Keep* or *Adjust*, it says "Today's target is 2,000 kcal until you answer the check-in or change something here".

Tests cover:
- `:domain`:
  - `AdaptiveTdee` on a simulated body with a known TDEE: daily and weekly weighing, gaps in both, steady under-logging (the deficit against logged calories is still the real one), the 80% rule on both windows, incomplete days, the window choice, the clamp, and the gap stored on each row.
  - An early water drop of 1.5 kg reads as up to about 260 kcal of extra burn. The half step keeps the estimate's peak lower and later, and it's gone once the drop leaves the window.
  - A trend only four weeks old still lags, so a new user's first estimate reads up to about 150 kcal low.
  - `CheckinFlags`: each flag at its edges, and rates, the 4-week change and waist pairs from weigh-ins.
  - `CheckinProposal`: TRD §5.3 vector A's inputs at a new estimate, the floor, the 50 kcal rule, overrides, and the *Adjust* range. It always takes in the proposal, checked for every goal and pace at every maintenance from 1,200 to 3,600.
- Repository:
  - catch-up in order, the day boundary, and catch-up on open;
  - the estimate's chain, frozen rows, recompute on open, and lapse;
  - proposals only on weeks that could be seen;
  - a clock that ran ahead and came back, and a log date that moved back across Monday midnight;
  - Today's card;
  - *Accept*, *Use the new target*, *Adjust* (clamped) and *Keep*;
  - two answers at once, and settings that fail after the save;
  - the decision's log date;
  - the guardrails: the review's probe of a cut at BMI 18.48 is judged as Maintain with no deficit, and an accepted Maintain proposal writes and stores Maintain;
  - incomplete days in the estimate and in Today's average, keeping the day's other columns;
  - Settings' targets from the latest estimate, including a kept week's and past a later week without one.
- Migrations: 7→8, and 6→7→8 in one open.
- Backup: the round trip with both new tables and `checkin_seen`, and a bad `checkin_seen` refused.

| Check | Result |
|-------|--------|
| Upgrade from M4 | ✅ A backup was exported first (Settings → Data). The release build installed over the M4 (v7) data and migration 7→8 ran: Today opened with the targets intact, and the Food tab's day menu had *Mark day as incomplete*. The first real check-in comes the Monday after the first full week. |
| Check-in with six weeks of invented data | ✅ Checked in a separate debug install (`app.recomp.m5dev`, uninstalled afterwards). Launch caught up five weeks. The first two had too little food for an estimate, and the two days marked incomplete were left out of the next ones (20, 19 and 19 of 21 logged). The estimate went 2,460 → 2,376 → 2,335 → 2,318 kcal, and the recomp's flag fired. Today's card read "Week of 5 Oct · a new target to look at". The screen read "About 2,320 kcal a day, from 21 days of food and weigh-ins (the formula said 2,430)" and "Lower your target to 1,870 kcal (now 2,000)". <!-- pii-ok: invented dev-install values --> |
| Answers | ✅ *Adjust* stopped at the 1,770 floor and saved 1,820 as a check-in version carrying the new estimate. *Use the new target*, with a 2,100 override, saved 1,870 and removed the override. *Keep* left the target alone. <!-- pii-ok: invented dev-install values --> |
| Settings | ✅ Settings → Targets → Calories read "Maintenance is about 2,320 kcal, from your check-in of 5 Oct (the formula says 2,460)". The *Weekly check-in* row opened the check-in, and after *Accept*, Settings and Today both showed 1,870. <!-- pii-ok: invented dev-install values --> |
| Incomplete days | ✅ The Food tab's day menu marked a day incomplete and back, with its note. |
| Fixed during the phone run | The outlined buttons had no visible border on the tinted proposal card, and the Settings row's long value squeezed its title onto one letter per line. Both fixed and re-checked. |
| Review fixes | A fresh-session review of #10 found 0 blockers, 3 should-fix and 6 nits, all fixed with tests: the guardrails, the answer flow, catch-up triggers, the *Adjust* range, the decision date, a changed goal's verdict, the incomplete-day write, proposals on caught-up weeks, and the clock. |
| Not run on the phone | A real check-in, because the first week ends after the install. Lapse and catch-up over real weeks. Settings' note when its number differs from today's target, and the review fixes: the phone was unplugged. The tests cover them. |

#### M6: In the gym

*RT-05, WL-11, WL-09 (assisted), PO-08, EX-04; PRD §12.2; Room v9.*
- **Scope:**
  - Superset groups in the routine editor and the workout: selection order, rest after a round, save-back.
  - `WarmupPlanner` in `:domain`.
  - `ASSISTED` in the engine, PRs, the set dock and the exercise editor's load-type chips, plus seed entries for an assisted pull-up and an assisted dip (machine). Seeding adds missing keys at launch, so no migration.
  - Assisted e1RM, PRs and stall detection compare every session at today's trend weight minus its assistance, so sessions are judged at the same body weight: in effect, on assistance and reps, and a cut doesn't read as a stall. M10's chart plots the assistance itself.
  - The double step in `ProgressionEngine` (§5.1's P1 line).
  - A *Same movement* section in `ExercisePickerSheet` (`ui/components/ExercisePicker.kt`).
- **Data (v9):** `session_exercise.superset_group INTEGER`, nullable, copied from the routine at start like the other targets (RT-03), so grouping mid-workout leaves the template alone. `routine_exercise.superset_group` already exists.
- **Tests:**
  - engine branches for assisted lifts: less assistance as progress, the big-jump guard on the effective load, PRs with and without a weigh-in;
  - the double step: RIR 3+ on every set, the 5% limit, a policy without it;
  - warm-up rounding and the two-step cut-off;
  - superset selection and rest order, and save-back keeping the groups;
  - the migration to v9.
- **Risks:**
  - The workout screen is the most-used screen, so supersets must not slow a plain workout. Compose UI tests cover tick → next set both ways.
  - Assisted e1RM needs the trend weight, which may be missing; it's hidden until the first weigh-in.
- **Parallel:** with M5.

**M6 results (2026-10-18; Room v9, after M5's v8).** Built to PRD §12.2. Where the spec was silent:
- **Assisted lifts:** before a weigh-in they move one step at a time; a deload adds about 10% of the effective load as assistance (§5.1).
- **Warm-ups:** offered only for weighted lifts, since a share of an added plate or of the assistance isn't a warm-up, and the line goes away once the exercise has a ticked set.
- **Moves:** never break a superset (§6.1).
- **After a fresh-session review:**
  - a superset round started on its later exercise goes back to the earlier one with no rest;
  - the notification names the set in superset order;
  - warm-up *Add* can't add twice;
  - barbell warm-ups start at the empty bar, settled as a PRD §12.2 clarification;
  - zero assistance reads "BW".

Tests cover:
- `:domain`:
  - assisted lifts: less assistance as progress, the guard on the effective load, deload, the harder variation at zero, the stall score and PRs with and without a weigh-in, and volume from what each set lifted;
  - the double step: RIR 3+ on every top set, a missing RIR, a policy without it, the 5% limit, back-off sets;
  - `WarmupPlanner`: rounding, the two-step cut-off, duplicate loads, the barbell's empty bar;
  - `Supersets`: join, split, normalize, block moves, and the tick order with rest, including warm-ups, uneven set counts and a round started on its later exercise.
- Repositories:
  - grouping mid-workout leaves the routine alone until saved back, save-back keeps the groups, a swap keeps its group, and removing one of two ends the superset;
  - warm-up insertion, and *Add* twice or after a ticked set adding nothing;
  - assisted progression, PRs, history and overrides;
  - RIR through history to the double step;
  - the frozen JSON's new fields and *Same movement*.
- Migration 8→9 and the 7→8→9 chain, from the exported schemas; the backup round trip with a superset group.
- The notification's current set in superset order.
- Compose UI tests on Robolectric: tick → next set with rest in a plain workout; tick → the next exercise's set with no rest, then rest after the round, in a superset; the warm-up line's *Add*.

| Check | Result |
|-------|--------|
| Upgrade from M5 (release over the real data) | ⏳ After #10 merges and M6 is rebased to v9: backup first, then the release build. |
| Warm-ups and the double step (dev install `app.recomp.m6dev`, invented data, uninstalled afterwards) | ✅ The bench card read "Today: 55 × 8 · weight up (2 steps: every set had 3+ reps left)" and "Warm-up: 20 × 8 · 32.5 × 5 · 42.5 × 3". *Add* put three warm-up sets ahead of the working sets and selected the first, and the line went away. On Pull the line went to the machine row, the first weighted compound lift. |
| Supersets (dev install) | ✅ *Superset with next* from the bench card's menu bracketed bench and shoulder press. After the warm-ups, ticking bench set 1 selected the shoulder press's set 1 with no rest, and ticking that started its 2:00 rest on bench set 2. *Save today's changes to Push* kept the superset in the routine, and the routine editor's *Superset with next* and *Split superset* worked. |
| Assisted lift and swap (dev install) | ✅ The assisted pull-up read "Today: assist 20 × 8 · less assistance", with the stepper at "Assist 20 kg". The summary showed "least assistance, assist 20 kg", and on the next session a rep PR and "best estimated 1RM, about 78 kg" from the trend weight. *Swap* on the pec deck listed *Same movement* (cable fly, dumbbell fly) first. The run found the summary's volume counting the assistance as load; fixed to count body weight minus assistance (542 kg for 9 reps at assist 20 with an 80.2 kg trend). <!-- pii-ok: invented dev-install values --> |
| Not run on the phone | Supersets of three, uneven set counts and a split mid-workout (the tests cover them); an assisted lift before a first weigh-in (tests); the stall card's *Swap* (same picker as the card menu's). |

#### M7: AI providers

*AI-05; PRD §12.3; Room v10.*
- **Scope:**
  - An `AiProvider` interface in `data/ai`, behind `MealEstimator` and every later AI use. One call takes a system prompt, a user message, optional images and a hand-written JSON schema, and returns the parsed reply, its usage and an `AiStatus` (§6.2).
  - Adapters: Anthropic (today's Java SDK code, moved behind the interface); OpenAI and Gemini over OkHttp with kotlinx.serialization, with no new SDKs; and OpenAI-compatible, with a base URL.
  - Each provider's structured outputs: Anthropic's `output_config.format`, OpenAI's `response_format` with a strict `json_schema`, and Gemini's current structured-output field (its reference marked the older `responseSchema` deprecated in October 2026). Shared schemas carry no array-length or number-range constraints, which Anthropic rejects; code checks those. An OpenAI-compatible server that rejects the schema falls back to plain JSON mode; the reply is validated the same way either way (`MealCheck`).
  - `AiKeyStore` keeps one encrypted key per provider. `AiPricing` gains the new providers' models; the compatible provider's prices are typed in.
  - Settings → AI's provider picker. `ai_notice_seen` becomes a set of (provider, kind), so each kind of request shows what it sends before its first send to each provider (§6.2).
  - Per-provider usage mapped to input, cached input and output with reasoning included, each provider's effort setting for meal requests, and its own upper bound for the cap's reservation (§6.2). OpenAI counts cached tokens inside `prompt_tokens` and reasoning inside `completion_tokens`; Gemini reports thinking tokens apart from the output and bills both as output. A wrong mapping would let the cap under-count. The status messages name the provider in use ("Your OpenAI account is out of credit").
  - An OpenAI-compatible URL must be `https://`, except plain `http://` to `localhost`, a private-range IP or a `.local` name, and a key is never sent over plain HTTP. A network security config can't name a LAN IP, so cleartext is allowed in the base config and the rule is enforced in code. Android 17 blocks local-network traffic for apps targeting API 37 unless they hold `ACCESS_LOCAL_NETWORK`, so M7 declares it, asks for it when the URL is local, and says so when it's refused (not a timeout).
- **Data (v10):** `ai_request_log.provider TEXT NOT NULL DEFAULT 'ANTHROPIC'`, so spend and debugging know which service answered. Settings gain `ai_provider`, a model per provider, `ai_base_url` and the compatible provider's prices. Keys stay in the `secrets` store, out of exports.
- **Tests:**
  - each adapter's request shape against a mock server: schema, images, and the key never in anything logged;
  - each provider's errors mapped onto the existing `AiStatus` table: rejected key, quota, overload, timeouts, refusals and safety blocks, cut-off replies;
  - the schema fallback for compatible servers;
  - a price for every listed model;
  - switching provider keeps both keys;
  - the URL rules: `https` anywhere, `http` only to local addresses, and never a key over `http`; a refused local-network permission giving its own message;
  - the migration to v10.
- **Risks:**
  - Structured outputs and error formats differ by provider. The shared validation and status table keep the app's behaviour the same.
  - Model names and prices change. The lists live in `AiPricing`, updated by hand, and an unknown model is priced at the highest rates (§6.2).
  - Some providers' free tiers may keep prompts for training. The milestone checks each one, and the notice says so where it applies.
  - Anthropic code loads the `claude-api` skill first. The other adapters are written against each provider's current API docs, cited in the results note.
- **Parallel:** with M8.

#### M8: Weekly review

*HM-03, HM-04, IB-04; PRD §12.4; no Room change.*
- **Scope:**
  - The review sections on M5's screen, and a `ReviewDao` of read-only weekly aggregates: sessions, PRs, stalls, origin shares, protein days.
  - The consistency calendar.
  - The finish-line projection, recomputed weekly from the trend (PRD §4.2, §5.8): "At this rate: around mid-March", or "Moving away from your goal". It replaces the pace picker's one-off projection as the weekly view.
  - Steps and sleep sections built against `daily_activity`, hidden while it's empty (M13 fills it).
  - `ScanTrends` in `:domain`: §5.6's deltas, noise labels and slope.
  - A reusable chart (`ui/components/Chart`) grown from M4's weight chart, still drawn on `Canvas`.
- **Data:** none. Everything is computed on read.
- **Tests:** each aggregate on fixed data; protein days hit leaving out the ramp's baseline week; the projection, including moving away; weeks that span a goal change or a gap; noise labels at the 1 kg and 1% edges; off-protocol scans left out; the slope.
- **Risks:** the review runs many queries. Each covers one week and uses the `log_date` indexes, so it should open well inside a second; the phone check measures it.
- **Parallel:** with M7.

#### M9: Faster food logging

*AI-04, NU-18, NU-06; PRD §12.5; no Room change.*
- **Scope:**
  - `DescribeParser` in `:domain` (§6.2's local parser), over a food index the repository supplies.
  - Describe it with AI off. With AI on, the parser goes first and the AI estimates what it couldn't match: it gets the whole description, with the matched items listed as already counted, and returns only the rest in `MealSchema`. An *Ordered* meal goes straight to the AI.
  - Slot rules for "the meal for the time of day": each meal slot gets a usual time range (editable with the slots), and a time falls to the last slot that starts before it. M12's reminders use the same rule.
  - Will it fit? in the Food tab, as `AiKind.FIT_CHECK` with the meal schema.
  - The snack allowance: a `snack_allowance` setting, the day view and the chosen meal.
- **Data:** none. Items log with `source = LOCAL_PARSE` or `AI_TEXT`, and a fit check you log keeps its `ai_request_id`.
- **Tests:**
  - the parser against an invented corpus: fractions, numbers in words, "and", units and synonyms, unknown chunks, the diet filter;
  - merging parser and AI items without counting anything twice;
  - Will it fit's before and after for each goal's main number;
  - the allowance inside the target.
- **Risks:** the parser can pick the wrong dish ("dal" for "dal makhani"). It prefers your own foods and exact names, and shows its pick for review.
- **Parallel:** with M10.

#### M10: Training history

*WL-10, PO-09, PO-10; PRD §12.6; no Room change.*
- **Scope:**
  - A History list on the Train tab, and an edit screen for a completed session: sets, exercises, times, notes, and delete.
  - `PrRebuilder`, which recomputes an exercise's PRs from the edited session onwards, keeping stall reset points valid.
  - The exercise chart, using M8's component.
  - `MuscleVolume` in `:domain` (§5.5), with its Train card and the band setting.
  - §4.8's progression message in a deficit: the summary and stall card say "holding strength in a deficit is a win" when the target is in a deficit.
- **Data:** none. Each save is one transaction on the existing tables. Deleting a session cascades to its exercises, sets and PRs.
- **Tests:**
  - PRs after editing, adding and deleting sets in an older session, so a later PR disappears or appears; the incremental rebuild matches one from scratch;
  - deleting the last strength session moves the rotation back;
  - frozen recommendations unchanged;
  - volume counting: secondary muscles, working sets only, the week boundary.
- **Risks:** this rewrites PR history; the from-scratch comparison is the guard. M16's Health Connect writes must follow these edits later.
- **Parallel:** with M9.

#### M11: Weekly programme review with AI

*HM-06; PRD §12.7; Room v11.*
- **Scope:**
  - `ProgrammeReport` in `:domain` builds the ticked sections as text from M8's weekly aggregates, the routines and the targets. It never includes the fields PRD §12.7 lists as never sent.
  - The checklist, the full-text preview and *Send*, as `AiKind.COACH` through M7's providers, at a higher effort than meals (e.g. `medium`) with a 60 s timeout, a progress line and its own upper-bound cost.
  - The hand-written schema: `summary`, and `suggestions[] {kind, routine_ref, exercise_ref, replacement_ref, value, reason}`. `kind` is an enum (`CALORIES`, `PROTEIN`, `SWAP`, `ADD`, `REMOVE`, `SETS`, `REP_RANGE`, `STRENGTH_DAYS`, `DELOAD_EXERCISE`, `DELOAD_ALL`, `ADVICE`); `value` is `{min, max}` with min = max for a single number; unused fields are `""` or 0, like §6.2's convention. Exercises are report-local refs (`e1…eN`) for the routines' exercises and the library the report lists, mapped back on apply, so custom exercises work too. Array lengths and number ranges aren't in the schema (structured outputs don't take all such constraints); `SuggestionCheck` enforces at most 5 and every range.
  - Targets go in relative terms by default: the deficit or surplus as a % of maintenance, and protein in g/kg. Absolute calories and grams go only when *Body trends* is ticked, since they reveal your weight. A `CALORIES` or `PROTEIN` suggestion's `value` is in the same terms the report used (% of maintenance and g/kg, or kcal and g), and `SuggestionCheck` converts it with the local numbers before the preview.
  - `SuggestionCheck` validates each suggestion against the library, the routines and the guardrails. Anything else is advice only.
  - *Apply* uses the existing paths: `PersonaRepository`'s target preview and save (`reason = REVIEW`), `TrainingRepository`'s routine edits with *Undo*, and Training setup for strength days. A deload is marked on the exercise (`deload_pending`), and the next workout start turns it into the stall card's deload (90% and a reset point), then clears it. *Deload the whole week* marks every exercise in the active routines.
  - Scheduled deloads from the experience policy (`ExperiencePolicy.deloadEveryWeeks`, PRD §4.3), on a Train card: a deload week every 6–8 weeks for intermediate lifters and 4–6 for advanced. Accepting marks every exercise `deload_pending`, like the review's. It's offered, never applied on its own, and never shown to beginners, whose policy deloads only on a stall.
  - The prompt on M5's check-in card, which lives in `ui/checkin`, so M11 stays out of `TodayScreen.kt`.
- **Data (v11):**
  ```text
  programme_review  id, week_start, created_at, sections, sent_text, ai_request_id,
                    response_json?, applied_json   -- which suggestions you applied
  ```
  `exercise.deload_pending INTEGER NOT NULL DEFAULT 0`. `TargetReason` gains `REVIEW`.
- **Tests:**
  - the report for each combination of sections: body trends only when ticked, and the never-sent fields never present;
  - the schema and parsing;
  - every suggestion kind validated and applied, and *Undo* for routine changes;
  - a pending deload turning into the next workout's targets, once;
  - the scheduled-deload card's timing for each experience level, and none for beginners;
  - targets sent in relative terms unless body trends are ticked;
  - unknown exercises and values outside the guardrails downgraded to advice;
  - the request against a mock server;
  - the migration to v11.
- **Risks:**
  - A suggestion can be poor. Nothing applies without your tap, the guardrails hold, and the progression engine stays rules.
  - The report grows with many routines. It's capped at about 4,000 tokens, trimming per-exercise detail first.
- **Parallel:** with M12.

#### M12: Nudges, reminders and the widget

*RM-01, NU-23, GL-08 (nudges), PO-11, IB-06, HM-05; PRD §12.8; no Room change.*
- **Scope:**
  - Reminders on AlarmManager (`setAndAllowWhileIdle`, §6.6), rescheduled at boot and when the time zone or a reminder changes.
  - A `ReminderScheduler`, the *Reminders* and *Weekly check-in* channels (§6.6), and the 2-a-day ledger.
  - `Nudges` in `:domain`: the goal policy's nudge list, the 25% threshold, and the food ranking and portion sizing.
  - Today's evening card, and a Glance widget. Both read a new `TodaySnapshot` (M12 adds it), which the notifications use too. The widget updates when food is logged, a workout finishes, the targets change and the day rolls over.
  - Reminder slots use M9's slot rule (each meal slot's usual time range), so a renamed or removed Lunch still works.
- **Data:** settings only: `reminders` (each one's on or off, time and weekdays). The daily ledger is device state, in the `device` DataStore (§4), out of exports and Android's own backup.
- **New dependencies:** `androidx.glance:glance-appwidget`. New permission: `RECEIVE_BOOT_COMPLETED`, to reschedule reminders.
- **Tests:**
  - the skip rules and the cap, in time order, over a simulated day;
  - nudge content for each goal, and food ranking and portions;
  - reminder times across the day boundary and a time-zone change;
  - the widget's state, after a food log, a finished workout and a target change.
- **Risks:** inexact alarms can drift by minutes, and battery saver can delay them further; the cap still holds. Widget updates are limited to the triggers above.
- **Parallel:** with M11.

#### M13: Health Connect in

*AR-01, AR-02, AR-04, IB-07; PRD §12.9; Room v12.*
- **Scope:**
  - `HealthConnectSource` replaces the M0 spike: a permission per feature, a sync at launch plus a daily worker, and change tokens (§6.3), kept in the `device` DataStore (§4) so a restored phone starts with a full 30-day read. The daily worker needs Health Connect's background-read permission (`READ_HEALTH_DATA_IN_BACKGROUND`, where the feature is available); without it, sync runs at launch only, and Settings says so.
  - New manifest permissions: `READ_EXERCISE`, `READ_SLEEP` and the background read, besides M0's. The background read is in Android 15, and on Android 14 needs an updated Health Connect module. A first sync reads at most 30 days back, which needs no history permission.
  - WorkManager (`androidx.work:work-runtime-ktx` with `androidx.hilt:hilt-work`), first used here.
  - Steps with the goal on Today and Body, from Health Connect's aggregate, which includes the phone's own count (§6.3). The sensor fallback is built only if the reference phone's Health Connect is too old for that.
  - Sleep, behind Settings → Body data → *Track sleep* (off by default; on when Health Connect sleep is turned on): the weigh-in sheet's chips and Today's ask, prefilled from `SleepSessionRecord`. "The night that ended that morning" is the sessions ending between 18:00 the day before and the weigh-in (or noon), with overlapping sessions from several apps merged, not summed, and awake stages left out. A typed value wins and is marked `sleep_source = TYPED`, so a later sync doesn't overwrite it.
  - Steps by calendar day: `daily_activity.steps` is keyed by the calendar date, not the log date, which the column's comment says.
  - Other apps' workouts (AR-04): `ExerciseSessionRecord`s from any app but Recomp, saved as completed extra sessions with `hc_record_id` set (the column exists), read-only and never written back (M16).
    - A mapping table turns Health Connect's exercise types into session kinds (walking, running, cycling and the like → cardio; yoga and pilates → yoga; stretching → mobility; sports → sport). Strength and weightlifting types are never imported, so a watch's copy of a Recomp workout can't count twice or trigger *Rest day*; nor is any session that overlaps a Recomp session.
    - An import counts toward an extra of its kind: the one scheduled that day first, else the first of that kind in the persona. With no extra of its kind, it shows in History and the review but counts toward nothing.
    - *Log class* prefills only from sessions you logged (`hc_record_id IS NULL`).
  - InBody drafts on Body, which open the scan form.
- **Data (v12):**
  ```text
  scan_draft  id, measured_at, weight_g, body_fat_pct?, bmr_kcal?, hc_ids
                                        -- an InBody measurement waiting for muscle and visceral fat;
                                        -- deleted once it's saved as a scan or dismissed
  ```
  `daily_activity.sleep_source TEXT` (TYPED or HEALTH_CONNECT). Steps and sleep fill M5's `daily_activity`.
- **Tests:**
  - grouping InBody records by timestamp into one draft, and matching a scan typed within 10 minutes;
  - steps by calendar day;
  - other apps' workouts mapped to a session kind and counted once; Recomp's own, strength types and overlapping sessions skipped; an edited or deleted one followed; *Log class* prefill ignoring imports;
  - sleep merging, and a typed value surviving a sync;
  - steps, sleep and workouts appearing in M8's review and M12's nudge;
  - an expired change token: a full re-read of 30 days creates no duplicates;
  - the step counter resetting at reboot, if the fallback is built;
  - the migration to v12.
- **Risks:**
  - Google Fit's and InBody's Health Connect access were off on the reference phone (M0). The setup screen says how to turn each on.
  - A change token expires after 30 days without a sync.
  - Health Connect can be missing or out of date on other phones. Everything works without it.
- **Parallel:** with M14.

#### M14: Recipes

*NU-22; PRD §12.10; Room v13.*
- **Scope:** the recipe editor. A recipe is a `food` with `source = RECIPE`, whose values are recomputed from its ingredients when it's saved, and whose serving is a `food_unit`.
- **Data (v13):**
  ```text
  recipe             food_id (PK, → food), servings?, cooked_weight_g?
  recipe_ingredient  id, recipe_id (→ recipe), position, food_id (→ food), grams
  ```
  `FoodSource` gains `RECIPE`.
- **Tests:** values per 100 g with and without a cooked weight; the diet tag from the strictest ingredient; an edited ingredient leaving past logs alone; a recipe inside itself refused; the migration to v13.
- **Risks:** raw and cooked weights are easy to mix up, so the editor says which one the totals assume.
- **Parallel:** with M13.

#### M15: InBody share and scan-informed targets

*IB-03, IB-05; PRD §12.11; no Room change.*
- **Scope:**
  - The share target (§6.7) and its picker, with *InBody result*: the scan form with the image pinned above it, prefilled from an InBody draft from the same time (M13). No AI reads it: body data stays on the phone (decided 2026-10-18).
  - The image saved as the scan's `image_path`, deleted with the scan.
  - Lean mass in `TargetInputs` (Katch-McArdle, §5.3's vector B), the *Preview targets with your scan* prompt, the body-fat finish line and the scan-based goal-weight suggestion.
- **Data:** none. `image_path` already exists; `source = SHARE_AI` stays unused.
- **Tests:**
  - golden vector B end to end;
  - the 60-day and protocol rules for which scan counts;
  - the body-fat goal weight;
  - a shared image with and without a matching draft, and the image deleted with its scan.
- **Risks:** the image is sensitive: it stays in app-private storage, out of Android's own backup, and is never sent anywhere. Lean mass from Health Connect is derived from weight and body fat % (§6.3), so it can differ a little from the machine's own figure.
- **Parallel:** with M16.

#### M16: Data out

*DA-02, DA-03, DA-04; PRD §12.12; no Room change.*
- **Scope:**
  - The automatic backup worker: a persisted folder permission, `BackupManager`'s export, the last 8 kept. The folder's address is device state (§4).
  - First, check on the reference phone whether Google Drive appears in the folder picker (`ACTION_OPEN_DOCUMENT_TREE`): community reports say it doesn't. If it doesn't, Drive gets one rolling file instead (`recomp-backup-latest.zip`, chosen once with the save dialog and overwritten each week through its saved permission), and *the last 8* applies to folders on the phone. PRD §12.12 says so.
  - CSV export.
  - Health Connect writes: `ExerciseSessionRecord`, one `NutritionRecord` per meal and `WeightRecord`, each with a `clientRecordId` (§6.3). Sessions read from other apps (M13) are never written back.
  - Sync reads the tables (a diff of the last 30 days, at launch and daily, plus a diff soon after any change, found by watching the tables through Room's invalidation tracker), so it never edits the repositories' save paths, which M15 changes in the same wave. Each record's `clientRecordVersion` is its write time in epoch ms, so an update always wins, even after a reinstall or restore; device state keeps only a hash per record, to skip unchanged ones. An edit or delete more than 30 days old isn't followed automatically: Settings → Health Connect has *Resync everything*. Deletes go by `clientRecordId`.
  - A meal's `NutritionRecord` spans its log date's meal time (the slot's usual time, M9), so a 1 AM snack sits on the log date it belongs to, and `mealType` maps the default slots (custom ones are `MEAL_TYPE_UNKNOWN`).
- **Data:** none. The `clientRecordId` stands in for a local mapping table; `workout_session.hc_record_id` marks only sessions read from other apps (M13).
- **Tests:**
  - keeping 8 files, and a revoked folder permission giving a clear message, not a crash;
  - CSV escaping and columns;
  - the records built for a day, through edits and deletes, and meals with quick adds;
  - scan weights and other apps' sessions never written.
- **Risks:** a Drive folder can be slow or offline, so the worker retries with backoff and Settings shows the last success. Nutrition written to Health Connect is readable by every app you give access; it's off by default.
- **Parallel:** with M15.

#### M17: Body extras

*BM-03, BM-04, YG-03, YG-04; PRD §12.13; Room v14.*
- **Scope:**
  - The measurements sheet and its settings.
  - Photos: capture into `files/photos/`, compare, and the 4-week prompt. *Include photos* in the export and the automatic backup, off by default; scan images (M15) follow the same switch.
  - The camera intent: once M18 declares `CAMERA`, firing `ACTION_IMAGE_CAPTURE` without that permission granted throws, so M17 asks for it first. Some phones' camera apps also save their own copy to the gallery; check the reference phone, and if it does, use M18's CameraX capture instead.
  - Import with media: a zip with photos is `formatVersion` 2. Import never deletes an image file until the next import succeeds, the safety copy keeps the current files, and a row whose file is missing shows a placeholder.
  - Focus-area chips, and the mobility card.
- **Data (v14):** `progress_photo id, log_date, taken_at, pose(FRONT|SIDE|BACK), file_path`, as in §4 plus `taken_at`. `MeasurementType` gains `SIT_AND_REACH` (cm), `SQUAT_HOLD` (seconds) and `TOE_TOUCH` (1 or 0), an enum change only. `yoga_detail.focus_areas` already exists.
- **Tests:**
  - a backup with and without photos: the 16 MB budget still covers the JSON, and photos stream as separate entries with a per-file cap;
  - a photo file that's gone missing, and an import without photos leaving the current files in place;
  - the camera permission asked before the intent fires;
  - Android's own backup leaving `files/photos/` out (the include-only rules already do);
  - the migration to v14, and mobility values.
- **Risks:** photos are the most sensitive data in the app. They stay in app-private storage, out of the gallery and out of Android's own backup.
- **Parallel:** with M18.

#### M18: Camera food

*NU-21, NU-19, NU-20, NU-11 (Open Food Facts search); PRD §12.14; no Room change.*
- **Scope:**
  - CameraX for the barcode scanner and the label photo. ZXing reads barcodes; the on-device text recogniser is open source (e.g. Tesseract), picked after a short trial on real labels. No ML Kit (decided 2026-10-18; §7).
  - An Open Food Facts client with a custom User-Agent, for product lookups and search, saving results as `food(source = OFF)`. Open Food Facts allows about 15 product reads and 10 searches a minute per user, and says its search isn't for search-as-you-type. So search asks it only on submit or after a 600 ms pause with 3 or more characters, caches results for the session and backs off on 429. Its User-Agent is `Recomp/<version> (<contact>)`, with the contact taken from each person's `local.properties`, never the public repo. Settings → Food → Food data cites Open Food Facts (ODbL, with a link).
  - Label OCR parsing into New food (§6.4).
  - `AiKind.LABEL` and `MEAL_PHOTO` requests with hand-written schemas, through M7's providers.
  - *Nutrition label* and *Meal photo* in the share picker.
- **Data:** none. `food.barcode`, the `OFF` and `LABEL_OCR` food sources, the `BARCODE`, `LABEL` and `AI_PHOTO` log sources, and `meal_draft.photo_path` already exist.
- **Tests:**
  - OCR parsing on invented label texts: FSSAI and international layouts, kJ and kcal, per-serving columns;
  - Open Food Facts replies with fields missing;
  - photo downscaling to about 1,500 px;
  - the request shapes against a mock server.
- **Risks:** an open-source recogniser reads labels less well than ML Kit would; AI cleans up when it's on, and every field stays editable. Tesseract and its `tessdata_fast` models are Apache-2.0, so the English model (about 4 MB) can be committed. Tesseract4Android ships through JitPack, a third-party repository: pin its version and limit JitPack to that group in Gradle's content filter. ZXing is Apache-2.0 too. Open Food Facts covers Indian brands patchily, so label scan is the fallback. Camera permissions and lifecycle on the phone.
- **Parallel:** with M17.

#### M19: Goals

*PS-04, PS-05, GL-03, GL-06, GL-09 (pregnancy); PRD §12.15; no Room change.*
- **Scope:**
  - *What to expect* for each goal change.
  - `GoalPlan` gains `reachedOn`, `breakUntil`, `lastBreakEndedOn` and `breakSnoozedUntil` (*Not now*). Reaching the finish line and `maintenance_break_due` (§5.8) are checked with each check-in and at launch.
  - A break is two target versions; `TargetReason` gains `BREAK` and `RESUME`.
  - `AiKind.GOAL` through M7's providers, with a hand-written `{intent, follow_up_question}` schema.
  - During a break, the check-in still updates the estimate but shows no flag or proposal.
  - `Profile.pregnant` and its guardrail, which also reaches M5's proposals and M11's suggestions: neither offers a deficit while it's on.
  - Presets loaded from `assets/presets/*.json`.
- **Data:** none. Target reasons are stored as text, and the plan's state lives in the persona JSON.
- **Tests:**
  - reached on the trend, not on one weigh-in, and by date;
  - a break due at exactly 12 weeks, and resuming on the first launch after its end date, even after days without opening the app;
  - the guardrail moving an active deficit to Maintain, and refusing a deficit from a check-in proposal or a review suggestion;
  - no flag or proposal during a break, and *Not now* asking again after 4 weeks;
  - the GOAL request against a mock server, and its fallback;
  - every bundled preset decoding and completing.
- **Risks:** the automatic resume writes a target version while you're not looking. It's the one exception to "targets never change on their own", and accepting the break agrees to it.
- **Parallel:** none; it runs before M20.

#### M20: lb and in

*PR-05; PRD §12.16; no Room change.*
- **Scope:** the shared formatters take the unit; steppers and typed entry convert at the edge; lb steps for lifts; height in feet and inches. Only lengths convert (tape sites, height and sit-and-reach); the squat hold's seconds and the toe touch don't.
- **Tests:** kg ↔ lb round trips on stored grams (225 lb is 102,058 g and shows as 225.0 lb); lb steps on the engine's integer grams; rounding only at display.
- **Risks:** it touches every screen with a weight or a length, so it runs alone and last.
- **Parallel:** none.

## 11. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| The Anthropic Java SDK misbehaves on Android (Jackson reflection under R8, dependency conflicts) | **M0 so far:** the debug and R8 release builds pass with broad keep rules, and the class-based structured outputs were found to be unusable, so schemas are hand-written. Still to confirm a live call on the phone. Narrow the keep rules before release (the release APK is ~30 MB). AI is optional, so the MVP ships either way. |
| INDB's undeclared licence | The data is never committed or bundled in a published APK. Each build downloads it from the authors at a pinned commit (§6.4). Ask the authors before distributing an APK that contains it. |
| No app writes steps to Health Connect on a given phone | Health Connect counts the phone's own steps on Android 14 with an up-to-date module; on older ones, read the step-counter sensor directly (§6.3). |
| AI portion estimates are off by 20–30%, worse for ordered food | Origin-aware prompts, review screen, confidence flags, learning loop (§6.2), and adaptive TDEE absorbing consistent bias (§5.4). |
| InBody readings swing with hydration | Test protocol, protocol flags, noise bands, trends over 3 or more scans (§5.6). |
| Logging fatigue | Saved meals, copy day, quick add, AI logging. Targets judged on weekly averages. |
| Data loss | Per-set writes, mandatory migration tests, auto-backup, round-trip test. |
| The engine progresses too fast in a deficit | Conservative defaults, deficit-aware stall window, everything overridable. Retune after 4 weeks of real data. |
| Scope creep | The P0 list is the MVP. The rule that nothing from P1 starts until the MVP has been used daily for 2 weeks was waived on 2026-10-18: v1.1 is built while the MVP is dogfooded, and dogfood fixes go first. |
| v1.1 builds upgrade the app you use every day | A backup before any schema change, installs only along `main`'s chain of Room versions, a shared install ledger, and invented data in a separate dev install (§10, v1.1 plan). |
| Parallel milestones collide | Two at a time, only in the planned waves; fixed Room versions; shared registration files only; rebase before the PR and before the phone check (§10). |
| Recognition libraries phone home (ML Kit sends usage metrics to Google) | Open-source recognisers only (decided 2026-10-18, PRD §12.14). |
| Photos make backups too big for the import's 16 MB budget | Photos stream as separate zip entries with a per-file cap, and are off by default in exports (M17). |
| Health Connect access is off in the writing apps | M13's setup screen says which app to change and how; every feature works without Health Connect. |

## 12. Open technical decisions

1. **Model for text logging:** Opus 5.5 (default) or Haiku 5.5. Decide on latency with the eval set; cost matters only on pay-as-you-go. *(AI access is settled: an API key funded by those credits.)* M3c ships Opus 5.5 at effort `low`, and `ai_request_log.latency_ms` records each request's time for the comparison.
2. ~~**Exercise seed data**~~ *Settled in M1:* 81 hand-written entries in `assets/seed/exercises.json`, so there's no third-party licence to track.
3. ~~**Indian food database**~~ *Settled in M3b:* INDB, fetched at build time with diet tags from ingredient codes (§6.4). Open: ask the authors about bundling, and whether a fried-recipe correction exists.
4. **Package name and app name:** "Recomp" is a placeholder.
5. **Charts:** stay hand-drawn on Compose `Canvas` as a shared component (M8). Adopt Vico only if a chart needs pan and zoom.
6. ~~**Text and barcode recognition**~~ *Settled 2026-10-18:* open source, not ML Kit: ZXing for barcodes, and an on-device text recogniser such as Tesseract, picked after a trial in M18 (PRD §12.14).
7. **AI providers' structured outputs:** each provider's schema support is confirmed in M7 against its current docs (Gemini's field changed in October 2026). Shared schemas leave out array-length and number-range constraints. An OpenAI-compatible server without schema support falls back to JSON mode plus validation.
