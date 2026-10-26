# NEXORA — Campus Placement & Recruitment Management System

> *Your Next Opportunity Starts Here.*
> Native Android · Java · XML · MVVM + Repository · Firebase Auth + Cloud Firestore (Spark/free) · Room · WorkManager

One APK with two role-based portals:

- **Student:** profile, drive discovery, explainable eligibility and match score, apply, recruitment timeline, readiness dashboard, announcements.
- **Placement officer:** verification queue, companies, drives, applicant pipeline, announcements, analytics.

The full specification is in `NEXORA_PLAN.md` (kept outside the repo). This README records how the build differs from it, the setup steps, test results and the demo script.

---

## 0. Which backend does a build use?

- **`app/google-services.json` present** (the live setup, project `nexora-a014b`): every build, including Android Studio's ▶ Run, uses the **real Firebase project**. Sign-up and login work on any device with internet.
- **No `google-services.json`**: debug builds use the local **Firebase Emulator Suite** (§1).
- Override either way with `-Pnexora.useEmulator=true|false`.

**Live demo layout:** the placement officer signs in on the Android Studio emulator, and the student uses a physical phone. Both talk to the same Firestore project, so every action appears live on the other device.
- If the emulator shows network errors while the laptop is online, cold-boot it with fixed DNS: `emulator -avd Pixel_6a -no-snapshot-load -dns-server 8.8.8.8,1.1.1.1`.

## 1. Running it locally (no Firebase project needed)

Without `google-services.json`, debug builds talk to the **Firebase Emulator Suite** using the offline `demo-nexora` project, so no console setup or credentials are needed.

Prerequisites:
- Android Studio (its bundled JBR 21 is fine for Gradle and the emulators).
- Node 20+.
- An Android emulator or device.

```bash
cd firebase && npm install
```
```bash
cd firebase && npm run emulators
```
In a second terminal, seed the demo data (1 officer, 4 students, 4 companies, 6 drives, applications, activities, announcements):
```bash
cd firebase && npm run seed
```
Then run the `app` configuration from Android Studio, or:
```bash
./gradlew installDebug
```

- The Android emulator reaches your PC at `10.0.2.2`, which is the default.
- On a **physical phone**, build with your PC's LAN IP:
  `./gradlew installDebug -Pnexora.emulatorHost=192.168.x.x`
  The emulators already listen on `0.0.0.0`.

Demo accounts (password `Test@1234`):

| Account | Role | Notes |
|---|---|---|
| officer@nexora.test | Officer | Created only by the seed script (Admin SDK). |
| aisha@nexora.test | Student | CSE_AI, 8.40, verified. Strong aptitude, weak coding. |
| rahul@nexora.test | Student | CSE, **6.99**, verified. Just below the 7.00 cutoff. |
| meera@nexora.test | Student | ME, 8.90, verified. Wrong branch for SDE drives. |
| kiran@nexora.test | Student | CSE, 8.10, **unverified**. |

## 2. Using a real Firebase project (Spark plan)

These console steps are manual and not automated:

1. Create a Firebase project. Enable **Authentication → Email/Password** and create a **Cloud Firestore** database.
2. Add an Android app with package `com.nexora`. Download `google-services.json` into `app/`.
   - The file is git-ignored.
   - Gradle reads it directly; the google-services plugin is not used, see §5.
3. Deploy the rules and indexes. Do this yourself after reviewing them:
   ```bash
   cd firebase && npx firebase deploy --only firestore:rules,firestore:indexes --project <your-project-id>
   ```
4. Create accounts in the app. **Create account** offers **Student** or **Placement officer**; an officer account opens the officer portal directly. Optional demo data can be loaded with the Admin SDK seed script and a service-account key (never commit it):
   ```bash
   cd firebase && node seed/seed.js --service-account ./service-account.json
   ```
5. Build and install. With `google-services.json` in `app/`, the normal build targets the real project:
   ```bash
   ./gradlew installDebug
   ```
6. Optional FCM: send a console message to topic `all_students`.
   - FCM is enabled only when a real `google-services.json` is present.
   - No feature depends on it.

## 3. Architecture

```
app/src/main/java/com/nexora/
  NexoraApp.java              Application + manual AppContainer (no DI framework), Firebase/emulator init, theme, sync schedule
  domain/   (pure Java — enforced by a unit test that scans imports)
    Models.java               all POJOs/enums as nested classes
    EligibilityEngine.java    explainable per-criterion eligibility (mirrors rules eligible())
    MatchScoreCalculator.java 0.60·S + 0.25·P + 0.15·R, skill alias normalisation, reasons, bands
    ReadinessAnalyzer.java    5 weighted components, 30-day window, 6-week trend, drive-linked suggestions
    ApplicationWorkflow.java  canApply / newApplication / advance / reject, immutable final states
    Analytics.java            dashboard metrics, funnel, branch selections, readiness comparison
    Validators.java           every §7.5 input rule
  data/
    FirestoreMapper.java      the only Firestore⇄model/JSON mapping + lifecycle-aware listener LiveData
    Resource.java             LOADING | SUCCESS | ERROR wrapper + callback
    LocalDb.java              Room: bookmarks, readiness activities, cached drives/applications, one DAO
    AuthRepository.java       auth, users/roles, profiles, verification
    PlacementRepository.java  companies, drives, applications, notifications, bookmarks, Room write-through cache
    ReadinessRepository.java  local-first activities, idempotent upload/pull
    SyncWorker.java           one WorkManager worker (readiness upload + notification fetch) + local notifications
    MessagingService.java     optional FCM display
  ui/
    AuthActivity.java         splash routing, onboarding, sign in/up, reset
    MainActivity.java         one shell for both roles (role-specific bottom nav), offline banner, theme, logout
    StudentViewModel.java     live inputs → eligibility, match, readiness per drive; filters
    OfficerViewModel.java     officer data + actions (batched decision + notification)
    UiKit.java                generic adapter, row/criterion/progress binders, state view, dialogs, screen bases
    ChartView.java            one custom view: ring, bars, line, funnel
    StudentScreens.java       Home, Applications, Journey (also officer applicant view), Notifications
    DriveScreens.java         Drive list (search/filter/sort/bookmark), Drive detail (eligibility/match/apply)
    ProfileScreens.java       Profile editor (academic lock), Readiness dashboard + log activity
    OfficerScreens.java       Dashboard, Verification queue, Announcements, Analytics
    DriveAdminScreens.java    Officer drives, Applicants, Drive editor (criteria, skills, focus, stages), Companies
firebase/   firestore.rules · firestore.indexes.json · tests/rules.test.js · seed/seed.js
```

How the layers fit together:
- **Data flow:** Firestore snapshot listener → `QueryLiveData` → repository → ViewModel (pure domain engines) → fragment.
- **Listener lifetime:** listeners attach in `onActive` and detach in `onInactive`, so no screen leaks a listener.
- **Firebase confinement:** ViewModels never touch Firebase types.
- **Fragments:** screens are nested `public static` fragment classes, grouped by feature to keep the file count small.

### Deliberate deviations from the plan (and why)

| Plan | Built | Reason |
|---|---|---|
| ~90 files (one per class) | 27 main Java files | Requested compact codebase. Models, results and DAOs are nested; one adapter and one list layout are reused everywhere. |
| Navigation component + nav graphs | One `MainActivity` with role-specific bottom-nav menus and a fragment back stack | Removes nav XML and safe-args boilerplate. |
| MPAndroidChart (JitPack) | `ChartView` (≈260 lines) | No extra repository or unmaintained dependency; theme-aware in dark mode. |
| Two workers | One `SyncWorker` | Same schedule and constraints for both jobs. |
| google-services Gradle plugin | Gradle parses `app/google-services.json` into `BuildConfig` | Works with AGP 9. Without the file the app falls back to the emulator demo project. |
| `recruitmentRounds` collection (proposal) | `drive.stages[]` + `application.stageHistory[]` | Plan §2.6: fewer reads, simpler rules. |
| `studentProfiles` without `name` | Adds `name`/`email` | Lets the rules verify the denormalised `studentName` on applications without an extra `get()`. |

## 4. Security (Firestore rules audit)

The rules in `firebase/firestore.rules` are the server-side gate. The Java eligibility check exists only for user feedback.

I hardened the plan's §6 draft after auditing it. The gaps found and fixed:

1. **Forged application data.** Students could write any `profileSnapshot`, `studentName`, `driveTitle`, `matchScore` or `stageHistory`.
   - The rules now require these to **equal the real profile and drive**.
   - `stageHistory` must start empty and `matchScore` must be 0–100.
2. **Unchecked writes.** Writes had no key whitelists. Every create/update now uses `keys().hasOnly(...)`, so no smuggled fields.
3. **Forgeable verification.** An officer could forge `verifiedBy` or verify an incomplete record.
   - Verification must now be stamped with the officer's own uid and the server time.
   - All academic fields must be present.
4. **Unbounded outcome changes.** Officer updates could jump stages or reopen final outcomes.
   - An update now moves at most **one stage**, appends **exactly one** history entry, and **SELECTED/REJECTED are immutable**.
5. **Registration integrity.** Email must match the auth token and `createdAt` must be the server time. Only `name` and `fcmToken` stay editable.
6. **Schema checks.** Profiles have type/range checks (CGPA 0–10, backlogs int 0–30, branch enum, roll-number pattern). Readiness activities and notifications have schema checks, and notifications must carry `createdBy == officer`.
7. **Draft queries.** Draft drives use `status in ['OPEN','CLOSED']`, so student queries are provably safe.

**Project decision — open officer sign-up.** Registration accepts role `STUDENT` or `OFFICER`, chosen once in the app; the role can never be changed afterwards from the client, and an officer cannot also create a student profile. This keeps a college demo simple. The trade-off is that anyone with the APK can create an officer account; a production deployment would require officer approval instead.

Rules unchanged from the plan's intent:
- Application ids are deterministic (`{driveId}_{uid}`). A second create is an update, which students are denied, so duplicates are rejected.
- Closed, expired, draft and ineligible drives are rejected at create.

## 5. Test results (actually run, 25 Oct 2026)

| Check | Command | Result |
|---|---|---|
| Build | `./gradlew clean assembleDebug` | ✅ success |
| JVM unit tests (eligibility boundaries, match, readiness, workflow, validators, analytics, domain-purity) | `./gradlew testDebugUnitTest` | ✅ **53 / 53** pass |
| Domain coverage (JaCoCo) | `./gradlew createDebugUnitTestCoverageReport` | ✅ **98.6 % lines** (489/496), 85.5 % branches (target ≥ 90 % lines) |
| Firestore rules tests (emulator) | `cd firebase && npm test` | ✅ **47 / 47** pass |
| Instrumented (Room DAO ×4, Espresso onboarding/validation ×1) on Pixel 6a AVD API 36 | `./gradlew connectedDebugAndroidTest` | ✅ **5 / 5** pass |

The rules tests cover:
- role escalation;
- post-verification locking and forged verification;
- private profile, application, activity and notification access;
- draft drives;
- valid and invalid applications: exact 7.00 passes, 6.99 fails, wrong branch, backlogs max/max+1, expired, closed, draft, unverified, wrong id, duplicate, forged snapshot/name/history/score/time;
- student and officer update limits, final-state immutability, and the officer-only batch decision + notification.

**Manual end-to-end check** on the AVD against the emulators, driven via adb:
- **Kiran (unverified):** login → role routing → home shows *Verification pending* plus a provisional match.
- **Drive detail:** per-criterion ✓ rows, Apply disabled with a reason.
- **Officer:** dashboard counts match the seed (4 open drives, 4 applications, 1 pending, 1 selection) → verified Kiran → advanced Aisha with a note.
  - The timeline updated.
  - The funnel, branch chart and readiness comparison match a manual count.
- **Aisha:** applied to SDE Intern (accepted by the hardened rules) → the button switched to *Applied*.
- **Offline:** network off → *Offline* banner → activity saved to Room → network on → exactly 1 copy in Firestore, still 1 after a forced resync.
- **Live notifications:** a targeted update posted an Android notification while the app was open.
- **Dark theme:** checked.

## 6. Demo script (≈8 min, follows plan §10)

1. **Kiran** signs in: home shows *Verification pending*, and SDE Intern shows *Needs verification* with a provisional match.
   - **Officer** → Verify → Kiran → *Verify record*.
   - Kiran's SDE Intern now shows **Eligible**.
2. Open **SDE Intern** as:
   - **Aisha:** Eligible, with the high match formula.
   - **Rahul:** *Not eligible — "Your CGPA is 6.99; this drive requires at least 7.00 (short by 0.01)."*
   - **Meera:** branch ✗.
3. **Aisha** applies (the confirmation shows her snapshot). Reopen the drive: *Applied*. The server rejects any second create; this is covered by the rules tests.
4. **Officer** → Drives → SDE Intern → Aisha → **Advance** with a note.
   - Aisha's timeline moves to *Online Assessment* (its schedule and link show).
   - She gets a notification: live if the app is open, otherwise within 15 min.
5. **Aisha → Readiness:** coding is weakest (≈21 %). The suggestion links to the eligible drives closing within 7 days that focus on CODING.
6. **Airplane mode:** drives and applications stay browsable with the offline banner, and logging an activity saves it offline. Reconnect: it syncs once.
7. **Officer → Analytics:**
   - applications per drive;
   - funnel per drive (dropdown);
   - branch-wise selections;
   - readiness of applicants vs selected students.

## 7. Known limitations

- **No push without a server.** Background notifications arrive through a 15-minute periodic sync when the app is closed; they're instant only while it is open. Spark has no Cloud Functions.
- **Eligibility trust.** Eligibility relies on officer verification of academic records.
- **Heuristic scores.** Match and readiness use fixed, documented weights. They rank and guide; they do not predict outcomes. Readiness is self-logged.
- **Client-side analytics.** Analytics are computed on the device, and officers listen to all applications. This suits a single campus, not large scale.
- **Locked stages.** After applicants exist, a drive's stage *structure* is locked in the editor; schedules and venues stay editable.
- **Cached role.** The role is cached for instant and offline start, then re-verified from the server on every launch. Data access is always enforced by the rules.
- **Not yet done:**
  - The full Espresso journeys from plan §9.3 (register → apply; officer create → publish) were exercised manually via adb, not as automated Espresso tests.
  - The §9.4 usability study.
- **Open officer sign-up.** Anyone with the APK can register as a placement officer (see §4).
- **Officer edge case.** An account created in the Authentication console *without* a `users` doc becomes a STUDENT on first login.
