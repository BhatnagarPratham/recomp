# Recomp: Product Requirements Document

*Draft v1.1, 2026-10-18: the MVP as built, plus the v1.1 designs (§12), settled on 2026-10-18. Companion doc: [TRD.md](TRD.md). Open source under MIT: fork it and configure it for yourself.*

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
- **Reference weight** is your goal weight if you set one, otherwise your current weight. There is one goal weight per goal, set in the finish-line step (§4.7). It stays the reference weight whatever finish line you pick, so a date or no finish line doesn't change protein.
- **Suggested goal weight** (fat loss and recomp): from a recent body-composition scan if you have one (IB-05), otherwise the BMI-25 weight for your height. If your BMI is already 25 or less, the suggestion is 5% below your current weight, never below BMI 20.
- **Safety floor:** intake never goes below your BMR, or below 1,500 kcal (men) / 1,200 kcal (women). More guardrails are in §4.9.
- **In a deficit** (fat loss, recomp), the progression engine adds one session to its stall window, because progress is slower when eating below maintenance.

### 4.3 Experience policies

These are starting defaults, to be tuned with real data.

| Experience | Rep ranges (compound / isolation / high-rep) | Stall window, in sessions | Jumps | Deloads |
|------------|----------------------------------------------|---------------------------|-------|---------|
| **Beginner** | 8–12 / 10–15 / 12–20 | 3 (4 in a deficit) | Double increment allowed when every set hits the top with RIR ≥ 3 | Only on a stall |
| **Intermediate** | 6–10 / 10–15 / 12–20 | 4 (5 in a deficit) | Single increment | Suggested every 6–8 weeks, or on a stall |
| **Advanced** | 5–8 / 8–12 / 10–15 | 5 (6 in a deficit) | Single increment. Periodised blocks are P2. | Suggested every 4–6 weeks |

Each exercise in the library has a rep class. **High-rep** covers small muscles that respond to higher reps: lateral raises, rear delts and face pulls, calves, and hip abduction and adduction. A changed experience level moves every rep range you haven't edited.

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
   - **None of these fit? Describe your goal:** a free-text box. This leads to step 2. With AI off the card reads **Not sure? Answer 3 quick questions** and has no text box, since nothing would read the text.
2. **Suggestion**, only after "Describe your goal":
   - **With AI on:** Claude reads the text, maps it to an intent (lose fat, build muscle, both, stay as I am) and may ask one follow-up question.
   - **With AI off, or if the intent is still unclear:** three quick questions. What matters most to you? How long have you been lifting? What's your body fat? Body fat comes from a recent scan, or is estimated from height and weight (shown as your BMI). You can also type a body-fat % you know; it's used for this suggestion only and isn't saved. The lifting answer pre-fills your experience level.
   - **The result:** a recommended goal with a one-line reason ("You want both and you're new to lifting, so a recomp fits"). Confirm it, or pick another card.
3. **Pace** (not for Maintain): Gentle, Standard or Aggressive, each with its projection. Picking Aggressive shows its warning once, and you acknowledge it (§4.9).
4. **Finish line** (§4.7).
5. **Targets:** each number, with where it came from. From Settings, this step shows the old and new targets side by side before saving.

**Recommendation rules.** These are starting defaults; the algorithm is in TRD §5.7.

| Intent | Recommended goal |
|--------|------------------|
| Lose fat | Fat loss. At a BMI below 18.5: Maintain, with an explanation. |
| Both | Recomp if you're new to lifting (under a year) or carrying more fat (body fat ≥ 20% for men, ≥ 28% for women; BMI ≥ 27 when body fat is unknown). Otherwise a lean bulk now and a fat-loss phase later, because recomposition is slow for lean, trained lifters. <!-- pii-ok: thresholds --> |
| Build muscle | Lean bulk, unless body fat is high (≥ 25% for men, ≥ 32% for women; BMI ≥ 30 when unknown). Then Recomp first. <!-- pii-ok: thresholds --> |
| Stay as I am | Maintain. |

### 4.7 Finish line and what comes next

- **One finish line per goal:** your goal weight, a target body-fat % (needs scans; shown but unavailable until you have one), a date, or none. The goal weight is edited here.
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
- **Maintain's range** is the target ±5%, rounded to 10 kcal (e.g. 2,480–2,740 for a 2,610 target).
- Today shows what's left today, plus a small line with the 7-day average against target. The average covers the last 7 complete days (today is left out) and counts only days with food logged; it shows calories and protein against their targets and how many days were logged. With nothing logged it says it appears once you log food.
- Check-ins and the weekly review judge 7-day averages.
- A single day over target is never styled as a failure. It just shows up in the average.
- Recomp's main number uses the ramped protein target (NU-03) while the ramp runs.

### 4.9 Guardrails

- **Low BMI:** Fat loss and recomp are unavailable at a BMI below 18.5. Their goal cards are greyed out with the reason, and the app suggests Maintain.
- **Floor:** intake never goes below BMR, or below 1,500 / 1,200 kcal.
- **Aggressive pace** shows a plain warning about muscle loss and sticking with it, acknowledged once per goal. It's still subject to the caps.
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
6. **AI is an accelerator, not a dependency.** Every flow works with AI off. The set-by-set progression engine never uses AI. The weekly programme review (HM-06) can suggest changes to the plan, and you accept each one.
7. **Local and private.** Data stays on the phone. Only AI requests leave the device, to the provider you chose and only when AI is switched on, plus food lookups to Open Food Facts. Body data is never sent to AI, except the weekly trends you tick for a programme review. Beyond that, data goes only where you send it: a backup folder you choose, or apps you let read it through Health Connect (which may sync it to their own cloud).

## 6. Core journeys

Examples use the reference persona.

- **J0, first run.** Pick a preset and enter your body data. Choose a goal from the cards, or describe it and get a suggestion (§4.6). Pick a pace and a finish line, then review experience, diet, region, split and extras. The app shows your targets with a plain explanation of where each number comes from.
- **J1, strength day.**
  1. The Today screen shows "Pull is next". Tap Start.
  2. Each exercise shows *last time* and *today's target*, e.g. `Lat pulldown: last 45 kg × 12/11/10 → today 45 × 12/12/10`.
  3. Tick a set to accept the prefilled values, or adjust them with steppers. The rest timer starts on its own and counts down in the notification.
  4. At Finish, a summary shows PRs, targets hit and what changes next time ("Lat pulldown goes to 50 kg").
- **J2, extra session** (yoga, for the reference persona). After the class, tap *Log class*. It's prefilled from last time. Save, and it counts toward the weekly plan.
- **J3, logging a meal.** On the Food tab, tap + on a meal and choose one of: *My meals* (one tap), *Search* (the region's database plus your own foods), *Describe it* (AI, when on) or *Quick add*. Review and save.
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
| PR-03 | P0 | ★ Configurable "day starts at" time (default 4 AM; midnight to 6 AM in hourly steps), so a late-night snack counts toward the right day. Changing it never moves days already logged. |
| PR-04 | P0 | Onboarding walks through the chosen split once to confirm the equipment and smallest weight step for each exercise. That sets the progression increments. |
| PR-05 | P1 | Show and enter body weight, lifts and lengths in lb and in. Storage stays metric (§4.1). |

**Equipment walkthrough (PR-04).** One scrolling list of the split's exercises, grouped by routine:
- Where the template lists alternatives (the "/" rows in §7.3), you pick the variant your gym has, e.g. dumbbell or machine shoulder press. Picking one swaps the exercise in the routine, because a machine press and a dumbbell press keep separate histories.
- Every exercise shows its smallest weight step as chips (1, 1.25, 2, 2.5, 4 or 5 kg, or a custom value). Defaults come from the equipment: 2.5 kg for barbells (1.25 kg plates each side), dumbbells (per dumbbell) and plate-loaded machines, 5 kg for pin-loaded machines and cables, 4 kg for kettlebells.
- Running it again on the same split swaps variants in place and keeps your other routine edits. Choosing a different split archives the current routines (history stays) and builds the new ones.

**Training setup** (split, experience, strength days per week, and extra sessions with their days, then the walkthrough) was a stand-in for onboarding in M1. From M3 it's part of onboarding's review step, and it can still be re-run from the Train tab or Settings.

**Onboarding (PS-01, PR-01, PR-02, J0).** One step per screen, with Back on every step:
1. **Preset** (§4.4).
2. **About you:** sex (for the calorie formulas), date of birth, height, current weight and activity level. Activity has three choices: mostly sitting with little training (×1.2), a desk job plus 3–4 sessions a week (×1.375, the default), or an active job or daily training (×1.55).
3. **Goal** (§4.6), with **Pace** and **Finish line**.
4. **Your settings:** every other persona value pre-filled from the preset for review: experience, diet, food region, split and strength days, extra sessions, primary weight source, units, "day starts at" and the protein ramp. Values a preset leaves as *Asked* must be chosen before you can continue.
5. **Targets:** calories, protein, fat and carbs, each with a plain sentence on where it came from, and the "not medical advice" note.
6. **Gym equipment** (PR-04), only when routines need building: on a fresh install, or when you picked a different split.

- **First run** opens onboarding instead of Today. So does the first launch after upgrading from a version without a persona; the training settings you already had are carried over and pre-filled, and an unfinished workout can still be resumed.
- **Units:** kg and cm only in the MVP. The persona stores the choice, and lb and in arrive in v1.1 (PR-05).

**Settings (PS-02).** The last bottom tab, after Today, Food, Train and Body. Sections: Goal (opens the goal flow), Targets, About you, Training (opens Training setup), Food (diet, region, protein ramp, meals, portion sizes, hidden foods and where the food data comes from), AI (on or off, API key, model, this month's spend and the cap; §7.12), Body data (weight source, usual scan slot, units), Day starts at, Data (export and import, §7.14) and Developer (the M0 spike screen). Every row shows its current value.
- **Targets can be overridden** (PR-02): calories, protein and fat. Carbs always fill the remaining calories. An overridden number is labelled *set by you* and stays until you reset it. Calories can't be set below the safety floor (§4.9).
- **A change that moves your targets** (goal, pace, goal weight, body data, activity, an override) shows the old and new targets before it saves. Saving starts a new target version from today. Several saves on the same day replace that day's version; earlier days are never rewritten.

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

**Reference Push/Pull/Legs template.** Defaults are 3 working sets per exercise. Equipment marked with "/" is confirmed at onboarding. Rep ranges come from the rep class and the experience policy (§4.3); the column shows a beginner's.

| Day | Exercise | Equipment (assumed) | Rep class | Beginner reps |
|-----|----------|---------------------|-----------|---------------|
| Push | Bench press | Barbell | Compound | 8–12 |
| Push | Shoulder press | Dumbbell / machine | Compound | 8–12 |
| Push | Chest fly | Pec deck / cable | Isolation | 10–15 |
| Push | Triceps pushdown | Cable | Isolation | 10–15 |
| Push | Overhead triceps extension | Cable / dumbbell | Isolation | 10–15 |
| Push | Lateral raise | Dumbbell | High-rep | 12–20 |
| Pull | Lat pulldown | Cable | Compound | 8–12 |
| Pull | Chest-supported row | Machine / dumbbell | Compound | 8–12 |
| Pull | Back extension | Bodyweight, then + plate | Isolation | 10–15 |
| Pull | Incline dumbbell curl | Dumbbell | Isolation | 10–15 |
| Pull | Face pull | Cable | High-rep | 12–20 |
| Pull | Shrug | Dumbbell | Isolation | 10–15 |
| Legs | Hack squat | Plate-loaded machine | Compound | 8–12 |
| Legs | Leg extension | Machine | Isolation | 10–15 |
| Legs | Leg curl | Machine | Isolation | 10–15 |
| Legs | Calf raise | Machine | High-rep | 12–20 |
| Legs | Hip adduction | Machine | High-rep | 12–20 |
| Legs | Hip abduction | Machine | High-rep | 12–20 |
| Legs | Hip thrust | Barbell / machine | Compound | 8–12 |

**Full body A/B** (the Fat loss starter and Maintain presets), alternating A and B:

| Day | Exercises (alternatives after "/") |
|-----|------------------------------------|
| A | Back squat / hack squat / goblet squat · Bench press / dumbbell bench press / machine chest press · Seated cable row / machine chest-supported row · Dumbbell shoulder press / machine shoulder press · Leg curl · Cable crunch |
| B | Romanian deadlift / dumbbell Romanian deadlift · Lat pulldown · Incline dumbbell press / incline machine press · Leg press · Dumbbell lateral raise · Hanging knee raise |

**Upper/Lower** (the Lean bulk preset), alternating Upper and Lower:

| Day | Exercises (alternatives after "/") |
|-----|------------------------------------|
| Upper | Bench press / dumbbell bench press · Barbell row / machine chest-supported row / seated cable row · Dumbbell shoulder press / machine shoulder press / overhead press · Lat pulldown / pull-up · EZ-bar curl / dumbbell curl · Triceps pushdown |
| Lower | Back squat / hack squat · Romanian deadlift / dumbbell Romanian deadlift · Leg press · Leg curl · Standing calf raise · Cable crunch |

The templates and the ~80-exercise library are data files (`app/src/main/assets/seed/`), so a fork can change them without code.

**Rotation details (RT-02).** The next routine is the one after the last strength routine you completed. Discarded workouts, ad-hoc workouts and routines from an old split don't move it. Doing Legs when Pull was due makes Push next. Today offers *Start*, *Choose another* and *Empty workout*.

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

**Workout screen (WL-01 to WL-09).** Built for one hand between sets:
- **Exercise cards** scroll in the upper part of the screen. Each shows the exercise, its rep range and rest, setup notes, *Last: 45 × 12/11/10*, and compact set rows. A row shows the set type, weight × reps, RIR and a ✓. Values you haven't entered show as ghost values in a dimmer style.
- **Ghost values (WL-02).** From M2, working sets prefill from **today's target** (PO-02), so ticking ✓ hits the plan; repeating last time instead is a −1/+1 tap and ✓. Warm-ups, drop sets and sets beyond the target keep the rules below. Before M2, each set took the matching set from last session, pairing the nth set of a type with the nth set of that type last time, so warm-ups never prefill working sets. Without a match it copies the set above. With no history at all, reps start at the top of the range and the weight is blank until you enter one. Last session's warm-ups are recreated at the start; drop and failure sets aren't.
- **The set dock** sits at the bottom. It holds the selected set: weight − / + by the exercise's increment, reps − / + 1 (each control at least 56 dp), tap a value to type it, optional RIR chips (0, 1, 2, 3+), and a full-width ✓. Ticking saves the set and selects the next unticked one, so repeating last time's set is one tap. Tapping a row selects that set instead.
- **Set types:** tap the set's badge to make it a warm-up, working, drop or failure set. Bodyweight exercises show reps only; weighted bodyweight shows "+ kg"; dumbbell loads are per dumbbell and unilateral reps are per side.
- **Rest (WL-05):** ticking a set starts the exercise's rest. A banner in the dock and the ongoing notification count down, both with *Skip* and *+30 s*.
- **Changing the plan (RT-03):** from a card's menu, swap (only before its first set is ticked), remove, move up or down, edit notes. *Add exercise* and *Add set* are always there. Adding or deleting a working set changes that exercise's target, so it can be saved back.
- **Finishing:** unticked sets are dropped after a confirmation. An exercise with no sets is kept as skipped. If you changed the plan, the summary offers *Save changes to the routine*.
- **Resume (WL-06):** opening the app with an active workout asks *Resume workout?* with Resume, Finish and Discard. After 4 hours it suggests finishing, using the last ticked set's time as the end.
- **Keep screen on (WL-07)** is a switch in the workout menu, off by default; the lock-screen notification already shows the rest timer.
- The summary (WL-08) shows duration, sets, reps, volume and each exercise's sets in M1. PRs, targets hit and what changes next time arrive with the progression engine in M2.

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

**Progression in the app (M2):**
- **Target line.** Each exercise card shows *Last: 45 × 12/11/10* and *Today: 45 × 12/12/10 · add reps*. The first time it shows the baseline hint instead. Today's next-workout card previews every exercise's target, with ↑ where the weight goes up, e.g. "Bench press 42.5 × 8 ↑".
- **Live feedback (PO-03).** A ticked working set that meets or beats its target says how it compares with last time, e.g. "+1 rep", "+2.5 kg" or "target hit". A missed target says nothing: nudge, don't nag.
- **Stall card (PO-05).** When a lift stalls, a card sits at the top of that exercise in the workout. *No gain in 3 sessions* offers:
  - *Deload 10%*: today's target drops to 90% of the load at the bottom of the range, and stall detection starts afresh from today.
  - *Swap*: opens the picker, swaps the exercise in today's workout and in the routine.
  - *Hold*: hides the card until another full stall window passes with no gain over the best before the hold.
- **Overrides (PO-07).** Ticking a working set at a load other than the target's records an override on that exercise for the session. The engine always works from what you actually lifted, so an override never counts against you.
- **PRs (PO-04)** are found when the workout finishes: heaviest weight, most reps at a weight, best estimated 1RM, best session volume. At most one of each type per exercise per session, and none on the first session of an exercise, where everything would count.
- **Summary (WL-08):** adds PRs, targets hit or missed, and what changes next time, e.g. "Lat pulldown goes to 50 kg".
- **Exercise history (HM-02):** every past session for an exercise, newest first, with its sets, best estimated 1RM and PR badges. Opened from the exercise's menu in a workout and from its settings.

### 7.6 Extra sessions (yoga, mobility, cardio, sport)

| ID | Pri | Requirement |
|----|-----|-------------|
| YG-01 | P0 | Log a session in one tap, prefilled from the last one of that kind: type (e.g. yoga style), duration, optional intensity (1–5) and notes. |
| YG-02 | P0 | Configured extra sessions are part of the weekly plan. On a scheduled class day the Today screen shows it, and the strength rotation carries on around it. Sessions count toward weekly adherence. |
| YG-03 | P1 | Optional focus areas (hips, hamstrings, back…). |
| YG-04 | P1 | ★ **Monthly mobility markers** (sit-and-reach distance, deep-squat hold time, toe touch yes/no), so mobility work has measurable progress too. |

Each configured extra session has a kind (yoga, mobility, cardio or sport), a name such as "Yoga class" and optional weekdays. On a scheduled day Today shows it with *Log class*; other days it's a smaller button. Logging opens a short form prefilled from the last session of that name (style, duration, intensity 1–5, notes), and *Save* is the second tap. Weekly progress counts strength days against the target and each extra against its scheduled days. Weeks start on Monday.

### 7.7 Nutrition

**Targets**

| ID | Pri | Requirement |
|----|-----|-------------|
| NU-01 | P0 | Daily calorie target from the goal policy (§4.2), with a safety floor. |
| NU-02 | P0 | ★ **Protein is the hero metric.** The target comes from the goal policy and is shown first on every nutrition view. |
| NU-03 | P0 | ★ **Protein ramp.** The first week measures what you eat now. After that, the target rises by about 15 g a week until it reaches the full target. On by default for vegan, lacto-vegetarian and eggetarian diets, whose starting intake is often low; optional for others. The baseline week starts on your first day with food logged and needs 5 logged days out of 7; until then the full target is shown with a note that the ramp starts when you log food. |
| NU-04 | P0 | Fat has a floor. Carbs fill the remaining calories. Fiber is shown but not targeted. |
| NU-05 | P1 | ★ **Adaptive target.** After 2–3 weeks of data, estimate your real maintenance calories from logged intake against the trend in your weight. The weekly check-in proposes the new target for your goal. |
| NU-06 | P1 | ★ Optional daily snack allowance inside the calorie target (e.g. 150 kcal), so a planned snack isn't a "failure". |

**Logging**

| ID | Pri | Requirement |
|----|-----|-------------|
| NU-10 | P0 | Meal slots: Breakfast, Lunch, Snacks, Dinner (editable). |
| NU-11 | P0 | **Bundled food database for the region**, filtered to your diet. *Indian:* INDB's ~1,000 dishes, fetched at build time (TRD §6.4), plus bundled protein-rich basics. *International:* Open Food Facts lookups plus your custom foods. This is what makes logging work with AI off. |
| NU-12 | P0 | Custom foods, per 100 g or per serving, with **regional portion units** mapped to grams that you can calibrate to your own utensils. Indian: roti, katori, glass, piece, tbsp, plate. International: cup, slice, piece, tbsp. |
| NU-13 | P0 | ★ **Saved meals** ("my usual breakfast") logged in one tap. Copy a meal or a whole day from any earlier day. |
| NU-14 | P0 | Quick add with just calories and protein, for when you can't be bothered. |
| NU-15 | P0 | ★ **Origin tag** on every entry: *Home*, *Ordered* or *Packaged*. Restaurant food usually carries far more oil, butter or ghee than the same dish made at home, and estimates for *Ordered* account for that. |
| NU-16 | P0 | Daily view: calories and protein remaining, macro split, and per-meal totals. |
| NU-17 | P0 | ★ **Describe it (AI).** Type something like "2 phulka, 1 katori rajma, cucumber salad, 1 glass chaas" and get an itemised estimate with grams, macros and a confidence level. The prompt follows your diet and region. Edit it, then save. One tap saves it as a meal for next time. Available when AI is on; from M9 the same box runs the local parser with AI off (AI-04). |
| NU-18 | P1 | ★ **Will it fit?** Describe a dish before ordering and see its effect on today's calories and protein, without logging it. One tap logs it if you order. Uses AI when on, and your foods plus the database when off. |
| NU-19 | P1 | ★ **Label scan.** Photograph a packet's nutrition label and a new food is filled in by on-device text recognition. AI corrects it when on. |
| NU-20 | P1 | ★ **Photo (AI).** Snap the plate and get the same review flow as Describe it. |
| NU-21 | P1 | Barcode scan for packaged food (Open Food Facts; coverage varies by country, so label scan is the backup). |
| NU-22 | P1 | Recipe builder for home recipes: ingredients in, macros per portion out. |
| NU-23 | P1 | ★ **Evening protein nudge.** If you're well short of target at a set time, show how much is left and suggest 2–3 of *your own* frequent protein foods that fit your diet, ranked by protein per 100 kcal. |

**Food logging in the app (M3b):**
- **Food tab.** The bottom bar is Today · Food · Train · Body · Settings (Body from M4). Food is the daily view (NU-16), and Today's nutrition card has *Log food*, which opens it. ‹ and › move between days. You can log on past days, not future ones.
- **Daily view (NU-16).** Calories and protein remaining against that day's targets, protein first. Protein follows the ramp (NU-03) and has no target during the baseline week. A macro split shows grams eaten against the protein, carbs and fat targets; fibre is shown without a target. Being over is a plain number, never failure styling. Each meal shows its calories and protein.
- **Meals (NU-10).** Breakfast, Lunch, Snacks and Dinner. Settings → Food → Meals renames, adds, removes and reorders them. A removed meal keeps its past entries.
- **Adding food (J3).** A meal's + opens one screen: a search box, *My meals*, then the results, plus *Quick add* and *New food*. An empty search lists your recent foods; before you've logged anything, it lists the protein-rich basics for your diet. With AI on, *Describe it* sits beside them (M3c, below).
- **Portion.** Tapping a food opens a portion sheet prefilled with what you logged for it last time (amount, unit and origin), otherwise one of the food's own servings, otherwise 100 g. Amounts step by ½ (10 for grams) and can be typed. *Save* logs it, so a repeat food is search, tap, Save.
- **Units (NU-12).** Every food offers grams, its own units (INDB's serving, e.g. "bowl ≈ 258 g", or a custom food's) and the region's household measures: katori 150 g, glass 250 ml and tbsp 15 g for Indian; cup 240 ml and tbsp 15 g for International. Settings → Food → Portion sizes calibrates the household measures to your utensils, and the portion sheet can recalibrate any unit for one food. Roti, piece, slice and plate belong to particular foods, not to every food. Liquids count 1 ml as 1 g.
- **Custom foods (NU-12).** Name, optional brand, values per 100 g or per serving (a unit and its weight; the weight is prefilled for household measures), calories, protein, carbs, fat, optional fibre, and a diet tag that defaults to your diet. Editing one changes future logs only.
- **Quick add (NU-14).** Calories and protein, with optional carbs, fat and a name. Unknown carbs and fat stay unknown, and the macro split says how many calories it couldn't split.
- **Origin (NU-15).** Home, Ordered or Packaged chips on every entry. The default is what you chose last time for that food, else Home (Packaged for a food with a brand). Ordered doesn't change a database food's numbers: the sheet notes that restaurant food usually carries more oil, and the tag feeds the weekly share of ordered food (HM-03) and the AI estimates (M3c).
- **Saved meals (NU-13).** *Save as meal* in a meal's menu names it. In *My meals*, one tap logs every item into the meal you're adding to, with *Undo*. Items link to their foods, so a corrected custom food flows into the meal. Rename or delete it from its menu; to change the items, save a meal over it.
- **Copy (NU-13).** *Copy from another day*, in the day's menu or a meal's, lists the last 14 days with food logged, plus a date picker. Copy the whole day, or one meal into its slot. Copies are added beside what's there, never replacing it, with *Undo*.
- **Editing.** Tap an entry to change its amount, unit, origin or meal, or delete it with *Undo*.
- **Diet filter (NU-11).** Search shows only the foods your diet allows: vegan foods for vegans, plus dairy for lacto-vegetarians, plus egg for eggetarians, plus fish but not meat for pescatarians, and everything for non-vegetarians. A food tagged wrongly can be hidden from its menu; Settings → Food → Hidden foods restores it.
- **Region.** The Indian database (INDB) shows for the Indian region only. The protein-rich basics and your own foods show for both.
- **Fried INDB recipes.** INDB counts all the oil used for deep frying as eaten, so about 120 fried recipes read two to three times too high. They stay searchable with a warning that quick add or a custom food may be closer, and rank below other matches.

**Describe it in the app (M3c, NU-17).** Text only; meal photos (NU-20) stay P1.
- **Where.** *Describe it* is a button on the add-food screen, beside *Quick add* and *New food*. With AI off it isn't there (AI-01) until M9, which keeps it for the local parser (AI-04). It opens its own screen for that meal and day.
- **Describe.** A text box ("2 phulka, 1 katori rajma, cucumber salad, 1 glass chaas") and the origin chips (Home, Ordered, Packaged; Home by default). *Estimate* sends it. While it runs the screen says so, and *Cancel* goes back to the text, unchanged.
- **Not ready (AI-02).** With AI on but no key yet, offline, or this month's cap reached, the button still opens the screen. A line says why ("You're offline", "AI is paused until 1 November: this month's $5.00 cap is reached", or "Add your Claude API key", with *Add key*), and the main action is *Save draft*. Search, *My meals* and *Quick add* stay one tap away.
- **Review.** The estimate lists each item with its amount and grams, calories, protein, carbs and fat, a confidence (high, medium or low) and Claude's assumptions ("medium size, no ghee"). The meal's total sits on top, recomputed from the items. An item whose calories don't match its macros (more than 15% from 4 × protein + 4 × carbs + 9 × fat) is flagged. Items Claude gave no usable weight or amount are left out, with a note naming them.
  - Tap an item to edit its name, amount in grams (its macros scale with it) or its numbers, or to remove it.
  - **A clarifying question** ("Was the dal tempered with ghee?") shows above the items with a one-line answer box. *Re-estimate* sends the meal again with your answer; it's a new request, counted in the spend. If it fails or you cancel it, the earlier estimate comes back, with the reason. Ignoring the question is fine.
- **Save.** *Save* logs every item into the meal, with *Undo*. *Save + My meals* also saves them as a meal under a name you confirm (prefilled from the description), so next time it's one tap (NU-13).
- **Learning loop.** An item you edited before saving also becomes one of your foods (marked *AI-learned*), at your numbers per 100 g and with the unit you used. It shows in search and in later estimates, so a repeat dish converges on your numbers. It's tagged by what it contains (Claude tags each item vegan, vegetarian, egg, fish or meat), so a dish your diet excludes never shows in your search. One of your own foods with the same name (custom or AI-learned) is updated rather than duplicated. A database food is never changed, so your version shows beside it.
- **Failures (AI-02).** If the estimate fails (no connection, a timeout, the service busy or down, a rejected key, no credit left, a declined request, or a reply that can't be read), your description is kept as a draft in that meal and you're back on the add-food screen with the reason, ready to search or quick add.
- **Drafts.** A draft shows in its meal on the Food tab with its text, *Estimate now* (only once AI is ready again) and *Delete*. *Estimate now* opens Describe it with the text and origin and starts the estimate; saving the result removes the draft. Drafts are never sent on their own.

### 7.8 Body metrics

| ID | Pri | Requirement |
|----|-----|-------------|
| BM-01 | P0 | Weigh-ins from your chosen primary source: a home scale (daily, the default), weekly body-composition scans, or a gym scale. Each weigh-in is tagged with its source. A smoothed **trend line** works with daily or weekly data and shows the weekly rate of change against your goal. Readings from other sources are plotted but kept out of the trend, because each scale and setting reads differently. |
| BM-02 | P0 | ★ **Weekly waist measurement.** A cheap, reliable body-composition signal, especially for recomp and lean bulk. |
| BM-03 | P1 | Other tape measurements (chest, arms, thighs, hips). |
| BM-04 | P1 | ★ Progress photos (front, side, back) stored privately on the device, with side-by-side comparison. |

**Body in the app (M4):**
- **Body tab.** The bottom bar is Today · Food · Train · Body · Settings. Body holds the weight chart, waist and scans.
- **Weigh-in (BM-01, J5).** *Log weight*, on Today's weight card or in Body, opens a sheet:
  - weight with −/+ 0.1 kg steppers (tap the value to type it), prefilled from your last weigh-in;
  - source chips: Home scale, Gym scale and Scan, defaulting to your primary source;
  - the day (today or an earlier one) and the time.

  Every weigh-in is kept. Tap one to edit it, or delete it with *Undo*.
- **Trend.**
  - It uses only your primary source (Settings → Body data → Weight trend from), and only that source's first weigh-in of each day, because weight drifts up through the day.
  - Other sources, and later weigh-ins on the same day, are plotted as hollow dots and kept out of the trend.
  - The trend starts at your first weigh-in. Changing the primary source recomputes it from the new source.
- **Chart.** Weigh-ins as dots and the trend as a line, over 4 weeks, 12 weeks or everything.
- **Rate (BM-01).** Once the trend spans a week, Body and Today show the change over the last 7 days, in kg and as a percentage of body weight, next to the goal's pace:
  - fat loss and recomp: their pace, e.g. −0.5% a week;
  - lean bulk: 0.25–0.5% a week;
  - Maintain: steady.

  It's information only, never failure styling.
- **Trend weight is your current weight.** Once your primary source has a weigh-in, Settings → About you shows the trend weight, marked *from your weigh-ins*, instead of a typed one.
  - Targets don't move on their own. The next change that recomputes them uses the trend weight: any Settings change that moves the targets, and the weekly check-in (v1.1).
  - Tapping the weight shows your targets at the trend weight, before and after, so you can update them on purpose.
  - The usual before-and-after preview shows the effect.
- **Waist (BM-02), weekly.** *Log waist* is a sheet:
  - −/+ 0.5 cm, prefilled from last time;
  - a one-line how-to: at the navel, standing relaxed, after breathing out.

  Today's weight card offers it when your last waist is 7 or more days old. Body shows the latest waist, its change over 4 weeks, and the history.

### 7.9 Body-composition scans (optional)

For personas with access to InBody or another bioimpedance (BIA) machine.

| ID | Pri | Requirement |
|----|-----|-------------|
| IB-01 | P0 | Log a scan: weight, skeletal muscle mass, body fat mass, body fat %, visceral fat level. Optional: the machine's BMR, segmental lean mass, ECW/TBW ratio, score. Saving a scan also records it as a weigh-in. |
| IB-02 | P0 | ★ **Consistent conditions.** A fasted morning scan is ideal, but often unrealistic when the machine is at a gym or office, so consistency stands in for it. You pick a usual slot (e.g. Monday 10:00). A scan counts as on-protocol when it's within 90 minutes of your usual time, at least 2 hours after eating, and before any workout that day. Off-protocol scans are flagged. The protocol is shown before each scan. |
| IB-03 | P1 | ★ **Share from the InBody app.** Share the result screen (or a photo of the paper sheet) to Recomp: the scan form opens with the image beside it, filled in from Health Connect where it can (IB-07). The image never leaves the phone. |
| IB-04 | P1 | ★ **Body-composition chart:** muscle mass and fat mass on one chart, alongside trend weight and waist. Changes smaller than measurement noise (roughly 1 kg of muscle or 1% body fat between scans) are labelled "within noise". |
| IB-05 | P1 | ★ **Scan-informed targets.** With an on-protocol scan from the last 60 days, the app estimates BMR from your lean mass, which is more accurate than height-and-weight formulas. It can also suggest a goal weight for a target body-fat %: lean mass ÷ (1 − target %). |
| IB-06 | P1 | ★ A reminder at your usual scan slot (weekly by default). |
| IB-07 | P1 | **Auto-import through Health Connect** of what the InBody app writes there: weight, body fat % and BMR. Lean mass is derived from those. The app then asks only for the missing fields: skeletal muscle mass and visceral fat. |

**Scans in the app (M4):**
- **Who sees it.** Scans are optional and open to every persona. Body shows an empty state until the first one.
- **The form.** *Log scan* opens it.
  - At the top is the protocol (IB-02), e.g. "Usual slot Monday 10:00 · at least 2 h after eating · before any workout that day", with a live verdict.
  - Fields: date and time (now by default, since scans are often typed in later), weight, skeletal muscle mass, body fat mass, visceral fat level, and body fat %. Body fat % is filled in from fat mass ÷ weight and can be edited.
  - *More* adds the machine's BMR, the ECW/TBW ratio, its score, and segmental lean mass (each arm, the trunk, each leg).
- **The protocol questions.**
  - *Last ate:* under 2 h, 2–3 h, 3 h or more, or fasted overnight.
  - *Trained before the scan today:* pre-set from the workout log (any session finished before the scan's time that day), and you can change it.
- **On protocol (IB-02).** A scan is on protocol when it's within 90 minutes of your usual time of day, at least 2 hours after eating, and before any workout.
  - Anything else is flagged *off protocol*, with the reasons, in the form and in the table.
  - The verdict is saved with the scan, and editing the scan recomputes it.
- **Usual slot.** It's a weekday and a time, under Settings → Body data → Usual scan slot.
  - Your first scan sets it from its own day and time, and says so.
  - The weekday is for the scan reminder (IB-06, P1). The check uses only the time of day.
- **Linked weigh-in.** Saving a scan also records its weight as a *Scan* weigh-in, which feeds the trend when scans are your primary source. Editing or deleting the scan updates or removes that weigh-in.
- **Scan table (HM-02).** Newest first, with date, weight, muscle, fat mass, body fat % and visceral fat. Off-protocol rows are marked. Tap a row to edit or delete it.
  - Noise labels and the body-composition chart are IB-04 (P1).

### 7.10 Activity and recovery

| ID | Pri | Requirement |
|----|-----|-------------|
| AR-01 | P1 | ★ **Daily steps** read through Health Connect, from whichever app writes them (Google Fit, Samsung Health, a watch app…). A step goal (e.g. 8,000). |
| AR-02 | P1 | ★ **Sleep hours**, entered in the morning check-in or read from Health Connect when a tracker provides them. |
| AR-04 | P1 | Other apps' workouts (walks, cycling, yoga…) read through Health Connect: shown on Today and in the weekly review, and counted toward a matching extra session. Their calories are never added to the food budget. |
| AR-03 | P2 | ★ Pre-workout readiness check (sleep, energy, soreness, each 1–5), shown next to performance to explain off days. |

### 7.11 Today screen, progress and weekly review

| ID | Pri | Requirement |
|----|-----|-------------|
| HM-01 | P0 | **Today screen:** next workout (or rest day, or scheduled extra session), calories and protein remaining, trend weight, and weekly plan progress. |
| HM-02 | P0 | Exercise history: every past session for an exercise, newest first. Also the scan history as a table. |
| HM-03 | P1 | ★ **Weekly review**, built around the goal's success criteria (§4.2): sessions done vs planned, average calories vs target, protein days hit, share of calories from ordered and packaged food, change in trend weight, waist and scan results, PRs, stalled lifts, average steps and sleep. |
| HM-04 | P1 | Consistency calendar for workouts and logged days. No punishing streaks. |
| HM-05 | P1 | ★ Home-screen widget: calories and protein left, plus the next workout. |
| HM-06 | P1 | ★ **Weekly programme review with AI.** From the weekly review, send a summary you've checked (body trends only if you tick them) and get up to 5 concrete changes to your programme, each with a reason. Each is applied only when you accept it (§12.7). |

**Today in the app (M4, HM-01).** From top to bottom:
1. **The goal's main number,** protein, and the 7-day average line (§4.8).
2. **The next workout, or a rest day.**
   - Today says **Rest day** once you've finished a strength workout today, or once this week's strength days are done. It also says which ("Strength done for today", or "3 of 3 strength days done this week").
   - The next routine stays below it, with a smaller *Start anyway*.
3. **Extra sessions** scheduled today.
4. **The weight card:** trend weight, the week's rate against the goal's pace, *Log weight*, and *Log waist* when it's due.
   - Before the first weigh-in, it says the trend starts with your first weigh-in.
   - Tapping the card opens Body.
5. **Weekly plan progress** and the last workout.

### 7.12 AI features and the no-AI mode

| ID | Pri | Requirement |
|----|-----|-------------|
| AI-01 | P0 | **AI is a setting: Off or On.** With AI off, every feature works and AI buttons are hidden (from M9, *Describe it* stays and runs the local parser, AI-04). Food logging then runs on the database, saved meals, label scan and quick add. |
| AI-02 | P0 | **AI on but unreachable** (offline, timeout, out of credit, service error): the app never blocks. It falls back to search, saved meals and quick add, and keeps your description as a draft to estimate later. |
| AI-03 | P0 | This month's AI spend shown in Settings, with an optional monthly cap. Once the cap is reached, AI pauses until the next month. |
| AI-04 | P1 | ★ **Offline Describe it.** A local parser reads "2 roti, 1 katori dal" by matching quantities, units and names against your foods and the bundled database. Same text box with or without AI. AI handles what the parser can't. |
| AI-05 | P1 | **Choose the AI provider:** Anthropic, OpenAI, Google Gemini or any OpenAI-compatible endpoint (e.g. OpenRouter or a local server), each with its own key, models and prices. Every AI feature, the fallbacks (AI-02) and the monthly cap (AI-03) work the same with each (§12.3). |

**Where AI is used, and where it isn't**

| Use | Priority | Without AI |
|-----|----------|------------|
| Describe a meal → itemised macros | P0 | Search, saved meals, quick add. Local parser at P1. |
| Will it fit? | P1 | Local parser plus the database. |
| Meal photo → itemised macros | P1 | Not available. Use the text flows. |
| Nutrition label photo → new food | P1 | On-device text recognition, then manual correction. |
| InBody screenshot or sheet photo → form | Not used: body data stays on the phone | The image sits beside the form; Health Connect import (IB-07) fills what it can. |
| Weekly programme review (HM-06) | P1 | The weekly review shows the numbers; you change the plan yourself. |
| **Set-by-set progression** | **Never** | **Plain rules, always offline.** The programme review only suggests plan changes, which you accept one by one. |

AI uses an API key that each person brings, from the provider they choose (AI-05; Anthropic only until M7). For Anthropic, a claude.ai subscription login can't be used by apps, but Max plans include monthly API credits that can fund the key. See TRD §6.2.

**AI in the app (M3c).** Settings → AI:
- **AI:** Off or On (AI-01). Off by default.
- **API key:** pasted once, with the Console setup steps beside it. It's stored encrypted on the phone and never shown again, logged, exported or backed up: the row just says *Saved*, with *Replace* and *Remove*.
- **Model:** Claude Opus 5.5 (the default), Claude Sonnet 5.5 or Claude Haiku 5.5, each with its rough cost per estimate. M7 adds other providers (§12.3).
- **This month (AI-03):** what this month's requests cost, and how many there were.
- **Monthly cap (AI-03):** none by default. Once this month's spend reaches it, AI pauses until the 1st of next month; Describe it then saves drafts. The Console workspace limit stays the hard stop.
- **Privacy notice:** the first estimate shows, once, what is sent to Anthropic (the description and origin, your diet and food region, your portion sizes and your most-used own foods) and what never is (your name, body data, targets or log). *Continue* sends it. From M7 the notice names the provider in use, and each new kind of request gets its own (§12.3).


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

**Export and import in the app (M4, DA-01):**
- **Export all data** (Settings → Data) opens the system's save dialog with `recomp-backup-YYYY-MM-DD.zip`, so the file can go to Google Drive or any folder.
  - It holds every table and every setting. It never holds the AI key.
  - A message says what was saved.
- **Import a backup** opens a file.
  - The file is checked first: it must be a Recomp backup, undamaged, and not from a newer version.
  - A confirmation then shows the backup's date and what it holds (workouts, food entries, weigh-ins, scans), and says everything on this phone will be replaced.
  - Import is refused while a workout is in progress.
- **Safety copy.** Just before replacing anything, the app saves the current data inside itself. There's one copy, and the next import replaces it. Settings → Data then offers **Restore the data from before the import**, with its date.
- **Older backups and merging.** A backup from an older version is upgraded as it's imported. Import never merges: it always replaces.
- **Interrupted imports.** If Recomp closes partway through an import, the next launch either finishes it (the data was already in place, so the settings follow) or says it didn't happen and nothing changed.
- **Android's own backup** (Auto Backup and device transfer) includes only the database and the settings, never the AI key.

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
| **v1.1** (P1) | Every P1 requirement, in milestones M5 to M20 (TRD §10, designs in §12): AI provider choice (Anthropic, OpenAI, Gemini or OpenAI-compatible); the weekly check-in with an adaptive target, its per-goal flags, and the weekly review with a consistency calendar and the body-composition chart; the weekly programme review with AI; supersets, warm-ups, assisted exercises, RIR-aware jumps and substitutes; the offline describe parser, Will it fit? and a snack allowance; reminders, goal-specific nudges with the evening protein nudge, and a widget; editing past workouts, charts and muscle-group volume; steps, sleep, other apps' workouts and InBody import through Health Connect; InBody share and scan-informed targets; auto-backup, CSV export and Health Connect write-back; tape measurements, progress photos, focus areas and mobility markers; label scan, barcode and AI meal photos; recipes; goal switch, Goal reached card, maintenance breaks, AI goal interpretation, the pregnancy toggle and presets as data files; lb and in. | The weekly review answers "is my goal on track?" without opening anything else. |
| **v2** (P2) | Persona export and import, readiness check, plate calculator, workout-history import, periodised blocks for advanced lifters, Wear OS companion. | — |

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
2. ~~Should lb/in display ship in the MVP or in v1.1?~~ *Settled in M3:* v1.1. The MVP is metric only; the persona already stores the choice.
3. Is "Pescatarian" enough, or does the diet filter also need custom exclusions (e.g. Jain: no root vegetables)?
4. Should a cut that loses faster than planned (say, beyond 1.5 × the pace for 2 weeks) get a check-in flag that proposes more food? §4.8 has no such flag, and M5 doesn't add one.

## 12. v1.1 designs

*Settled with you on 2026-10-18, with the v1.1 plan (TRD §10). Nothing here is built yet; each block is its milestone's spec. ◆ marks a choice that had a real alternative, with what was decided.*

These rules hold for every block:
- New features are opt-in or quiet by default, and work with AI off (AI-01) unless §7.12's table says they need AI.
- Numbers are judged as trends and weekly averages, never as one bad day (§5, GL-10).
- Anything that varies between people is a persona or Settings value (PS-03).
- Targets never change on their own. A new estimate shows as a before-and-after preview, and you save it (M4's rule for the trend weight). Ending a maintenance break you accepted is the one exception (§12.15).

### 12.1 M5: Weekly check-in and adaptive target (NU-05, GL-08 flags, J7)

- **When.** A week runs Monday to Sunday, like the weekly plan. Its check-in opens on the Monday after. Today shows a *Weekly check-in* card until you've opened it or the next week's is ready.
- **The screen** (*Week of 13 Oct*), top to bottom:
  1. **The goal's headline** (§4.8), e.g. waist and trend weight for a recomp, the loss rate for a cut. Below it, one verdict: *On track*, the flag that fired ("Losing slower than planned: −0.2% a week against −0.75%"), or *Not enough data yet*.
  2. **7-day averages:** calories and protein against target, and days logged.
  3. **Trend weight:** the change and the rate against the pace, and the waist change.
  4. **Your maintenance estimate:** "About 2,480 kcal a day, from 21 days of food and weigh-ins (the formula said 2,610)", with what this window alone measured as a detail. Until there's enough data, it says what's missing, e.g. "Needs food on 17 of the last 21 days: 12 so far" (TRD §5.4).
  5. **The proposal,** only when the goal's flag fires: "Lower your target to 1,960 kcal (now 2,090)", with *Accept*, *Adjust* and *Keep*.
     - *Adjust* is a −/+ 50 kcal stepper, held to the caps (§4.2) and the floor (§4.9).
     - *Accept* and *Adjust* save a new target version from today (reason: check-in).
     - A calorie override you set (PR-02) stays, unless you choose *Use the new target*, which resets it.
     - The check-in applies the same guardrails as Settings (§4.9) and shows their note, e.g. a cut whose trend falls below BMI 18.5 is judged and proposed as Maintain.
- **Settings follows the estimate.** Once a check-in has a maintenance estimate, Settings works out targets from it instead of the formula, and Settings → Targets says so ("from your check-in of 13 Oct"). An *Adjust* isn't carried into a later Settings change. Settings also has a *Weekly check-in* row that reopens the latest one.
  - Settings shows what a change there would save. When that differs from today's target, for example while a proposal waits, it says so under Calories: "Today's target is 2,000 kcal until you answer the check-in or change something here".
- **Flags** (§4.8), judged on the trend, never on single weigh-ins:

  | Goal | Fires when |
  |------|-----------|
  | Fat loss | You lost less than half the pace (or gained) in each of the last 2 weeks |
  | Recomp | Over 4 weeks, trend weight changed by less than 0.5% and waist by less than 1 cm. It needs a waist reading within a week of each end; without them it asks you to log your waist |
  | Maintain | Trend weight moved more than 1% over 4 weeks |
  | Lean bulk | The weekly rate is under 0.1% or over 0.5% for 2 weeks in a row |

  - Every proposal is the targets recomputed with the new maintenance estimate at your current pace.
  - A proposal that would move the target by less than 50 kcal isn't shown.
  - The estimate updates every week whether or not a flag fires, so the next proposal starts from it.
  - A week without a rate (no weigh-in that week) breaks "2 weeks in a row". The exact rules are in TRD §5.4.
- **Incomplete days.** The Food tab's day menu has *Mark day as incomplete* (and back). That day shows a small note.
  - ◆ **Decided:** incomplete days are left out of both the maintenance estimate and the 7-day average on Today.
- **A missed check-in isn't lost.** Its estimate still counts, and an unanswered proposal lapses when the next week's is ready.

### 12.2 M6: In the gym (RT-05, WL-11, WL-09 assisted, PO-08, EX-04)

- **Supersets (RT-05).** In a routine, or mid-workout from a card's menu, *Superset with next* joins two or three consecutive exercises. *Split superset* undoes it.
  - The joined cards show a bracket and "Superset".
  - Ticking a set selects the same set of the next exercise in the group, with no rest. If you started the round on a later exercise (its machine was free first), the earlier one's set of that round comes next, also with no rest. After the last exercise of a round, rest starts with that exercise's rest time.
  - Each exercise keeps its own target and progression.
  - Saving the workout back to the routine keeps the groups.
- **Warm-ups (WL-11),** for the first compound lift that has a target load:
  - Its card shows "Warm-up: 20 × 8 · 30 × 5 · 40 × 3", about 40%, 60% and 80% of today's target, rounded down to the exercise's step. Sets under two steps are left out, so light lifts get fewer or none.
  - On a barbell, a warm-up under the empty bar (20 kg) is raised to the bar, since you can't load less. Two that land on the same load become one, and none is at today's target: a 40 kg squat warms up at 20 × 8 · 22.5 × 5 · 30 × 3.
  - It isn't shown when the exercise already has warm-up sets, e.g. the ones M1 recreates from last session.
  - ◆ **Decided:** the line has *Add*, which adds them as warm-up sets for you to tick. Rows added automatically would trigger the finish confirmation when left unticked.
- **Assisted exercises (WL-09).** Assisted pull-ups and dips log the assistance: the stepper reads "Assist 25 kg". The library gains an assisted pull-up and an assisted dip (machine), and an exercise's settings can set it to *Assisted*.
  - Progress means less assistance: the target line reads "Today: assist 20 × 8 · less assistance". With no assistance left it reads "BW", and the next step is added load or a harder variation.
  - PRs and the e1RM use your trend weight minus the assistance, with every session compared at today's weight. So progress means less assistance or more reps, and a cut doesn't look like a stall. Before your first weigh-in, less assistance counts as better and the e1RM is hidden.
- **RIR-aware jumps (PO-08).** Where the experience policy allows it (Beginner), every working set at the top of the range with 3+ reps in reserve earns a double step. The target line says why: "Today: 55 × 8 · weight up (2 steps: every set had 3+ reps left)".
  - Only when one step is at most 5% of the load, so dumbbells don't leap.
- **Substitutes (EX-04).** *Swap* opens the picker with *Same movement* first: exercises with the same movement pattern and primary muscle, on other equipment. Everything else follows.

### 12.3 M7: AI providers (AI-05)

- **Settings → AI → Provider:** Anthropic (the default), OpenAI, Google Gemini, or *OpenAI-compatible*, which takes a base URL: OpenRouter, or a server on your own network such as Ollama.
- **One key per provider,** pasted once and stored encrypted, as today (§7.12). Switching provider keeps the other keys, so you can switch back. One provider is in use at a time.
- **Models.** Each provider lists a few models with their rough cost per estimate, like today's picker. For *OpenAI-compatible* you type the model's name and its prices per million tokens. Leave the prices at 0 for a free local server; the monthly cap then can't count it, and Settings says so.
- **Every AI feature works with every provider,** with the same review screens, drafts and fallbacks (AI-02), and one monthly cap across all of them (AI-03). A model that can't read images says so when you send a photo.
- **The privacy notice names the provider:** "The description goes to OpenAI". Changing provider shows it again, once.
- Anthropic's refusal fallback (TRD §6.2) is Anthropic's alone. On other providers a refusal keeps a draft with the reason, as it does today.

### 12.4 M8: Weekly review (HM-03, HM-04, IB-04)

- **The review extends M5's check-in screen.** There's one per week, with ‹ and › between weeks, and the current week shows what's in so far. Today's weekly-plan card opens it too.
- **Sections,** after the headline, verdict and proposal (§12.1):
  - **Food:** average calories and protein against target; protein days hit (95% or more of that day's target; the ramp's baseline week, which has no target, is left out); and the share of calories from *Ordered* and *Packaged* food.
  - **Training:** sessions done against planned, for strength and each extra session; PRs; and lifts showing a stall card.
  - **Body:** the trend weight and waist changes; the week's scan against the previous one, with noise labels; average steps and sleep, once M13 tracks them.
- **The finish line:** "At this rate: around mid-March (goal 78 kg)", or "Moving away from your goal", recomputed each week from the trend (§4.2). <!-- pii-ok: illustrative -->
- **With AI on,** the review ends with the weekly programme review (§12.7, M11).
- **Consistency calendar (HM-04):** a month grid in the review.
  - A day gets a filled dot for a strength workout, a ring for an extra session and a tint when food was logged.
  - There are no streak counts, and nothing marks a missed day.
- **Body-composition chart (IB-04),** on the Body tab above the scan table: three small charts on one time axis, over 12 weeks or everything:
  1. muscle mass and fat mass from scans;
  2. trend weight;
  3. waist.

  On-protocol scans are filled dots. Off-protocol scans are hollow and left out of the lines. Each change between on-protocol scans is labelled, e.g. "+0.4 kg muscle · within noise" (under 1 kg of muscle or 1% body fat, TRD §5.6). From three on-protocol scans, the slope per 4 weeks shows.

### 12.5 M9: Faster food logging (AI-04, NU-18, NU-06)

- **Describe it works with AI off (AI-04).** The button is always on the add-food screen.
  - The local parser reads text like "2 roti, 1 katori dal makhani and salad". It matches quantities, units (yours, plus synonyms like chapati → roti and bowl → katori) and names against your foods and the database.
  - The review is M3c's: the matched items with their amounts, and "Couldn't find: salad" with *Search* and *Quick add*.
  - Each item says where its numbers came from: *Your food*, *Database* or *AI*.
  - ◆ **Decided, with AI on:** the parser goes first, and the AI estimates only what it couldn't match, in the same review. An *Ordered* meal goes straight to the AI, because restaurant oil is what the database misses. Repeat dishes become instant and free. The AI gets the whole description, with the matched items marked as already counted, so it keeps the meal's context without counting anything twice.
- **Will it fit? (NU-18, J4).** A button in the Food tab's top bar and on the add-food screen.
  - You describe the dish; its origin is *Ordered* by default.
  - The result shows today's main number before and after, without logging anything: "Protein left 62 g → 30 g · calories 380 left → 40 over".
  - *Log it* adds it to the meal for the time of day, which you can change. Each meal slot has a usual time range, editable with the slots in Settings → Food → Meals; a time belongs to the last slot that starts before it.
  - With AI on it's one request, counted in the spend. With AI off, the parser and the database answer.
- **Snack allowance (NU-06).** Settings → Food → Snack allowance: off by default, or 100–300 kcal in steps of 50, counted against one meal (Snacks by default).
  - It's part of the calorie target, not extra. The day view says "includes a 150 kcal snack allowance", and the snack meal shows "60 of 150 kcal".
  - ◆ **Decided:** build it. (Moving NU-06 to P2 was the alternative.)

### 12.6 M10: Training history (WL-10, PO-09, PO-10)

- **History** on the Train tab: every workout and extra session, newest first.
- **Editing a past workout (WL-10).** It opens like the summary, with editable rows:
  - weight, reps, RIR and set type; add or delete sets; remove an exercise;
  - the date, start and end times, and notes;
  - *Delete workout*, which asks first.

  Then:
  - PRs, stall cards and next time's targets are recomputed from the edited history, and the screen says so. Targets frozen on past sessions stay as they were shown.
  - Deleting your last strength workout moves the rotation back.
  - An active workout is still edited in the workout screen.
- **In a deficit,** the summary and the stall card add "Holding strength in a deficit is a win" (§4.8).
- **Charts (PO-09).** Exercise history gets a chart: the best e1RM per session as a line and the top set as dots, over 12 weeks or everything. Bodyweight exercises chart reps, and assisted ones the assistance (lower is better).
- **Weekly sets per muscle (PO-10).** A Train tab card for this week: each muscle's working sets (1 for its primary muscle, ½ for each secondary) against a band.
  - The band is 10–20 by default, editable in Settings → Training.
  - It's information only: no warning colours and no notification.

### 12.7 M11: Weekly programme review with AI (HM-06)

- **Offered every week.** With AI on, each week's review (§12.4) ends with *Ask AI to review your programme*, and Today's check-in card mentions it. Ignoring it is fine: nothing is sent until you tap *Send*.
- **You choose what's sent.** A checklist, then the full text to read before *Send*:
  - *Goal and targets* (on): goal, pace and the kind of finish line, with targets in relative terms: the deficit or surplus as a % of maintenance, and protein in g/kg. Absolute calories and grams go only when *Body trends* is ticked, because they reveal your weight.
  - *Food* (on): the week's averages, protein days hit, and the share from ordered and packaged food.
  - *Training* (on): experience, split and strength days; each routine's exercises with sets, rep ranges and current loads; sessions done against planned; PRs; stalled lifts; weekly sets per muscle.
  - *Body trends* (off by default): the trend weight's change and rate, the waist change, and scan changes with their noise labels. Never single weigh-ins, scan values, photos or screenshots.
  - *A note from you* (optional), e.g. "left knee hurts on hack squats" or "only 2 days next week".
  - Never sent: your name, sex, age, height, or the food log itself.
- **What comes back:** a short summary and up to 5 suggestions, each with its reason. A suggestion is one of:
  - a calorie or protein target;
  - swapping, adding or removing an exercise in a routine, from the library only;
  - the sets or rep range for an exercise;
  - strength days per week;
  - a deload, for one exercise or the whole week, taking effect at the next workout;
  - advice the app can't apply, shown as text.
- **You accept each one, or not.** *Apply* goes through the usual path:
  - a target change shows the before-and-after and saves a target version;
  - a routine change applies with *Undo*;
  - a deload is marked on the exercise (or every exercise, for the whole week), and the next workout starts it the way the stall card's *Deload* does.

  A suggestion outside the guardrails (§4.9), or naming an exercise that isn't in the library, is shown as advice only.
- **Scheduled deloads (§4.3),** with or without AI: for intermediate and advanced lifters, a Train card suggests a deload week every 6–8 or 4–6 weeks, as the experience policy says. Accepting marks the next workouts to deload, as above. It's offered, never applied on its own. Beginners deload only on a stall.
- **The progression engine stays plain rules.** AI never sets a set's weight or reps, and nothing changes without your tap.
- **History:** each past review keeps what was sent, what came back and what you applied.
- With AI off, the weekly review shows the numbers only.

### 12.8 M12: Nudges, reminders and the widget (RM-01, NU-23, GL-08 nudges, PO-11, IB-06, HM-05)

- **Settings → Reminders.** Each is off until you turn it on. The first one you turn on asks for notification permission.

  | Reminder | Default time | Skipped when |
  |----------|--------------|--------------|
  | Morning weigh-in | 07:30 | you've weighed in today |
  | Lunch not logged | 14:30 | the meal it watches has an entry (Lunch by default; you can pick another slot) |
  | Evening nudge (below) | 20:30 | there's nothing to nudge about |
  | Workout day, on weekdays you pick | 08:00 | a strength workout is done today |
  | Weekly check-in | Monday 09:00 | you've opened it |
  | Scan slot | 3 h before your usual slot | you've logged a scan today |

- **At most 2 a day.**
  - ◆ **Decided:** in time order. The first two that are still needed that day are sent, and Settings warns when more than two could be due on one day.
- **The evening nudge follows the goal** (GL-08). The nudge list is part of the goal policy:
  - Fat loss: protein left, and steps left once M13 tracks them.
  - Recomp: protein left.
  - Maintain: none by default.
  - Lean bulk: calories still to eat ("You're 600 kcal short tonight").
- **The protein nudge (NU-23)** fires when at least 25% of the day's protein target is left at the nudge time.
  - It suggests 2–3 of *your* most-logged foods that your diet allows, ranked by protein per 100 kcal. Each comes with a portion that fits what's left: "Hung curd · 200 g · 22 g protein · 210 kcal".
  - Tapping one logs it into the meal for the time of day (§12.5), with *Undo*.
  - The same card shows on Today after the nudge time, whether or not the notification is on.
- **Workout day (PO-11):** "Pull is next · 3 lifts are due for a weight increase". The rotation stays calendar-free (RT-02); the weekdays are only for the reminder.
- **Scan slot (IB-06):** the protocol, e.g. "Scan at 10:00 · eat by 08:00 · before your workout".
- **Widget (HM-05):**
  - It shows the goal's main number with protein (or calories), and "Pull next" or "Rest day".
  - It's 4×1, and resizes to 2×2.
  - Tapping it opens Today; *Log food* opens the Food tab.
  - It updates when you log food, finish a workout or change targets, and at the day boundary.

### 12.9 M13: Health Connect: steps, sleep, other workouts and InBody import (AR-01, AR-02, AR-04, IB-07)

- **Fetched automatically:** at launch and once a day in the background, for each feature you turn on. The background part needs Health Connect's background-read permission; without it, Recomp syncs whenever you open it, and Settings says so.
- **Settings → Health Connect** lists what can be read (steps, sleep, other apps' workouts, InBody's body data) and what each needs, e.g. "Turn on Health Connect access in Google Fit". Each is off until you turn it on, which asks for that permission only.
- **Steps (AR-01).** Settings → Body data → Steps: off, or on with a goal (8,000 by default, from 2,000 to 20,000).
  - Today shows "6,240 of 8,000 steps" under the weight card, and Body shows the 7-day average.
  - Health Connect counts the phone's own steps even without Google Fit, on an up-to-date phone (TRD §6.3).
  - ◆ **Decided:** steps count by calendar day, so they match Google Fit.
- **Sleep (AR-02),** behind Settings → Body data → *Track sleep*, off by default (on when you turn on Health Connect sleep):
  - The weigh-in sheet gets *Slept* chips: 5 or less, 6, 7, 8 and 9+ hours, or type it to the half hour. It's for the night that ended that morning.
  - Today's weight card asks until it's entered.
  - If a tracker writes sleep to Health Connect, that's prefilled; several apps' overlapping nights are merged, not added up. What you type wins.
- **Other apps' workouts (AR-04).** Walks, runs, cycling, yoga and other sessions that another app writes to Health Connect (Google Fit, a watch) show on Today and in the weekly review, e.g. "Walk · 35 min · from Google Fit".
  - They count toward an extra session of the same kind in your weekly plan (the one scheduled that day first), and show in History, read-only. With no extra of that kind they count toward nothing.
  - Strength workouts from other apps aren't imported, and nor is anything that overlaps a workout you logged in Recomp, so nothing counts twice.
  - Their calories are never added to the food budget: the activity level and the maintenance estimate already cover activity.
- **InBody import (IB-07).** Each new InBody measurement in Health Connect (weight, body fat % and BMR) becomes a draft.
  - Body shows "New InBody scan, Mon 10:05: add muscle and visceral fat". It opens the scan form filled in, with the protocol questions as usual.
  - *Dismiss* drops the draft.
  - A scan you already typed within 10 minutes of it is matched instead of duplicated.

### 12.10 M14: Recipes (NU-22)

- *New food* → *Recipe*: a name, ingredients picked from search with their amounts, and the cooked weight (weigh the pot; optional) or the number of servings.
- It shows values per 100 g and per serving, and becomes one of your foods, so it's in search, the parser and saved meals. It gets a *serving* unit (cooked weight ÷ servings) next to the usual measures.
- Its diet tag is its strictest ingredient's. Editing a recipe changes future logs only.

### 12.11 M15: InBody share and scan-based targets (IB-03, IB-05)

- **Share to Recomp (IB-03).** Sharing an image to Recomp asks "What is this?": *InBody result*, and from M18 *Nutrition label* and *Meal photo*.
  - The scan form opens with the image pinned above it. If Health Connect has an InBody measurement from the same time (M13), its weight, body fat % and BMR are filled in, and you type the rest.
  - The image is kept with the scan, on the phone only, and never sent to AI. Deleting the scan deletes it.
  - ◆ **Decided:** body data stays on the phone, so AI doesn't read InBody images. (The alternative was letting AI read them, with their own notice.) The weekly programme review (§12.7) sends body trends only when you tick them.
- **Scan-based targets (IB-05),** with an on-protocol scan from the last 60 days:
  - BMR uses your lean mass (Katch-McArdle), which is more accurate than height and weight.
  - Targets don't change on their own. After a scan that moves BMR by 20 kcal or more, Body offers *Preview targets with your scan*, before and after.
  - The **body-fat finish line** becomes available. Its goal weight is lean mass ÷ (1 − target %), and the suggested goal weight uses the scan (§4.2).
  - Once the weekly check-in has a maintenance estimate (M5), that sets the calories. The scan's BMR still sets the floor.

### 12.12 M16: Data out (DA-02, DA-03, DA-04)

- **Automatic backup (DA-02).** Settings → Data → *Automatic backup*: choose where, once. A folder on the phone keeps the last 8 backups. Google Drive is offered as one file that's overwritten each week, because Drive likely can't be picked as a folder; M16 checks that on the phone first.
  - Every Sunday night, when the battery isn't low, the app writes `recomp-backup-YYYY-MM-DD.zip` to the folder (keeping the last 8), or overwrites the Drive file.
  - Settings shows the last backup's date, or why it failed.
- **CSV export (DA-03).** *Export as CSV* saves one zip with `workouts.csv` (one row per set), `food.csv` (one row per entry), `weigh-ins.csv` and `scans.csv`, for spreadsheets. It can't be imported back; the full backup does that.
- **Write to Health Connect (DA-04).** Settings → Health Connect → *Share with other apps*, off by default.
  - It writes your workouts, one nutrition record per meal, and the weigh-ins you log. Scan weights aren't written, since the InBody app already writes them, and nor are workouts read from other apps (AR-04).
  - Turning it on writes the last 30 days. After that, your edits and deletes follow.
  - Apps you let read it, such as Google Fit, may upload it to their own cloud; the switch says so.

### 12.13 M17: Body extras (BM-03, BM-04, YG-03, YG-04)

- **Tape measurements (BM-03).** Settings → Body data → Tape measurements: choose any of chest, arm, thigh and hip. Waist is always on.
  - *Log waist* becomes *Log measurements*: waist first, then the others once they're 4 or more weeks old.
  - Each site has a one-line how-to, e.g. "Right arm, relaxed, at the widest point".
  - Body shows each one's latest value and its change.
- **Progress photos (BM-04).** Body → Photos: front, side and back.
  - They go straight into Recomp's private storage and never reach the gallery.
  - *Compare* puts two dates side by side for one pose.
  - Body suggests new photos every 4 weeks, with no notification.
  - Photos never leave the phone unless you include them in a backup: they aren't sent to AI or included in Android's own backup.
  - An import never deletes your current photos until it has finished, and *Restore the data from before the import* brings them back.
  - ◆ **Decided, backups:** the full export and the automatic backup get *Include photos*, off by default.
  - ◆ **Decided, camera:** the phone's camera app takes them. An in-app camera with an alignment overlay was the alternative.
- **Focus areas (YG-03).** The extra-session form gets chips (hips, hamstrings, back, shoulders, core, full body), prefilled from last time.
- **Mobility markers (YG-04),** monthly, on a Body card when Settings → Training → *Mobility markers* is on. It's on by default when you have a yoga or mobility session.
  - Sit-and-reach in cm past your toes (negative when you're short of them).
  - Deep-squat hold in seconds, with a stopwatch.
  - Toe touch, yes or no.

  Each has its how-to and its history.

### 12.14 M18: Camera food (NU-19, NU-21, NU-20)

- **Barcode (NU-21).** *Scan barcode* on the add-food screen opens the camera.
  - A food you already have opens its portion sheet.
  - Otherwise Open Food Facts is asked. The product shows with its values per 100 g, and *Save* makes it one of your foods.
  - Not found: "Not on Open Food Facts: scan the label instead".
  - In the International region, search also asks Open Food Facts when your own results are few, shown as a separate section.
  - Only the barcode or the search words are sent.
- **Label scan (NU-19).** *Scan label* in New food: photograph the nutrition table, and on-device text recognition fills in the values per 100 g, starting with the FSSAI layout.
  - Each filled field is highlighted for you to check.
  - With AI on, the photo and the recognised text also go to the AI, for a cleaner fill.
- **Meal photos (NU-20).** Describe it gets a camera and gallery button.
  - A photo, with optional words ("2 rotis, dal"), gets the same review as text.
  - The photo is kept only while it's a draft, and deleted once the meal is saved.
  - The privacy notice adds photos.
- ◆ **Decided, privacy:** keep TRD §7's promise. Google's ML Kit also sends usage metrics to Google, so it isn't used: barcodes are read with ZXing, and labels with an open-source on-device text recogniser (e.g. Tesseract), chosen after a short trial on real labels.

### 12.15 M19: Goals (PS-04, PS-05, GL-03, GL-06, GL-09)

- **Goal switch (PS-04).** The goal flow's before-and-after step gains *What to expect*, one line for the change, e.g. from a cut to a lean bulk: "The scale will jump 1–2 kg in the first two weeks as water and glycogen come back; judge from week 3".
  - Comparisons ("since you started", the review's weeks and the charts' goal markers) start from the switch.
  - History stays.
- **Goal reached (GL-06).** A card on Today and in the check-in, once the trend crosses the finish line or the date passes: "You reached 78 kg on the trend". It offers the next phase from §4.7:
  - *Start 2 weeks at maintenance*;
  - *Choose a new goal*;
  - *Keep going*, which clears the finish line; you can set a new one.
- **Maintenance break (GL-06).** After 12 weeks in a deficit without reaching the finish line, the check-in suggests 1 or 2 weeks at maintenance.
  - Accepting sets maintenance targets from that day. Today says "Maintenance break · back to your recomp on Mon 3 Nov", with *End early*.
  - The goal resumes on its own on that date, and the 12 weeks start counting again.
  - *Not now* asks again in 4 weeks.
  - During a break, the check-in still updates the maintenance estimate, but shows no flag or proposal.
- **AI goal interpretation (GL-03).** With AI on, *Describe your goal* sends only the text you typed.
  - The AI returns an intent and, at most, one follow-up question with an answer box.
  - The same rules as offline then pick the goal (§4.6).
  - Any failure falls back to the three questions.
- **Pregnancy and breastfeeding (GL-09).** A switch in About you, in onboarding and Settings.
  - When it's on, fat loss and recomp are greyed out with the reason. An active deficit moves to Maintain with the usual preview, and a note suggests asking a doctor or midwife about calories and protein.
  - It's body data: kept with the profile, never sent to AI and never shared in a persona (PS-06).
  - ◆ **Decided:** shown to everyone.
- **Presets as data files (PS-05).** The five presets move to `assets/presets/*.json`. Nothing changes on screen.

### 12.16 M20: lb and in (PR-05)

- Settings → Body data → Units switches display and entry everywhere:
  - body weight in lb;
  - lifts in lb, with lb steps (2.5, 5 or 10 lb);
  - lengths in inches, and height in feet and inches.

  Food stays in grams and the region's household measures.
- Switching asks whether to move your exercises' weight steps to the nearest lb plates (2.5 kg → 5 lb). Logged history stays exact, because storage is always metric.
