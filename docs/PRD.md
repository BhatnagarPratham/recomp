# Recomp: Product Requirements Document

*Draft v0.5, 2026-10-17. Companion doc: [TRD.md](TRD.md). Open source under MIT: fork it and configure it for yourself.*

## 1. Summary

Recomp is an Android app for people who lift and want their nutrition to match their goal.

Strength training is the centre of the app. Every exercise shows what to beat today, so progressive overload happens by default. Nutrition tracking sets calorie and protein targets from your goal, then adapts them to your real intake and weight trend. Logging is built to take as little effort as possible.

Everything that depends on who you are lives in a **persona** (§4): goal, experience, diet, food region, training split, extra sessions and body-data sources. The persona drives the calorie maths, protein targets, progression pacing, food database and AI prompts. The app ships with presets, and every value can be changed.

**The reference persona** is the one the app was first designed around:
- an overweight lifter doing a body recomposition (losing fat while gaining muscle);
- a Push/Pull/Legs split plus a weekly yoga class;
- mostly home-cooked lacto-vegetarian Indian food, with some ordering in and snacking;
- access to InBody body-composition scans.

Where this doc gives a concrete default without saying otherwise, it's the reference persona's.

One user per install, no accounts, offline-first. AI is optional and uses your own API key.

## 2. How the goal shapes the app

- **Energy balance decides weight change.**
  - Fat loss needs a sustained deficit.
  - Muscle gain is fastest in a small surplus.
  - A recomposition works in a modest deficit, mainly for people who are overweight or new to lifting.
- **More muscle raises metabolism only a little.** At rest, muscle burns roughly 13 kcal/kg/day and fat about 4.5. Gaining 3 kg of muscle adds about 40 kcal/day. In a deficit, lifting's job is to make sure the weight you lose is fat, not muscle.
- **Protein** supports muscle under every goal, and matters most in a deficit.
- **Progressive overload** is the training lever for every goal. How fast it can move depends on experience and on energy balance.
- **What this means for the product:** targets, progression pacing and what counts as "progress" all come from the persona's goal.
  - A recomp is judged on trend weight, waist, body composition and strength together.
  - A cut is judged on rate of loss while holding strength.
  - A lean bulk is judged on rate of gain and strength, with waist roughly stable.

## 3. Goals and non-goals

**Goals**

| # | Goal |
|---|------|
| G1 | Make progressive overload automatic: every exercise shows a concrete target for today. |
| G2 | Logging a set that repeats last time's numbers takes ≤ 2 taps. |
| G3 | Hit calorie and protein targets on most days, with < 15 s to log a repeat meal. |
| G4 | Answer "is my goal on track?" on one screen, once a week. |
| G5 | Never lose data, and never be blocked by the network or the AI being unavailable. |
| G6 | Fit different people through configuration, not code: goal, diet, split and experience are settings. |

**Non-goals (v1):** multiple users or accounts per install, social features, a cloud backend, iOS, automatic rep counting, a video or coaching-content library, cardio programming, and medical advice.

## 4. Personas and configuration

### 4.1 Persona dimensions

| Dimension | Options | What it changes |
|-----------|---------|-----------------|
| **Goal** | Fat loss, Recomp, Maintain, Lean bulk | Calorie target, protein, progression pacing, what counts as progress, weekly check-in proposals |
| **Experience** | Beginner (< 1 year), Intermediate (1–3 years), Advanced (3+ years) | Rep-range defaults, stall window, jump sizes, deload cadence |
| **Diet** | Vegan, Lacto-vegetarian, Eggetarian, Pescatarian, Non-vegetarian | Food search filter, AI prompts, protein suggestions, protein ramp default |
| **Food region** | Indian, International | Bundled food database, portion units (katori and roti, or cup and slice), AI portion conventions |
| **Training split** | Push/Pull/Legs, Full body A/B, Upper/Lower, Custom | Seeded routines and rotation |
| **Extra sessions** | Yoga, mobility, cardio, sport (any number, or none) | Weekly plan and adherence |
| **Body data** | Home scale, body-composition scans (InBody or another BIA machine), gym scale; steps through Health Connect | Weight trend source, body-composition features |
| **Units** | kg and cm, or lb and in | Display only. Storage is always metric. |
| **AI** | Off, or on with your own API key | AI features |

### 4.2 Goal policies

Each goal has three paces. **Standard** is the default, and exact values are editable under Advanced.

| Goal | Gentle | Standard | Aggressive | Protein (g per kg of reference weight) | Success looks like |
|------|--------|----------|------------|----------------------------------------|--------------------|
| **Fat loss** | −0.5% of body weight a week | −0.75% | −1.0% | 2.0 (1.8–2.4) | Trend weight down at the chosen pace, strength held |
| **Recomp** | −0.25% | −0.5% | −0.75% | 1.6 (1.6–2.2) | Waist and fat mass down, muscle mass and strength up |
| **Maintain** | — | Maintenance, judged within a ±1% band | — | 1.6 (1.4–2.0) | Weight within the band, strength up |
| **Lean bulk** | +5% of maintenance | +10% | +15% | 1.6 (1.6–2.2) | Trend weight up 0.25–0.5% a week, strength up, waist roughly stable |

- **Caps:** a deficit is capped at 25% of maintenance, and a surplus at 15%.
- **Projection:** the pace picker shows an approximate projection, e.g. "≈0.5 kg a week, finish line around mid-March". It's recomputed weekly from the trend.
- **Reference weight** is your goal weight if you set one, otherwise your current weight. For fat loss and recomp, onboarding suggests a goal weight: from a recent body-composition scan if you have one, otherwise the BMI-25 weight for your height.
- **Safety floor:** intake never goes below your BMR, or below 1,500 kcal (men) / 1,200 kcal (women). More guardrails are in §4.9.
- **In a deficit** (fat loss, recomp), the progression engine adds one session to its stall window, because progress is slower when eating below maintenance.

### 4.3 Experience policies

These are starting defaults, to be tuned with real data.

| Experience | Rep ranges (compound / isolation) | Stall window, in sessions | Jumps | Deloads |
|------------|-----------------------------------|---------------------------|-------|---------|
| **Beginner** | 8–12 / 10–15 | 3 (4 in a deficit) | Double increment allowed when every set hits the top with RIR ≥ 3 | Only on a stall |
| **Intermediate** | 6–10 / 10–15 | 4 (5 in a deficit) | Single increment | Suggested every 6–8 weeks, or on a stall |
| **Advanced** | 5–8 / 8–12 | 5 (6 in a deficit) | Single increment. Periodised blocks are P2. | Suggested every 4–6 weeks |

### 4.4 Presets

| Preset | Goal | Experience | Diet | Region | Split | Extras |
|--------|------|------------|------|--------|-------|--------|
| **Reference: vegetarian recomp** | Recomp | Beginner | Lacto-vegetarian | Indian | Push/Pull/Legs (§7.3) | Weekly yoga class; InBody scans |
| **Fat loss starter** | Fat loss | Beginner | Asked | Asked | Full body A/B | — |
| **Lean bulk** | Lean bulk | Intermediate | Asked | Asked | Upper/Lower | — |
| **Maintain and get stronger** | Maintain | Asked | Asked | Asked | Full body A/B | — |
| **Custom** | Asked | Asked | Asked | Asked | Asked | Asked |

### 4.5 Persona requirements

| ID | Pri | Requirement |
|----|-----|-------------|
| PS-01 | P0 | Onboarding starts with a preset, then shows every persona setting pre-filled for review. |
| PS-02 | P0 | Every persona value can be changed later in Settings. Changes apply from that day on and never rewrite history: targets are versioned. |
| PS-03 | P0 | The calorie, protein, progression, food-search and AI logic read their behaviour from the persona. No persona-specific logic lives anywhere else. |
| PS-04 | P1 | **Goal switch** (e.g. recomp to lean bulk): new targets, a note on what the scale will now do, and a fresh baseline for comparisons. |
| PS-05 | P1 | Presets are data files, so a fork can add one without touching Kotlin code. |
| PS-06 | P2 | Export or import a persona as a file (settings only, never body data) to share a setup. |

### 4.6 Choosing a goal

Onboarding, and Settings → Goal, follow the same flow:

1. **Goal cards**, each with a one-line description:
   - **Lose fat:** a deficit while keeping your strength.
   - **Lose fat and build muscle (Recomp):** a gentle deficit with high protein. Works best if you're new to lifting or carrying extra fat.
   - **Maintain:** keep your weight steady and get stronger.
   - **Build muscle (Lean bulk):** a small surplus to grow faster with little fat gain.
   - **None of these fit? Describe your goal:** a free-text box. This leads to step 2.
2. **Suggestion**, only after "Describe your goal":
   - **With AI on:** Claude reads the text, maps it to an intent (lose fat, build muscle, both, stay as I am) and may ask one follow-up question.
   - **With AI off, or if the intent is still unclear:** three quick questions. What matters most to you? How long have you been lifting? What's your body fat? Body fat comes from a recent scan, or is estimated from height and weight.
   - **The result:** a recommended goal with a one-line reason ("You want both and you're new to lifting, so a recomp fits"). Confirm it, or pick another card.
3. **Pace** (not for Maintain): Gentle, Standard or Aggressive, with the projection.
4. **Finish line** (§4.7).
5. **Targets:** each number, with where it came from.

**Recommendation rules.** These are starting defaults; the algorithm is in TRD §5.7.

| Intent | Recommended goal |
|--------|------------------|
| Lose fat | Fat loss. At a BMI below 18.5: Maintain, with an explanation. |
| Both | Recomp if you're new to lifting (under a year) or carrying more fat (body fat ≥ 20% for men, ≥ 28% for women; BMI ≥ 27 when body fat is unknown). Otherwise a lean bulk now and a fat-loss phase later, because recomposition is slow for lean, trained lifters. <!-- pii-ok: thresholds --> |
| Build muscle | Lean bulk, unless body fat is high (≥ 25% for men, ≥ 32% for women; BMI ≥ 30 when unknown). Then Recomp first. <!-- pii-ok: thresholds --> |
| Stay as I am | Maintain. |

### 4.7 Finish line and what comes next

- **One finish line per goal:** a target weight, a target body-fat % (needs scans), a date, or none.
- **Suggested defaults:**
  - Fat loss and recomp: the suggested goal weight (§4.2).
  - Lean bulk: a date about 16 weeks out.
  - Maintain: none.
- **Reaching it** means on the trend, not on a single weigh-in. Reaching it, or hitting the date, shows a **Goal reached** card with a suggested next phase:
  - After fat loss or a recomp: 2 weeks at maintenance, then choose a new goal.
  - After a lean bulk: maintain, or a fat-loss phase.

  You can accept, change it, or keep going.
- **Long deficits:** after 12 weeks in a deficit without reaching the finish line, the app suggests a 1–2 week maintenance break. Accepting pauses the goal, and it resumes automatically.

### 4.8 How the goal shapes the screens

| | Fat loss | Recomp | Maintain | Lean bulk |
|---|---|---|---|---|
| **Main number on Today** | Calories left | Protein left | Calories within the ±range | Calories still to eat (under-eating is the usual failure) |
| **Nudges** | Protein, steps | Protein | Few | "You're 600 kcal short tonight" |
| **Progression messaging** | Slower stall window; "holding strength in a deficit is a win" | Slower stall window | Standard | Standard |
| **Weekly check-in flags** | Loss well below pace for 2+ weeks | Waist and trend weight both flat for 4 weeks | Drift beyond ±1% over 4 weeks | Gain too slow (< 0.1%/week) or too fast (> 0.5%/week) for 2 weeks |
| **Weekly review headline** | Loss rate, strength held | Waist and body composition | Stability, strength | Gain rate, strength |

**Daily target, judged weekly.**
- The calorie target is the same every day.
- Today shows what's left today, plus a small line with the 7-day average against target.
- Check-ins and the weekly review judge 7-day averages.
- A single day over target is never styled as a failure. It just shows up in the average.

### 4.9 Guardrails

- **Low BMI:** Fat loss and recomp are unavailable at a BMI below 18.5. The app suggests Maintain and explains why.
- **Floor:** intake never goes below BMR, or below 1,500 / 1,200 kcal.
- **Aggressive pace** shows a plain warning about muscle loss and sticking with it. It's still subject to the caps.
- **Under 18:** deficits are limited to Gentle, with a note to involve a parent or doctor.
- **Pregnancy and breastfeeding:** an onboarding toggle that disables deficits and shows a note (P1).
- **Not medical advice:** the app says so, and suggests checking with a professional if you have a medical condition.

### 4.10 Goal requirements

| ID | Pri | Requirement |
|----|-----|-------------|
| GL-01 | P0 | Goal cards with one-line descriptions, plus "Describe your goal" (§4.6). |
| GL-02 | P0 | The guided three-question suggestion, which works offline, ending in a reasoned recommendation that you confirm or change. |
| GL-03 | P1 | AI interpretation of a free-text goal, falling back to GL-02. |
| GL-04 | P0 | Pace presets with a projection. Exact values under Advanced. |
| GL-05 | P0 | One active goal, with an optional finish line. |
| GL-06 | P1 | The Goal reached card and next-phase suggestion, and the maintenance-break suggestion after 12 weeks in a deficit. |
| GL-07 | P0 | Today's main number follows the goal (§4.8). |
| GL-08 | P1 | Goal-specific nudges and weekly check-in flags (§4.8). |
| GL-09 | P0 | Guardrails from §4.9: BMI, floor, aggressive warning, under 18. The pregnancy toggle is P1. |
| GL-10 | P0 | Daily target judged weekly: the 7-day average on Today, and no failure styling for a single day over. |

## 5. Product principles

1. **Speed over completeness.** Defaults come from last time, and optional fields stay out of the way.
2. **Trends over single days.** Weight is smoothed and calories are judged as weekly averages.
3. **Nudge, don't nag.** Each exercise gets one clear suggestion. Notifications are few and opt-in.
4. **Honest numbers.** Estimates show their confidence, noise is labelled as noise, and workouts never "earn" extra food.
5. **Configuration over code.** Anything that varies between people is a persona setting.
6. **AI is an accelerator, not a dependency.** Every flow works with AI off. Training features never use AI.
7. **Local and private.** Data stays on the phone. Only AI requests leave the device, and only when AI is switched on.

## 6. Core journeys

Examples use the reference persona.

- **J0, first run.** Pick a preset and enter your body data. Choose a goal from the cards, or describe it and get a suggestion (§4.6). Pick a pace and a finish line, then review experience, diet, region, split and extras. The app shows your targets with a plain explanation of where each number comes from.
- **J1, strength day.**
  1. The Today screen shows "Pull is next". Tap Start.
  2. Each exercise shows *last time* and *today's target*, e.g. `Lat pulldown: last 45 kg × 12/11/10 → today 45 × 12/12/10`.
  3. Tick a set to accept the prefilled values, or adjust them with steppers. The rest timer starts on its own and counts down in the notification.
  4. At Finish, a summary shows PRs, targets hit and what changes next time ("Lat pulldown goes to 50 kg").
- **J2, extra session** (yoga, for the reference persona). After the class, tap *Log class*. It's prefilled from last time. Save, and it counts toward the weekly plan.
- **J3, logging a meal.** Tap + on a meal and choose one of: *My meals* (one tap), *Search* (the region's database plus your own foods), *Describe it* (AI, when on) or *Quick add*. Review and save.
- **J4, before ordering in.** *Will it fit?* shows what a dish does to today's calories and protein before you order. Log it in one tap if you go ahead.
- **J5, morning check-in.** Weigh in on your primary weight source and roughly log how many hours you slept. The app shows the trend, not just today's number.
- **J6, scan day** (if you have body-composition scans).
  1. A reminder at your usual slot shows the protocol.
  2. After the scan, share the result screen from the InBody app (or type in the key numbers).
  3. The body-composition chart updates.
- **J7, weekly check-in.** See the week's 7-day averages against your goal's headline (§4.8). If a check-in flag fires (e.g. a bulk gaining too slowly), the app proposes an adjusted calorie target. You accept or edit.
- **J8, stall.** An exercise hasn't improved within its stall window. A card offers a deload, a swap or a hold, and one tap applies it.
- **J9, finish line and goal switch.** Reach the finish line on the trend and a Goal reached card suggests the next phase (§4.7). Or change the goal any time, e.g. from recomp to lean bulk. Either way the app explains the new targets and starts a fresh baseline.

## 7. Functional requirements

**P0** = MVP, **P1** = v1.1, **P2** = later. **★** = a feature beyond basic logging that matters for body-composition goals (rationale in §8).

### 7.1 Profile and targets setup

| ID | Pri | Requirement |
|----|-----|-------------|
| PR-01 | P0 | Onboarding captures sex, date of birth, height, current weight, goal weight (or a target body-fat %, see IB-05), activity level, units and primary weight source, on top of the persona settings in §4. |
| PR-02 | P0 | Compute starting targets for calories, protein, fat and carbs from the goal policy, with a plain-language explanation. Every target can be overridden. |
| PR-03 | P0 | ★ Configurable "day starts at" time (default 4 AM), so a late-night snack counts toward the right day. |
| PR-04 | P0 | Onboarding walks through the chosen split once to confirm the equipment and smallest weight step for each exercise. That sets the progression increments. |

### 7.2 Exercise library

| ID | Pri | Requirement |
|----|-----|-------------|
| EX-01 | P0 | Seeded library of about 80 common gym exercises, covering every exercise in the bundled split templates. Each has primary and secondary muscles, equipment, and compound or isolation. |
| EX-02 | P0 | Create custom exercises. |
| EX-03 | P0 | Per-exercise settings: equipment, weight increment, rep range, unilateral flag, and ★ setup notes ("seat 4, pin 3"). |
| EX-04 | P1 | ★ Substitutes: when a machine is taken, suggest exercises for the same muscle and movement pattern. |
| EX-05 | P2 | Form cues and images for each exercise. |

### 7.3 Routines and weekly plan

| ID | Pri | Requirement |
|----|-----|-------------|
| RT-01 | P0 | Routines with ordered exercises, target sets, rep range and rest time per exercise. |
| RT-02 | P0 | **Rotation, not calendar.** The app suggests the next routine in sequence (e.g. Push → Pull → Legs → Push…), so a missed day never breaks the plan. The weekly plan sets a target number of strength days plus any extra sessions. |
| RT-03 | P0 | Add, remove, reorder or swap exercises mid-workout without changing the template, with an option to save the changes back to it. Ad-hoc workouts are supported. |
| RT-04 | P0 | Split templates ship pre-loaded: Push/Pull/Legs, Full body A/B and Upper/Lower. Nothing is fixed: you can add, remove, reorder or swap any exercise, and change its sets, rep range, rest time, equipment and increment at any time. Rep ranges default from the experience policy (§4.3). |
| RT-05 | P1 | Supersets. |

**Reference Push/Pull/Legs template.** Defaults are 3 working sets per exercise. Equipment marked with "/" is confirmed at onboarding.

| Day | Exercise | Equipment (assumed) | Rep range |
|-----|----------|---------------------|-----------|
| Push | Bench press | Barbell | 6–10 |
| Push | Shoulder press | Dumbbell / machine | 8–12 |
| Push | Chest fly | Pec deck / cable | 10–15 |
| Push | Triceps pushdown | Cable | 10–15 |
| Push | Overhead triceps extension | Cable / dumbbell | 10–15 |
| Push | Lateral raise | Dumbbell | 12–20 |
| Pull | Lat pulldown | Cable | 8–12 |
| Pull | Chest-supported row | Machine / dumbbell | 8–12 |
| Pull | Back extension | Bodyweight, then + plate | 10–15 |
| Pull | Incline dumbbell curl | Dumbbell | 10–15 |
| Pull | Face pull | Cable | 12–20 |
| Pull | Shrug | Dumbbell | 10–15 |
| Legs | Hack squat | Plate-loaded machine | 8–12 |
| Legs | Leg extension | Machine | 10–15 |
| Legs | Leg curl | Machine | 10–15 |
| Legs | Calf raise | Machine | 12–20 |
| Legs | Hip adduction | Machine | 12–20 |
| Legs | Hip abduction | Machine | 12–20 |
| Legs | Hip thrust | Barbell / machine | 8–12 |

### 7.4 Strength workout logging

| ID | Pri | Requirement |
|----|-----|-------------|
| WL-01 | P0 | Log each set as weight × reps, with set type: warm-up, working, drop or failure. Only working sets drive progression. |
| WL-02 | P0 | Prefill every set from the matching set last session (shown as ghost values). Ticking ✓ accepts them. |
| WL-03 | P0 | Gym-sized steppers (weight ± increment, reps ± 1) plus direct numeric entry. |
| WL-04 | P0 | ★ Optional one-tap effort per set as **RIR**, reps in reserve (0, 1, 2, 3+). It makes the overload calls smarter (§7.5). |
| WL-05 | P0 | The rest timer starts on its own after a set, uses a per-exercise default, shows in the ongoing notification and on the lock screen, and vibrates when it ends. |
| WL-06 | P0 | A workout survives the app being killed or the phone restarting. Each set is saved the moment it's ticked. |
| WL-07 | P0 | Notes on the session and on each exercise. Option to keep the screen on during a workout. |
| WL-08 | P0 | Workout summary: duration, sets, volume, PRs, targets hit or missed, and what changes next time. |
| WL-09 | P0 | Bodyweight exercises (reps only) and weighted bodyweight exercises (+ load), such as back extensions holding a plate. Assisted exercises (− load) are P1. |
| WL-10 | P1 | Edit or delete past workouts. |
| WL-11 | P1 | ★ Warm-up set suggestions for the first heavy lift of the day. |
| WL-12 | P2 | ★ Plate calculator. |

### 7.5 Progressive overload coaching (the core)

| ID | Pri | Requirement |
|----|-----|-------------|
| PO-01 | P0 | The default scheme is **double progression**. You work within a rep range (e.g. 8–12). When every working set reaches the top of the range, the weight goes up by the exercise's increment and the reps reset to the bottom. |
| PO-02 | P0 | Each exercise shows exactly one target before and during the workout: **Increase** (`50 kg × 8`), **Add reps** (`45 × 12/12/10`), **Repeat** (same weight, get every set back into the range) or **Deload**. |
| PO-03 | P0 | Live feedback when a ticked set meets or beats its target ("+1 rep vs last time"). |
| PO-04 | P0 | PR detection: heaviest weight, most reps at a given weight, best estimated 1-rep max, best session volume. |
| PO-05 | P0 | **Stall detection.** If an exercise shows no improvement in estimated 1RM within the stall window set by the experience policy (§4.3), offer: deload 10% and rebuild, swap the variation, or hold. You choose and the engine adapts. |
| PO-06 | P0 | ★ **Big-jump guard.** When the next weight step is more than 10% of the current weight, require 2 extra reps on every set before the jump. Dumbbell and isolation moves often jump 20–30%, e.g. 7.5 → 10 kg. |
| PO-07 | P0 | You can always override a suggestion. The override is recorded. |
| PO-08 | P1 | ★ RIR-aware jumps per the experience policy. For example, a beginner who hits the top of the range with RIR ≥ 3 on every set gets a double increment. |
| PO-09 | P1 | Per-exercise charts of estimated 1RM and top set over time. |
| PO-10 | P1 | ★ Weekly hard sets per muscle group, for information rather than as a nag, with an editable reference band. |
| PO-11 | P1 | ★ A workout-morning notification: "3 lifts are due for a weight increase today." |

### 7.6 Extra sessions (yoga, mobility, cardio, sport)

| ID | Pri | Requirement |
|----|-----|-------------|
| YG-01 | P0 | Log a session in one tap, prefilled from the last one of that kind: type (e.g. yoga style), duration, optional intensity (1–5) and notes. |
| YG-02 | P0 | Configured extra sessions are part of the weekly plan. On a scheduled class day the Today screen shows it, and the strength rotation carries on around it. Sessions count toward weekly adherence. |
| YG-03 | P1 | Optional focus areas (hips, hamstrings, back…). |
| YG-04 | P1 | ★ **Monthly mobility markers** (sit-and-reach distance, deep-squat hold time, toe touch yes/no), so mobility work has measurable progress too. |

### 7.7 Nutrition

**Targets**

| ID | Pri | Requirement |
|----|-----|-------------|
| NU-01 | P0 | Daily calorie target from the goal policy (§4.2), with a safety floor. |
| NU-02 | P0 | ★ **Protein is the hero metric.** The target comes from the goal policy and is shown first on every nutrition view. |
| NU-03 | P0 | ★ **Protein ramp.** The first week measures what you eat now. After that, the target rises by about 15 g a week until it reaches the full target. On by default for vegetarian and vegan diets, whose starting intake is often low; optional for others. |
| NU-04 | P0 | Fat has a floor. Carbs fill the remaining calories. Fiber is shown but not targeted. |
| NU-05 | P1 | ★ **Adaptive target.** After 2–3 weeks of data, estimate your real maintenance calories from logged intake against the trend in your weight. The weekly check-in proposes the new target for your goal. |
| NU-06 | P1 | ★ Optional daily snack allowance inside the calorie target (e.g. 150 kcal), so a planned snack isn't a "failure". |

**Logging**

| ID | Pri | Requirement |
|----|-----|-------------|
| NU-10 | P0 | Meal slots: Breakfast, Lunch, Snacks, Dinner (editable). |
| NU-11 | P0 | **Bundled food database for the region**, filtered to your diet. *Indian:* INDB's ~1,000 dishes plus ingredients, fetched at build time (TRD §6.4). *International:* Open Food Facts lookups plus your custom foods. This is what makes logging work with AI off. |
| NU-12 | P0 | Custom foods, per 100 g or per serving, with **regional portion units** mapped to grams that you can calibrate to your own utensils. Indian: roti, katori, glass, piece, tbsp, plate. International: cup, slice, piece, tbsp. |
| NU-13 | P0 | ★ **Saved meals** ("my usual breakfast") logged in one tap. Copy a meal or a whole day from any earlier day. |
| NU-14 | P0 | Quick add with just calories and protein, for when you can't be bothered. |
| NU-15 | P0 | ★ **Origin tag** on every entry: *Home*, *Ordered* or *Packaged*. Restaurant food usually carries far more oil, butter or ghee than the same dish made at home, and estimates for *Ordered* account for that. |
| NU-16 | P0 | Daily view: calories and protein remaining, macro split, and per-meal totals. |
| NU-17 | P0 | ★ **Describe it (AI).** Type something like "2 phulka, 1 katori rajma, cucumber salad, 1 glass chaas" and get an itemised estimate with grams, macros and a confidence level. The prompt follows your diet and region. Edit it, then save. One tap saves it as a meal for next time. Available when AI is on. |
| NU-18 | P1 | ★ **Will it fit?** Describe a dish before ordering and see its effect on today's calories and protein, without logging it. One tap logs it if you order. Uses AI when on, and your foods plus the database when off. |
| NU-19 | P1 | ★ **Label scan.** Photograph a packet's nutrition label and a new food is filled in by on-device text recognition. AI corrects it when on. |
| NU-20 | P1 | ★ **Photo (AI).** Snap the plate and get the same review flow as Describe it. |
| NU-21 | P1 | Barcode scan for packaged food (Open Food Facts; coverage varies by country, so label scan is the backup). |
| NU-22 | P1 | Recipe builder for home recipes: ingredients in, macros per portion out. |
| NU-23 | P1 | ★ **Evening protein nudge.** If you're well short of target at a set time, show how much is left and suggest 2–3 of *your own* frequent protein foods that fit your diet, ranked by protein per 100 kcal. |

### 7.8 Body metrics

| ID | Pri | Requirement |
|----|-----|-------------|
| BM-01 | P0 | Weigh-ins from your chosen primary source: a home scale (daily, the default), weekly body-composition scans, or a gym scale. Each weigh-in is tagged with its source. A smoothed **trend line** works with daily or weekly data and shows the weekly rate of change against your goal. Readings from other sources are plotted but kept out of the trend, because each scale and setting reads differently. |
| BM-02 | P0 | ★ **Weekly waist measurement.** A cheap, reliable body-composition signal, especially for recomp and lean bulk. |
| BM-03 | P1 | Other tape measurements (chest, arms, thighs, hips). |
| BM-04 | P1 | ★ Progress photos (front, side, back) stored privately on the device, with side-by-side comparison. |

### 7.9 Body-composition scans (optional)

For personas with access to InBody or another bioimpedance (BIA) machine.

| ID | Pri | Requirement |
|----|-----|-------------|
| IB-01 | P0 | Log a scan: weight, skeletal muscle mass, body fat mass, body fat %, visceral fat level. Optional: the machine's BMR, segmental lean mass, ECW/TBW ratio, score. Saving a scan also records it as a weigh-in. |
| IB-02 | P0 | ★ **Consistent conditions.** A fasted morning scan is ideal, but often unrealistic when the machine is at a gym or office, so consistency stands in for it. You pick a usual slot (e.g. Monday 10:00). A scan counts as on-protocol when it's within 90 minutes of your usual time, at least 2 hours after eating, and before any workout that day. Off-protocol scans are flagged. The protocol is shown before each scan. |
| IB-03 | P1 | ★ **Share from the InBody app.** Share the result screen (or a photo of the paper sheet) to Recomp, and AI fills in the form for you to review. With AI off, you type the numbers in. |
| IB-04 | P1 | ★ **Body-composition chart:** muscle mass and fat mass on one chart, alongside trend weight and waist. Changes smaller than measurement noise (roughly 1 kg of muscle or 1% body fat between scans) are labelled "within noise". |
| IB-05 | P1 | ★ **Scan-informed targets.** With a scan from the last 60 days, the app estimates BMR from your lean mass, which is more accurate than height-and-weight formulas. It can also suggest a goal weight for a target body-fat %: lean mass ÷ (1 − target %). |
| IB-06 | P1 | ★ A reminder at your usual scan slot (weekly by default). |
| IB-07 | P1 | **Auto-import through Health Connect** of what the InBody app writes there: weight, body fat % and BMR. Lean mass is derived from those. The app then asks only for the missing fields: skeletal muscle mass and visceral fat. |

### 7.10 Activity and recovery

| ID | Pri | Requirement |
|----|-----|-------------|
| AR-01 | P1 | ★ **Daily steps** read through Health Connect, from whichever app writes them (Google Fit, Samsung Health, a watch app…). A step goal (e.g. 8,000). |
| AR-02 | P1 | ★ **Sleep hours**, entered in the morning check-in or read from Health Connect when a tracker provides them. |
| AR-03 | P2 | ★ Pre-workout readiness check (sleep, energy, soreness, each 1–5), shown next to performance to explain off days. |

### 7.11 Today screen, progress and weekly review

| ID | Pri | Requirement |
|----|-----|-------------|
| HM-01 | P0 | **Today screen:** next workout (or rest day, or scheduled extra session), calories and protein remaining, trend weight, and weekly plan progress. |
| HM-02 | P0 | Exercise history: every past session for an exercise, newest first. Also the scan history as a table. |
| HM-03 | P1 | ★ **Weekly review**, built around the goal's success criteria (§4.2): sessions done vs planned, average calories vs target, protein days hit, share of calories from orders and snacks, change in trend weight, waist and scan results, PRs, stalled lifts, average steps and sleep. |
| HM-04 | P1 | Consistency calendar for workouts and logged days. No punishing streaks. |
| HM-05 | P1 | ★ Home-screen widget: calories and protein left, plus the next workout. |
| HM-06 | P2 | ★ AI weekly coach note: Claude reads the week's summary and writes 3 concrete suggestions for your goal. |

### 7.12 AI features and the no-AI mode

| ID | Pri | Requirement |
|----|-----|-------------|
| AI-01 | P0 | **AI is a setting: Off or On.** With AI off, every feature works and AI buttons are hidden. Food logging then runs on the database, saved meals, label scan and quick add. |
| AI-02 | P0 | **AI on but unreachable** (offline, timeout, out of credit, service error): the app never blocks. It falls back to search, saved meals and quick add, and keeps your description as a draft to estimate later. |
| AI-03 | P0 | This month's AI spend shown in Settings, with an optional monthly cap. Once the cap is reached, AI pauses until the next month. |
| AI-04 | P1 | ★ **Offline Describe it.** A local parser reads "2 roti, 1 katori dal" by matching quantities, units and names against your foods and the bundled database. Same text box with or without AI. AI handles what the parser can't. |

**Where AI is used, and where it isn't**

| Use | Priority | Without AI |
|-----|----------|------------|
| Describe a meal → itemised macros | P0 | Search, saved meals, quick add. Local parser at P1. |
| Will it fit? | P1 | Local parser plus the database. |
| Meal photo → itemised macros | P1 | Not available. Use the text flows. |
| Nutrition label photo → new food | P1 | On-device text recognition, then manual correction. |
| InBody screenshot or sheet photo → form | P1 | Health Connect import (IB-07), then type in the rest. |
| Weekly coach note | P2 | The weekly review shows the numbers without commentary. |
| **Workouts and progression** | **Never** | **Plain rules, always offline.** |

AI uses a Claude API key that each person brings. A claude.ai subscription login can't be used by apps, but Max plans include monthly API credits that can fund the key. See TRD §6.2.

### 7.13 Reminders

| ID | Pri | Requirement |
|----|-----|-------------|
| RM-01 | P1 | Opt-in reminders: workout day, morning check-in, "lunch not logged" by a set time, evening protein (NU-23), weekly check-in, scan slot. At most 2 a day. |

### 7.14 Data

| ID | Pri | Requirement |
|----|-----|-------------|
| DA-01 | P0 | Manual export and import of all data to a file, which can be saved to cloud storage through the system file picker. |
| DA-02 | P1 | ★ Automatic weekly backup to a folder you choose. |
| DA-03 | P1 | CSV export of workouts, the food log and scans. |
| DA-04 | P1 | ★ Write workouts, nutrition and weight to Health Connect, so other apps can see them. |
| DA-05 | P2 | Import history from other workout apps' CSV exports (Strong, Hevy). |

## 8. Design rationale: what matters most for body-composition goals

Ranked by impact.

| # | Feature | Why it matters |
|---|---------|----------------|
| 1 | **Protein as the hero metric, with a ramp** (NU-02, NU-03, NU-23) | After energy balance, protein is the biggest lever for keeping and building muscle. Vegetarian and vegan diets often start well short of target; the ramp and nudges built from your own foods make it reachable. |
| 2 | **Fast logging without needing AI** (NU-11, NU-13, AI-01, AI-04) | Logging fatigue is the #1 reason people stop tracking. A regional database plus saved meals covers most days. AI is a speed-up, not a dependency. |
| 3 | **Ordering and snacking visibility** (NU-15, NU-18, NU-19, HM-03) | Ordered food and packaged snacks are where hidden calories live. Tagging them, checking a dish before ordering, and seeing their weekly share make them visible. |
| 4 | **Body-composition scans with noise bands** (IB-02, IB-04) | The only direct view of muscle and fat moving separately, which is the whole point of a recomp or a lean bulk. Consistent conditions and noise labels stop one off scan from misleading you. |
| 5 | **Adaptive calorie target** (NU-05, IB-05) | Formulas misjudge an individual's maintenance by ±10–15%. Your own intake and weight trend find the real number for any goal. Adaptive targets also absorb *consistent* logging error. |
| 6 | **Trend weight, waist and photos** (BM-01, BM-02, BM-04) | Daily weight swings 1–2 kg with water and salt. During a recomp the scale can stall for weeks while the waist shrinks. |
| 7 | **Stall detection and deloads, tuned by experience** (PO-05, §4.3) | Lifters in a deficit and experienced lifters stall sooner. The app should say what to do next, not just show a flat line. |
| 8 | **RIR per set and the big-jump guard** (WL-04, PO-06) | They turn "add weight" from a blunt rule into a sensible call, especially for dumbbell and isolation work. |
| 9 | **Daily steps** (AR-01) | For heavier bodies, 8–10k steps is roughly 200–300 kcal a day of extra burn without hurting recovery from lifting. |
| 10 | **Rest timer in the notification, crash-proof workouts** (WL-05, WL-06) | Not exciting, but it decides whether the app gets used in the gym. |
| 11 | **Weekly review per goal** (HM-03) | One place to check every lever once a week, against what success means for your goal. |
| 12 | **Backups** (DA-01, DA-02) | No server: losing months of history would be the worst possible bug. |

**Outside the app:** a basic bathroom scale makes the adaptive target adjust after about 3 weeks of data instead of 4 or more, with far less noise. A tape measure covers the weekly waist check.

## 9. Release plan

| Release | Scope | Exit criterion |
|---------|-------|----------------|
| **MVP** (all P0) | Personas: the four goals with goal cards, guided suggestion, pace presets, finish line and guardrails (GL P0); experience, diet, Indian and International regions, split templates, presets, onboarding (PS-01 to PS-03); goal-driven Today screen with 7-day averages; profile and targets with protein ramp; strength logging (prefill, RIR, rest timer, crash-proof); progression engine with PRs and stall detection; extra-session log; nutrition (regional food database, custom foods and units, saved meals, copy day, quick add, origin tags, AI Describe it); AI on/off with fallbacks and spend cap; weight sources, trend and waist; scan entry with the conditions check; Today screen; history; export and import. | Used for every workout and every day of food for 2 straight weeks. |
| **v1.1** (P1) | Goal switch, Goal reached card and maintenance breaks; AI goal interpretation; goal-specific nudges and check-in flags; presets as data files; adaptive target with weekly check-in and review; Will it fit?; label scan; offline describe parser; AI meal photos; InBody share and Health Connect import; body-composition chart and scan-informed targets; steps through Health Connect; sleep; barcode; recipes; tape measurements and photos; charts; muscle-group volume; reminders; widget; auto-backup; supersets; warm-ups; snack allowance. | The weekly review answers "is my goal on track?" without opening anything else. |
| **v2** (P2) | Persona export and import, AI weekly coach, readiness check, plate calculator, workout-history import, periodised blocks for advanced lifters, Wear OS companion. | — |

## 10. Success metrics (first 12 weeks of use)

**Is the app doing its job?**
- A repeated set is logged in ≤ 2 taps and under 5 seconds.
- A saved meal is logged in under 15 seconds. A described meal, including review, in under 45 seconds.
- Food is logged on at least 6 of 7 days. Every workout is logged.
- At least 90% of planned sessions are completed.
- Protein hits that week's target on at least 5 days a week.

**Is the goal on track?** Personal targets per goal, not promises:

| Goal | Outcome signals |
|------|-----------------|
| Fat loss | Trend weight falling 0.5–1% a week; estimated 1RM held on main lifts |
| Recomp | Trend weight falling 0.5–0.75% a week; waist and scan fat mass down; scan muscle mass holding or rising; 1RM up |
| Maintain | Trend weight within ±1%; 1RM up |
| Lean bulk | Trend weight rising 0.25–0.5% a week; 1RM up; waist rising slowly or not at all |

## 11. Open questions

1. Which presets beyond the five in §4.4 are worth shipping? Candidates: older adults, a strength-focused powerlifting split, and returning after a break.
2. Should lb/in display ship in the MVP or in v1.1?
3. Is "Pescatarian" enough, or does the diet filter also need custom exclusions (e.g. Jain: no root vegetables)?
