# Recomp

An Android app for people who lift and want their nutrition to match their goal:
- progressive-overload coaching for strength training;
- calorie and protein targets for fat loss, recomposition, maintenance or a lean bulk;
- optional body-composition tracking.

Everything that varies between people lives in a configurable **persona**: goal, experience, diet, food region, training split and extra sessions. Presets get you started, and every value is editable.

One user per install, offline-first, no backend, no accounts. AI meal logging is optional and uses your own Claude API key.

> **Status:** early development. Done so far:
> - M0: project setup and feasibility checks on a real phone.
> - M1: training core: exercise library, split templates, rotation, the active workout with a lock-screen rest timer, summary and extra-session logging.
> - M2: progression: targets for every exercise, live feedback, PRs, stall detection and exercise history.
> - M3a: personas and targets: presets and onboarding, the goal flow with guardrails, Settings, versioned calorie and macro targets with a protein ramp, and a goal-driven Today screen.
> - M3b: food logging: a Food tab with meals, search over the Indian Nutrient Databank and protein-rich basics filtered to your diet, custom foods with portion units you can calibrate, saved meals, copy, quick add and origin tags.
> - M3c: AI meal logging: *Describe it* with your own API key (encrypted on the phone), drafts to estimate later when AI is unreachable, and a monthly spend cap.
> - M4: body, Today and data: weigh-ins with a smoothed trend from your chosen scale, a weekly waist, body-composition scans with a consistent-conditions check, a Body tab, a Today screen that knows rest days, and export and import of all your data (Settings → Data).
> - M5: a weekly check-in that estimates your real maintenance calories from your food and weight trend, flags when your goal is off pace, and proposes a new target you accept, adjust or keep. Days you mark incomplete are left out.
> - M6: in the gym: supersets, warm-up suggestions for the first heavy lift, assisted pull-ups and dips, two steps up for beginners with reps to spare, and same-movement substitutes when you swap.
> - M7: a choice of AI provider: Anthropic, OpenAI, Google Gemini, or any OpenAI-compatible endpoint such as OpenRouter or a server on your own network. Each keeps its own encrypted key and model, and one monthly cap covers them all.
> - M8: a weekly review on the check-in screen, week by week: food against target with protein days hit and the share of ordered and packaged food, training against your plan with PRs and stalls, the trend, waist and scans, the finish line at your current rate, a month calendar with no streaks, and a body-composition chart on Body.
> - M9: faster food logging: *Describe it* now works with AI off, matching what you type against your own foods and the database (and going first with AI on, so repeat dishes are instant and free); *Will it fit?* shows what a dish would do to today before you order it; and an optional snack allowance inside your calorie target.
> - M10: training history: every workout and extra session on the Train tab, editing or deleting a past workout with PRs and next targets recomputed, a chart of each exercise's estimated 1RM and top set, and weekly sets per muscle against a band you can change.
> - M11: with AI on, a weekly programme review: you tick what's sent (body trends off by default), read the full text, and get up to 5 suggestions you apply one by one, with Undo. Train also offers a deload week every 6–8 weeks (4–6 for advanced lifters).
> - M13: Health Connect in: daily steps against a goal, last night's sleep at the morning weigh-in, walks, runs and classes recorded by other apps counted toward your weekly plan, and InBody measurements as draft scans. Each is off until you turn it on, read-only, and stays on the phone.
> - M12: opt-in reminders (weigh-in, lunch not logged, the evening nudge, workout days, the weekly check-in, scan day), at most two a day and only when still needed; an evening card on Today that suggests your own protein foods in portions that fit and logs one in a tap; and a home-screen widget.
> - M16: data out: a weekly automatic backup to a folder on the phone (keeping 8) or one file on Google Drive, CSV export for spreadsheets, and, if you turn it on, your workouts, meals and weigh-ins written to Health Connect.
> - M14: recipes: *New food* → *Recipe* builds a dish from the foods in it, with a cooked weight or a number of servings. It becomes one of your foods, with values per 100 g and per serving and its strictest ingredient's diet tag.
> - M15: share an InBody screenshot to Recomp and type the scan with it pinned above the form (it stays on the phone, never sent to AI); with a recent on-protocol scan, BMR uses your lean mass, Body offers to preview targets with it, and a target body-fat % becomes a finish line.
> - M19: goals: *What to expect* when you change goal, a Goal reached card, a suggested maintenance break after 12 weeks in a deficit that resumes on its own, *Describe your goal* read by AI (only the text you type is sent), a pregnancy and breastfeeding switch that turns off deficits, and presets as data files a fork can add to.
> - M20: lb and in: Settings → Body data → *Units* shows and takes body weight and lifts in lb (with 1, 2.5, 5 and 10 lb steps), lengths in inches and height in feet and inches. Storage stays metric, so history never changes; switching offers to move your exercises' weight steps to the nearest lb plates. Food stays in grams.
> - M18: camera food: scan a pack's barcode (your foods, then Open Food Facts), scan a nutrition label read on the phone (and checked by AI when it's on), Open Food Facts search for the International region, and meal photos in *Describe it*.
>
> With M4 the MVP is complete; v1.1 is under way. See the milestones in the TRD.
>
> **Not medical advice.** Check calorie targets with a professional if you have a medical condition.

- Product requirements: [docs/PRD.md](docs/PRD.md)
- Technical requirements: [docs/TRD.md](docs/TRD.md)

## What it will do

- **Workouts:**
  - Log sets in two taps, prefilled from last time.
  - See one target per exercise: add reps, add weight, repeat or deload.
  - Rest timer on the lock screen.
  - Stall detection tuned to your experience.
- **Nutrition:**
  - Targets from your goal, with protein first and a protein ramp for low-protein diets.
  - Regional food databases filtered to your diet.
  - Saved meals, quick add, and optional AI meal estimates from a description or a photo.
- **Body:**
  - Weight trend from a scale or body-composition scans.
  - Waist and photos.
  - InBody import through Health Connect or a shared screenshot.
- **Weekly review** judged against what success means for your goal.

## Build it yourself

You need:
- JDK 17+;
- an Android SDK with platform 37, from Android Studio or the command-line tools;
- a phone on Android 14+.

1. Fork and clone.
2. Point Gradle at your SDK in `local.properties` (Android Studio writes this for you):
   ```properties
   sdk.dir=/path/to/Android/sdk
   ```
   Barcode lookups and International search use [Open Food Facts](https://world.openfoodfacts.org), which asks apps to name a contact in their User-Agent. Add yours on the next line; it goes into your build only, never the repo:
   ```properties
   recomp.off.contact=you@example.com
   ```
3. Turn on the privacy hooks (below):
   ```bash
   git config core.hooksPath .githooks
   ```
4. **Indian food data:** each build downloads [INDB](https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-) at a pinned commit, checks its SHA-256 and converts it into the app's food database under `build/` (`./gradlew :app:fetchIndb` does just that step). Its repository has no licence, so the data is never committed, and any APK you share or publish must be built without it:
   ```bash
   ./gradlew :app:assembleRelease -Precomp.indb=false
   ```
   Without INDB, search has the protein-rich basics, your own foods and quick add.
5. Build, test and install:
   ```bash
   ./gradlew :domain:test              # pure-Kotlin engine tests
   ./gradlew :app:testDebugUnitTest    # Room, migration and seed-data tests (Robolectric)
   ./gradlew :app:assembleRelease      # minified APK (debug-signed for now)
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
6. **AI (optional):** create an API key in the [Claude Console](https://platform.claude.com) and paste it into the app. A claude.ai subscription login can't be used by apps, but [Max plans include monthly API credits](https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers) that can fund the key. Give the key its own workspace with a spend limit.

## Privacy hooks

The repo ships hooks that stop secrets and personal data from being committed. CI runs the same scan on every push.

**They block:**
- API keys, tokens and private keys;
- email addresses (except GitHub noreply addresses) and phone numbers;
- body-metric values such as "weight 82.5 kg" outside tests <!-- pii-ok: example -->;
- files that tend to hold personal data: databases, backups, exports, screenshots and photos, keystores;
- commits whose author or committer email isn't a GitHub noreply address.

**Your own identifiers:** put your name, emails, device serials and real body numbers in `.git/info/sensitive-patterns`, one case-insensitive regex per line. That file lives inside `.git`, so it can never be committed.

**Illustrative numbers** in docs can be allowed by adding `pii-ok` to the line. Secrets and your own patterns can't be allowed.

```bash
python3 .githooks/sensitive_scan.py all   # scan the whole tree any time
```

## Food data

The Indian food database comes from the [Indian Nutrient Databank (INDB)](https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-). See Vijayakumar, Dubasi, Awasthi and Jaacks, [*Indian Nutrient Databank*](https://pmc.ncbi.nlm.nih.gov/articles/PMC11277795/). INDB's repository declares no licence, so this repo never redistributes the data. Builds download it from the authors at a pinned commit (TRD §6.4).

## Layout

- `domain/`: pure Kotlin. Personas, goal and experience policies, progression, nutrition and trend logic, with no Android imports.
- `app/`: Compose UI, Room, Hilt, the workout service, and the Health Connect and AI clients. The exercise library and split templates are JSON in `app/src/main/assets/seed/`, so a fork can change them without code.
- `docs/`: PRD and TRD. `docs/local/` is gitignored, for your own notes.

## Licence

[MIT](LICENSE)
