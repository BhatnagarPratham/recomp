# Recomp: Technical Requirements Document

*Draft v0.9, 2026-10-17. Implements [PRD.md](PRD.md) v0.9. Requirement IDs (WL-02, PO-05, PS-03…) refer to the PRD.*

## 1. Scope and constraints

- One user per install, on Android 14 or later. Each person builds the app (or installs a release APK) and brings their own AI key. No Play Store listing needed.
- **Offline-first.** Only AI features and barcode lookups need the network.
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
| Charts | Vico | Compose-native. |
| Health data | Health Connect (`androidx.health.connect:connect-client`) | P1. Steps come from whichever app writes them; §6.3. |
| On-device OCR | ML Kit Text Recognition v2 | P1. Nutrition labels, without AI. |
| AI | Anthropic Java SDK (`com.anthropic:anthropic-java`) | Kotlin uses the Java SDK. Builds and passes R8 (M0). Its class-based structured outputs don't work on Android (§6.2). |
| Barcode | ML Kit barcode scanning (on-device) and CameraX | P1. |
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
  work/{BackupWorker,ReminderWorker,HealthSyncWorker}.kt
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
set_entry           id, session_exercise_id, set_index, set_type(WARMUP|WORKING|DROP|FAILURE),
                    load_g?, reps?, rir?, completed_at?
                                          -- rows exist from the start; null load/reps = not entered (ghost)
personal_record     id, exercise_id, session_id, type(MAX_LOAD|REPS_AT_LOAD|E1RM|SESSION_VOLUME),
                    value,                -- grams, reps, grams or gram-reps by type
                    load_g?, set_entry_id?  -- no single set for SESSION_VOLUME
                    achieved_at
yoga_detail         session_id (PK), style, duration_min, intensity?, focus_areas[], poses[]
                                          -- details for every extra-session kind, not only yoga
body_weight         id, log_date, weight_g, measured_at, source(INBODY|GYM_SCALE|HOME_SCALE|HEALTH_CONNECT)
body_composition    id, measured_at, log_date, source(MANUAL|SHARE_AI|HEALTH_CONNECT), weight_g,
                    skeletal_muscle_g, body_fat_g, body_fat_pct, visceral_fat_level, bmr_kcal?,
                    ecw_tbw_ratio?, inbody_score?, segmental_lean_json?,
                    mins_since_meal, workout_before_scan, protocol_ok, image_path?, notes  -- IB-01/02
measurement         id, log_date, type(WAIST|CHEST|ARM|THIGH|HIP|MOBILITY_*), value
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
daily_activity      log_date (PK), steps?, steps_synced_at?, sleep_hours?   -- steps from HC, sleep typed in
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
- Later: usual scan slot and reminder times.

M1 and M2 stored a `TrainingSettings` JSON under `training`. A DataStore `DataMigration` moves it into `persona` and `keep_screen_on` on first read after the upgrade and deletes the old key. The install then has a training setup but no goal or profile, so onboarding opens, pre-filled from it (PRD §7.1).

A stored JSON value that no longer decodes (for example one saved by a newer build) reads as its default, so onboarding opens. It is never written over: before any write, it's copied untouched to `<key>_unreadable`, and the app says so once at launch. The API key is stored separately and encrypted (§6.2). `nutrition_target` rows also record the goal they were computed for, so history survives goal switches (PS-02, PS-04).

**Indexes:** `set_entry(session_exercise_id)`, `session_exercise(exercise_id)`, `workout_session(status, started_at)`, `food_log(log_date)`, `food_log(food_id)`, `food_unit(food_id, label)` (unique), `saved_meal_item(saved_meal_id)`, `meal_draft(log_date)`, `ai_request_log(created_at)`, `measurement(type, log_date)`, `body_composition(measured_at)`. Food search uses an `@Fts4` table over `food.name`, filtered by `diet` and `region` (§6.4).

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

- `Profile.weightKg` is the current weight typed in onboarding or Settings. M4's weigh-ins replace it with the trend weight (§5.2).
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
// ctx: inDeficit (true when the current target is below estimated TDEE), experience (ExperiencePolicy, §5.0)

if (history.isEmpty())           -> BASELINE   "Pick a weight you could do for ${repMax} with ~2 reps left"
val last = history[0]
val jumpRatio  = cfg.incrementG / last.topLoadG
val effRepMax  = cfg.repMax + if (jumpRatio > 0.10) 2 else 0          // PO-06 big-jump guard
val allAtTop   = last.workingSets.size >= cfg.targetSets && last.workingSets.all { it.reps >= effRepMax }
val missedBottom = last.workingSets.any { it.reps < cfg.repMin }

when {
  allAtTop -> INCREASE   load = roundToStep(last.topLoadG + inc), reps = repMin × targetSets
              // P1 (PO-08): inc = 2 × incrementG if ctx.experience.allowsDoubleJump, every set had RIR ≥ 3 and jumpRatio ≤ 0.05
  missedBottom && history.getOrNull(1)?.let { it.topLoadG == last.topLoadG && it.missedBottom } == true
           -> DELOAD     load = roundDownToStep(last.topLoadG × 0.9), reps = repMin × targetSets
  missedBottom -> REPEAT  same load, target = repMin on every set
  else     -> ADD_REPS   same load, target = last reps with +1 on the first set below effRepMax
}

// Stall check, layered on top of any action above (PO-05)
val window = ctx.experience.stallWindow + if (ctx.inDeficit) 1 else 0   // §5.0 experience policy
if (history.size > window && history.take(window).maxOf { it.bestE1rm } <= history.drop(window).maxOf { it.bestE1rm })
    rec.stall = StallOptions(DELOAD_10, SWAP(substitutes(cfg)), HOLD)   // offered, never auto-applied
```

- **Load types.** `BODYWEIGHT` uses the `REPS_ONLY` scheme: reps climb to `repMax`, then the engine suggests a harder variation or adding load. `BODYWEIGHT_PLUS` (e.g. weighted dips) progresses the added load; with no load yet the jump ratio is unbounded, so the big-jump guard asks for `repMax + 2` before the first plate. `ASSISTED` (P1) progresses by *reducing* the assistance load; until then it's treated as weighted.
- **Steps.** `INCREASE` adds one increment to the top load. `DELOAD` takes 90% of the top load and rounds *down* to a multiple of the increment. Targets list one rep count per target set; `ADD_REPS` pads a short last exposure with its last set's reps.
- **Stall metric.** The best Epley score over every working set, with no rep cap. It's a poor absolute 1RM above 12 reps, but as a progress measure it's consistent between sessions and rises with reps; excluding or capping high-rep sets made 12–20 rep work look stalled while reps climbed. Bodyweight sets use reps. Displayed e1RMs and E1RM PRs still use sets of 12 reps or fewer.
- **Hold and Deload (PO-05).** Both store a reset point on the exercise: the start of the session where you chose. After a *Deload*, stall detection runs only on exposures after the reset, with the usual rule. After a *Hold*, the card returns once at least `window` exposures follow the reset and none beats the best from before it.
- **In a deficit.** `ctx.inDeficit` compares the target in force today with the TDEE estimate stored on the same `nutrition_target` row: true when the target sits below the ±5% maintenance band around TDEE (§5.3). With no target yet, it's false.
- **Rounding.** `roundToStep` uses the exercise's increment as the equipment step (e.g. dumbbells at 2.5 kg). With 1.25 kg plates, the barbell step is 2.5 kg total. Load is always per dumbbell, never the pair.
- **Estimated 1RM** uses Epley: `load × (1 + reps / 30)`. It's computed only for sets of 12 reps or fewer. Higher-rep sets still count for rep PRs.
- **PRs** are computed once, when the workout finishes, and written to `personal_record`. At most one PR of each type per exercise per session.
- **Freezing the recommendation.** It's written to `session_exercise.recommendation_json` when the workout starts, or when an exercise is added or swapped in mid-workout, as kotlinx.serialization JSON (`action`, `loadG`, `reps[]`, `stalled`, `baseline`). The summary then grades against what you were actually shown, even if the history changes later. Choosing *Deload* or *Hold* rewrites it for that session. Working sets' ghost values come from it (PRD §7.4).
- **Overrides (PO-07).** `user_overrode` is true when the session's top completed working load differs from the target load. It's recomputed whenever a set is ticked, unticked, retyped or deleted, so back-off sets and corrected typos don't count.
- **PRs.** Compared with every earlier completed set of the exercise. None are written for an exercise's first exposure.

### 5.2 Weight trend (BM-01)

Only weigh-ins from the primary source (BM-01) feed the trend. Readings from other sources are plotted as dots but left out, because each scale and setting reads differently.

```
Δd     = days since the previous primary weigh-in
α_eff  = 1 − (1 − 0.1)^Δd          // 0.10 for daily data, ≈ 0.52 for weekly
trend  = trend_prev + α_eff × (w − trend_prev)
```

The smoothing accounts for the gap between weigh-ins, so daily and weekly data both give a sensible trend. A weekly InBody reading moves the trend about halfway toward it, the same effect seven daily readings at that weight would have. The trend is seeded with the first weigh-in. Weekly rate = `trend_today − trend_7_days_ago`, interpolated between points and also shown as a percentage of body weight. The UI plots weigh-ins as dots and the trend as a line.

### 5.3 Starting targets (NU-01 to NU-04, IB-05)

```
BMR    = Katch-McArdle when a body-composition scan ≤ 60 days old exists (IB-05):
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
window      = 21 days with daily weigh-ins; 28 days with weekly ones (needs ≥ 4 weigh-ins)
              usable only if ≥ 80% of days have food logged
              (a day with no logs, or one you mark "incomplete", is excluded)
tdee_obs    = mean(kcal on logged days) − (trend_end − trend_start) × 7700 / days
tdee_new    = tdee_prev + clamp(0.5 × (tdee_obs − tdee_prev), −150, +150)   // at most 150 kcal/week
new target  = §5.3 applied with TDEE = tdee_new
```

Example: you average 2000 kcal over 21 days and the trend falls 1.0 kg, so `tdee_obs ≈ 2367`. The Sunday check-in shows the observed number, the proposed target and the reason. Accepting it writes a new `nutrition_target` row.

If logging consistently under- or over-counts by some percentage, that error moves `tdee_obs` by the same amount, so the targets still steer you correctly.

**Check-in flags per goal (GL-08, PRD §4.8).** The weekly check-in runs adaptive TDEE for every goal. A goal's flag decides when a change is proposed:
- Fat loss: loss under 50% of pace for 2 weeks.
- Recomp: waist and trend both flat for 4 weeks.
- Maintain: drift beyond ±1% over 4 weeks.
- Lean bulk: gain under 0.1% or over 0.5% a week for 2 weeks.

A proposal is always §5.3 recomputed with `tdee_new` at the current pace.

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

## 6. Key technical flows

### 6.1 Active workout lifecycle (WL-05, WL-06)

1. **Start.** Insert `workout_session(status = ACTIVE)`, its `session_exercise` rows (targets copied from the routine, recommendations frozen from M2) and their `set_entry` rows: last session's warm-ups, then `target_sets` working sets, all with null values. Only one session can be ACTIVE at a time; a second start throws.
2. **Every ticked set is written to Room immediately** as a single `UPDATE` of load, reps, RIR and `completed_at`. Edits before the tick are written too. The workout UI is a Flow over the database, so process death loses nothing. Ghost values are computed in the ViewModel from the hot query (`Prefill` in `:domain`), never stored. On launch, an ACTIVE session triggers a "Resume workout?" prompt. If the session is more than 4 hours old, the prompt offers to finish it, using the last set's time as the end time.
3. **`ActiveWorkoutService`** is a foreground service that runs only while a session is ACTIVE. It observes the active session and the rest timer, and stops itself when there's no active session. Its ongoing notification shows elapsed time, the current exercise and set, and the rest countdown, with *Skip rest* and *+30 s* actions; tapping it opens the workout.
   - Android 14+ needs a declared service type. Use `health`, which requires the `ACTIVITY_RECOGNITION` runtime permission. **Confirmed in M0** on the reference phone (Android 15).
   - *Start* asks for `ACTIVITY_RECOGNITION` and `POST_NOTIFICATIONS` first. If they're refused, the workout still runs with the in-app rest banner, which vibrates when rest ends while the app is open.
   - `START_NOT_STICKY`: if the process dies, the resume prompt restarts the service on the next launch.
4. **Rest timer.** `RestTimer` stores an absolute end time and the rest length in a small DataStore, not a countdown, so it survives process death. The UI and the service both observe it. The service fires the high-priority "rest over" alert and vibration. A coroutine `delay` counts uptime, which stops while the CPU sleeps, so the service holds a `PARTIAL_WAKE_LOCK` for the rest's length plus a 10 s margin. A foreground service's wake lock is honoured even in Doze, so exact alarms (and their permission) aren't needed. "+30 s" after a rest has ended starts a fresh 30 s rest. M0 showed the alert channel's vibration supersedes a direct `Vibrator` call, so the service vibrates directly only when the channel can't: notifications are off for the app, the channel is blocked, or its vibration is turned off.
5. **Finish.** Delete unticked sets, set `status = COMPLETED` and `ended_at`, compute PRs and outcomes (M2), write a Health Connect `ExerciseSessionRecord` (P1), then stop the service. Exercises with no sets stay, as a record that they were skipped. **Discard** sets `status = DISCARDED`; discarded sessions never count as history.
6. **Keep screen on.** Use `FLAG_KEEP_SCREEN_ON` on the workout screen when the setting is enabled. No wake lock is needed.
7. **Save back (RT-03).** The session's plan (exercise ids in order, with sets, rep range and rest) is compared with the routine's. If they differ, the summary offers to rewrite the routine from the session in one transaction.

### 6.2 AI features (optional; PRD §7.12)

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
- **Failures.** A request times out after 20 s. The client (not the SDK, whose retries are off) retries once, and only when the request clearly wasn't processed: a 429, a 5xx (including 529 overloaded), or a connection that was never made. It waits what `retry-after` asks, but at most 2 s. A timeout, or a connection dropped after sending, isn't retried: the server may still be working on the request, and billing it, so sending it again could pay twice. The worst case is therefore about 22 s. The first live requests' `latency_ms` will show whether 20 s is right; a long reply that would end in `max_tokens` shows up as a timeout until then. Every request is logged with its status. Any status but `OK` saves the input as a `meal_draft` with that status as its `reason` and drops you into the non-AI path. Logging is never blocked. *Cancel* only stops waiting: the request runs to completion in the background so its spend is still logged, and its result is discarded.

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
  | `claude-opus-5`, `claude-opus-4-8` (refusal-fallback targets) | 5.00 | 25.00 | 0.50 |
  | `claude-fable-5-1` | 10.00 | 50.00 | 0.25 |
- **Refusal fallback.** Opus 5.5 and Sonnet 5.5 requests go to the beta Messages endpoint with `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`), so a safety-classifier false positive is retried on the model Anthropic recommends, in the same round trip. Haiku 5.5 has no server-side fallback, so its requests don't send it. `ai_request_log.model` records the model that served the response.

**Uses and output schemas.** Every request uses structured outputs: `output_config.format` with a JSON schema written by hand (`JsonOutputFormat.Schema`, or `BetaJsonOutputFormat.Schema` on the beta endpoint). The reply is parsed with kotlinx.serialization. **Don't use the SDK's class-based `.outputConfig(SomeClass::class.java)`.** It derives the schema with `java.lang.reflect.AnnotatedType`, which Android doesn't have even at API 37, so it crashes on the phone (found in M0).

| Kind | Input | Output (abridged) |
|------|-------|-------------------|
| `MEAL_TEXT`, `MEAL_PHOTO`, `FIT_CHECK` | Text and/or photo, plus the origin tag | `items[]` (shape below), optional `clarifying_question` |
| `LABEL` (P1) | Label photo plus the on-device OCR text | `name`, `brand?`, `per_100g {kcal, protein_g, carbs_g, fat_g, fiber_g}`, `serving_g?` |
| `INBODY` (P1) | Screenshot shared from the InBody app, or a photo of the paper sheet | `weight_kg`, `skeletal_muscle_kg`, `body_fat_kg`, `body_fat_pct`, `visceral_fat_level`, `bmr_kcal?`, `ecw_tbw?`, `inbody_score?`, `segmental?` |
| `COACH` (P2) | The week's summary as JSON | `suggestions[3] {title, why, action}` |

```json
{
  "items": [
    { "name": "Phulka", "quantity": 2, "unit": "piece", "grams": 70,
      "kcal": 210, "protein_g": 6.5, "carbs_g": 42, "fat_g": 1.5, "fiber_g": 4,
      "confidence": "high", "assumptions": "medium size, no ghee" }
  ],
  "clarifying_question": "Was the dal tempered with ghee?"
}
```

Every property is required, as structured outputs expect with `additionalProperties: false`; `clarifying_question` is `""` when there's none. `confidence` is an enum of `high`, `medium` and `low`. The meal schema is `MealSchema` in `data/ai`, and the reply is decoded with kotlinx.serialization, ignoring unknown keys.

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
  - Items with non-positive grams, or a negative or non-finite number, are rejected and named in a note. No item left is `INVALID`.
  - An item is flagged when its kcal is more than 15% away from `4P + 4C + 9F`, ignoring differences under 10 kcal so small garnishes don't flag.
  - Totals are recomputed from the kept items; nothing from the model is summed as given.
  - `stop_reason` (`refusal`, `max_tokens`) is checked before content is read; errors are handled with the SDK's typed exceptions (table above); the server-side refusal fallback is on (above).
- **Saving.** One `food_log` row per item in one transaction, `source = AI_TEXT`, with `ai_request_id` and the meal's origin. *Save + My meals* then saves those rows as a saved meal (NU-13).
- **Learning loop.** An item whose name, grams or numbers you changed before saving becomes a `food(source = AI)`: values per 100 g from your item, the persona's own-food diet tag (`Diet.ownFoodTag`), no region, and its unit (unless grams) as a calibrated `food_unit` at grams ÷ quantity. An unarchived AI food with the same name (ignoring case) is updated instead of duplicated. The log row links to the food, so it's counted in use counts and appears in search, in future prompts and later in the local parser; repeat dishes converge on your numbers. Unedited items are logged with no food, from their snapshot.
- **Privacy.** Sent: the meal text and origin (or a photo, label or InBody image in P1), your diet and region rules, your household measures, and your top 30 own foods (names, values and units). Never sent: your name, body data, targets or log history. The first estimate shows a one-time notice (`ai_notice_seen`).
- **API key storage:**
  - Entered once in Settings → AI. Only printable ASCII without spaces is accepted, which is all a key is made of: a zero-width space, BOM or en dash picked up in a copy would make OkHttp reject the header with the whole key in its error message. A stored value that fails the same check reads as no key.
  - Encrypted with Tink AEAD (AES-256-GCM, associated data naming the key) whose keyset is wrapped by an Android Keystore master key (`AndroidKeysetManager`; the keyset lives in its own shared-preferences file). The ciphertext is stored in the `secrets` DataStore, apart from the settings, so a settings export can never include it.
  - Never logged: the app never prints it, and the SDK client is built in memory per request. Excluded from export, and from cloud backup and device-to-device transfer by `data_extraction_rules.xml` (the `secrets` DataStore and the keyset file). A restore onto another phone couldn't decrypt it anyway, since the Keystore key never leaves the phone; it then reads as `NO_KEY`.
  - Each person enters their own key into their own install, so no key ever ships inside an APK. A build distributed with a shared key would need a proxy, because a key inside an APK can be extracted.

**Local describe parser (AI-04, P1).** This is plain rules, not AI. The same component powers offline *Will it fit?*.

```
"2 roti, 1 katori dal makhani and salad"
→ split on  ,  "and"  +  newline
→ per chunk: quantity (2, ½, "half", "one"…) + unit (food_unit labels + synonyms: bowl→katori, chapati→roti)
             + name
→ FTS-match name against vegetarian foods, ranked by use_count;
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
  - Skeletal muscle mass and visceral fat always come from the share flow (§6.7) or manual entry.
  - The InBody app's Health Connect access was off; turn it on in the InBody app.
- **Not read:** sleep. There's no tracker, so it's typed in at the morning check-in.
- **Write (DA-04):** `ExerciseSessionRecord` (strength training, yoga), a `NutritionRecord` per meal, and `WeightRecord` for manual weigh-ins, so they show up in Google Fit.
- **Fallback if Fit doesn't write steps:** read the phone's `TYPE_STEP_COUNTER` sensor directly. It needs `ACTIVITY_RECOGNITION`, which the workout service already requests. The counter resets on reboot, so sample it on app open and from a periodic worker, and store daily deltas.
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
- **International region:** no bundled dataset in the MVP. Open Food Facts lookups (barcode and search, P1), custom foods, AI-learned items and quick add. A bundled generic dataset (e.g. USDA FoodData Central, public domain) is a candidate for v1.1.
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
  2. ML Kit text recognition runs on-device.
  3. Tolerant regexes parse the per-100 g column of the FSSAI nutrition table: `Energy (kcal)`, `Protein (g)`, `Carbohydrate (g)`, `Total Fat (g)`.
  4. An editable form is pre-filled.

  With AI on, the photo and OCR text also go to Claude (`LABEL`) for a cleaner result.
- **Barcode (P1, NU-21):** Open Food Facts API, with a custom User-Agent as its terms require. A result is cached as a `food` row. Coverage of Indian brands is patchy, so a miss goes straight to label scan.
- **Search ranking:** recent and frequent foods first (`use_count`, `last_used_at`), then custom foods, then AI-learned items, then the bundled database.

### 6.5 Backup and restore (DA-01, DA-02)

- **Export:** a versioned (`schemaVersion`) JSON of every table plus settings, without the API key, zipped with photos optionally included. Every table includes M3c's `meal_draft` and `ai_request_log`; the `secrets` DataStore is never read. Written through the system "create document" picker, so Google Drive works as a target.
- **Auto-backup (P1):** pick a folder once (the permission persists). A weekly WorkManager job writes `recomp-backup-YYYY-MM-DD.zip` and keeps the last 8.
- **Restore:** validate `schemaVersion`, migrate if the file is older, then replace the data in a single transaction after explicit confirmation.
- **Safety net:** Android Auto Backup is enabled for the database file, with the key store excluded (`data_extraction_rules.xml`: the `secrets` DataStore and Tink's keyset file, for cloud backup and device transfer).
- **Round-trip test:** export, then import, must give identical data. It's a required test (§8).

### 6.6 Notifications

- **Channels:** *Workout* (ongoing), *Rest timer* (high importance), *Reminders*, *Weekly check-in*.
- **Scheduling:** reminders use WorkManager with inexact timing; ±15 minutes is fine.
- **Permission:** `POST_NOTIFICATIONS` is requested at the first opt-in.

### 6.7 Share target (IB-03, NU-19, NU-20)

The app registers as an Android share target for images (`ACTION_SEND`, `image/*`). A shared image opens a picker: *InBody result*, *Nutrition label* or *Meal photo*. Each choice leads into its own flow. That's how a screenshot from the InBody app gets in without saving files around. With AI off, *InBody result* opens the manual form with the screenshot alongside for reference.

## 7. Non-functional requirements

| Area | Requirement |
|------|-------------|
| Performance | Cold start to the Today screen < 1.5 s on a mid-range phone. Ticking a set updates the UI in < 100 ms. Food search over 10k items returns in < 150 ms. |
| Reliability | No data loss on process death or reboot mid-workout. Every migration has a test. |
| Offline | Everything except AI features and barcode lookup works offline. Health Connect and label OCR run on-device. |
| Gym usability | Set controls are at least 56 dp. Primary actions sit in the bottom half of the screen. High-contrast dark theme. Layout holds up at 130% font size. |
| Privacy | No analytics or crash SDKs. The only network calls go to Anthropic and Open Food Facts. |
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

- Private Git repository. CI with GitHub Actions on push: build, unit tests, Android Lint, ktlint.
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
| **M4: Body, Today and data** | Weight sources with the time-aware trend, waist, InBody manual entry with the conditions check, Today screen, export and import. **MVP complete; start dogfooding.** | BM P0, IB P0, HM-01, DA-01 |
| **M5+: v1.1** | Adaptive target with weekly check-in and review, Will it fit?, label scan, local parser, AI meal photos, the share target (§6.7) with InBody import from screenshots and Health Connect, recomp chart and InBody-based targets, steps through Health Connect, sleep in the check-in, barcode, charts, reminders, widget, auto-backup | P1 |

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
  - 429 and 5xx retried once, a timeout after one retry, and dropped connections.
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

## 11. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| The Anthropic Java SDK misbehaves on Android (Jackson reflection under R8, dependency conflicts) | **M0 so far:** the debug and R8 release builds pass with broad keep rules, and the class-based structured outputs were found to be unusable, so schemas are hand-written. Still to confirm a live call on the phone. Narrow the keep rules before release (the release APK is ~30 MB). AI is optional, so the MVP ships either way. |
| INDB's undeclared licence | The data is never committed or bundled in a published APK. Each build downloads it from the authors at a pinned commit (§6.4). Ask the authors before distributing an APK that contains it. |
| No app writes steps to Health Connect on a given phone | Read the phone's step-counter sensor directly (§6.3). |
| AI portion estimates are off by 20–30%, worse for ordered food | Origin-aware prompts, review screen, confidence flags, learning loop (§6.2), and adaptive TDEE absorbing consistent bias (§5.4). |
| InBody readings swing with hydration | Test protocol, protocol flags, noise bands, trends over 3 or more scans (§5.6). |
| Logging fatigue | Saved meals, copy day, quick add, AI logging. Targets judged on weekly averages. |
| Data loss | Per-set writes, mandatory migration tests, auto-backup, round-trip test. |
| The engine progresses too fast in a deficit | Conservative defaults, deficit-aware stall window, everything overridable. Retune after 4 weeks of real data. |
| Scope creep | The P0 list is the MVP. Nothing from P1 starts until the MVP has been used daily for 2 weeks. |

## 12. Open technical decisions

1. **Model for text logging:** Opus 5.5 (default) or Haiku 5.5. Decide on latency with the eval set; cost matters only on pay-as-you-go. *(AI access is settled: an API key funded by those credits.)* M3c ships Opus 5.5 at effort `low`, and `ai_request_log.latency_ms` records each request's time for the comparison.
2. ~~**Exercise seed data**~~ *Settled in M1:* 81 hand-written entries in `assets/seed/exercises.json`, so there's no third-party licence to track.
3. ~~**Indian food database**~~ *Settled in M3b:* INDB, fetched at build time with diet tags from ingredient codes (§6.4). Open: ask the authors about bundling, and whether a fried-recipe correction exists.
4. **Package name and app name:** "Recomp" is a placeholder.
