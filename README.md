# mysimple-template

**The starting point for a new mobile-first app on the platform.** Duplicate it to start an app such as a risk assessment tool that works like the Mobile Auditor and connects to the same server.

It is the Mobile Auditor's foundation with the auditing taken out: the platform connection, local-first encrypted storage, the app lock, the look and the build, plus one small example feature to copy. Everything builds and runs as a blank app.

| | |
|---|---|
| **Reference app** | Mobile Auditor (`boxkutter/mobileauditor`), derived at commit `f384bf9` |
| **Platform server** | `https://mobileauditor.h03.eu` (the My Simple ISO platform, `boxkutter/iso`; build setting `-Pplatform.baseUrl`) |
| **Stack** | Kotlin Multiplatform · Compose Multiplatform · SQLDelight + SQLCipher · Ktor · Android 10+ (iOS later) |
| **Version** | 0.1.2 ([changelog](docs/template-changelog.md)) |

## Start a new app

```bash
scripts/new-app.sh --name "Risk Assessor" --dest ../riskassessor
```

Then follow [docs/new-app.md](docs/new-app.md): branding, the app's own records in place of the example, the app lock rule, its repository.

## What you get

**Platform connection** (works exactly like the Mobile Auditor)
- Starts without an account: a local workspace on the phone, nothing sent anywhere.
- Optional sign-in with a platform account: access and refresh tokens, automatic refresh, sign-out; offline never signs the user out.
- Status page: the account, the app lock, a connection check (DNS, server health, signed-in user) and the local database.
- AI drafting call (`POST /v1/ai/draft`), only with an account.

**Local-first data**
- SQLDelight database encrypted with SQLCipher; keys in the Android Keystore; no backups of secrets.
- Sync outbox: every write queues its operation in the same transaction, ready for the server's sync.
- Per-user settings on the device.

**Security**
- App lock with the phone's fingerprint, face or PIN; mandatory once the app stores information that needs protecting; hidden from Recents while in use.
- Test mode (debug builds only) to drive the app on a phone during development.

**Look and building blocks**
- The platform family's theme: navy and teal, Atkinson Hyperlegible, light and dark, WCAG AA contrast.
- `AppScreen`, `SectionCard`, `LabeledValue`, `Badge`, `PrimaryActionButton`, `EmptyState`, `FormScreen`, `FormField`, date/time/time-zone pickers.

**Example feature: Items**: a table, repository, tests, list and form screens. Copy it for the app's own records, then delete it.

## Repository layout

```text
mobile/                 Kotlin Multiplatform project
  shared/               domain, repositories, encrypted database, API client (no UI)
  composeApp/           Compose Multiplatform UI
  androidApp/           Android shell: application, activity, manifest, icon, launch screen
  run-on-phone.sh       build, install and launch on a phone over wireless debugging
scripts/new-app.sh      create a new app from the template
branding/               placeholder mark and icon (replace per app)
docs/                   platform contract, conventions, new-app checklist, keeping current
licenses/               third-party licences (the bundled font)
```

## Develop the template itself

Requires the Android SDK (`sdk.dir` in `mobile/local.properties`); Gradle downloads a JDK if needed.

```bash
cd mobile
./gradlew :androidApp:assembleDebug     # → androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:testAndroidHostTest   # shared-module unit tests
./run-on-phone.sh                       # build, install and launch on a phone
```

The template is kept current as the apps are built: improvements that every app wants come back here. See [docs/keeping-current.md](docs/keeping-current.md).

## Documents

| Document | Contents |
|---|---|
| [Platform](docs/platform.md) | How an app connects to the My Simple ISO platform (summary; the authoritative contract lives in `boxkutter/iso`) |
| [Conventions](docs/conventions.md) | Modules, adding a feature, database changes, UI, security, tests |
| [New app](docs/new-app.md) | Creating an app from the template and making it its own |
| [Keeping current](docs/keeping-current.md) | What belongs in the template; moving changes between the template and the apps |
| [Template changelog](docs/template-changelog.md) | What changed in each template version, and how to take it |
| [Branding](branding/README.md) | The placeholder artwork and every file to replace |
