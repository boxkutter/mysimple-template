# Notes for Claude

A mobile-first app of the platform: Kotlin Multiplatform (Android first, iOS later), local-first, connecting to the My Simple ISO platform (the same server as My Simple Auditor). It started from the **mysimple-template** repository; `.template-version` (in an app) names the template commit.

Read before changing code:

- [docs/platform.md](docs/platform.md): how the app talks to the server (sign-in, workspaces, sync, attachments, AI drafting) and what needs a server change.
- [docs/conventions.md](docs/conventions.md): modules, how to add a feature (copy the Items example), database migrations, UI and security rules.
- [docs/keeping-current.md](docs/keeping-current.md): what belongs in the template, and how changes flow between the template and the apps.

Rules that are easy to miss:

- All non-UI code goes in `mobile/shared` (`commonMain`); platform access only through `expect`/`actual`. UI is Compose Multiplatform in `mobile/composeApp`. No Google Play services.
- Every local write runs in a transaction that also queues its outbox operation.
- Synced entity names are shared by every app on the server: make them specific (`risk_assessment`, not `item`), register them on the platform, and name the local table's columns as the platform's (the sync engine writes pulled records generically).
- After the first release, database changes are migrations (`.sqm`); never edit a released one.
- UK English in the UI; accessibility rules in conventions.md.
- The server code is in the My Simple ISO platform repository (`boxkutter/iso`), not here. Don't change it from this repository. The contract the app relies on is `iso/docs/specification/platform-contract.md`.
- Generic improvements (anything every app would want) also go to the template, with an entry in `docs/template-changelog.md`.

Checks: `cd mobile && ./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug` (needs the Android SDK).
