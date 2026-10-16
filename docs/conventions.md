# Conventions

How the code is organised, and the rules every app of the platform follows. They come from the Mobile Auditor, where they were decided and proven; keep them unless an app has a good reason, and then say why in its docs.

## Modules

```text
mobile/
├── shared/       Kotlin Multiplatform core: domain, repositories, local database, API client.
│                 No UI. Everything that is not drawing pixels goes here.
├── composeApp/   The UI, in Compose Multiplatform (not Android-only Jetpack Compose APIs).
└── androidApp/   The Android shell: Application, MainActivity, manifest, icon, launch screen.
```

- **Android first, iOS later from the same code.** Platform services (Keystore, biometrics, camera, files) are reached through small `expect`/`actual` declarations, so only the iOS side has to be written later. Nothing Android-specific in `commonMain`.
- **No Google Play services.** The apps must run on de-Googled Android such as GrapheneOS. Use platform APIs and libraries that work without them: the platform `BiometricPrompt` for the app lock, a bundled engine rather than ML Kit, no Firebase. Push notifications via FCM may only ever be optional, with an in-app fallback.
- Android 10+ (API 29), phones and tablets.
- **One file per concern**, named after it. UI files are grouped by feature (`ui/items/`), shared code by layer (`data/`, `domain/`).

## Adding a feature

The template's **Items** are a complete example of every layer. To add the app's own records, copy them, then delete Items:

1. **Table** – `shared/src/commonMain/sqldelight/.../db/<Thing>.sq`: the synced-table columns (`id`, `owner_org_id`, `version`, `created_at`, `updated_at`, `deleted_at`) plus the record's own, and the named queries.
2. **Domain type** – `shared/.../domain/<Things>.kt`: plain data classes and enums. No database or UI types.
3. **Sync name** – add an `EntityType` in `Outbox.kt` (name it as in [platform.md §5](platform.md#5-sync)).
4. **Repository** – `shared/.../data/<Thing>Repository.kt`: `observe…()` flows for screens, `suspend` reads and writes. Every write runs in `db.transaction { … }` and queues its outbox operation inside it. Add it to `Repositories` in `AppContainer.kt`.
5. **Tests** – `shared/src/commonTest/.../<Thing>RepositoryTest.kt`, against the in-memory database (`testStore()`).
6. **Screens** – `composeApp/.../ui/<things>/`: a list screen (`AppScreen`, `PrimaryActionButton`, `EmptyState`) and a form (`FormScreen`, `FormField`).
7. **Routes** – add them in `AppShell.kt`, and a bottom tab if it is a main area (keep Status last).

## Database changes

The template starts at **schema version 1** with no migrations. Until the app's first release to real users, simply edit the `.sq` files (and reinstall the app on test phones).

From the first release on, **every change is a migration**:

1. Before the release, freeze the schema: `./gradlew :shared:generateCommonMainSimpleTemplateDatabaseSchema` writes `shared/src/commonMain/sqldelight/databases/1.db`. Commit it.
2. For each later change, add `shared/src/commonMain/sqldelight/migrations/<n>.sqm` (version *n* → *n+1*), update the `CREATE TABLE` in the `.sq` file to match, and note the version in a comment on the columns added.
3. `verifyMigrations` is on: the build checks that the migrations turn the frozen schema into the current one.

Never change a released migration.

## UI

- **Write in UK English**, plain and short. Say what something does for the user ("Unlock with your fingerprint, face or PIN"), not how it works.
- Every screen uses the building blocks: `AppScreen` (navy top bar, back arrow), `SectionCard` for groups of information, `LabeledValue`, `Badge`, one `PrimaryActionButton` for the screen's main action, `FormScreen` for editing.
- **Accessibility** is not optional: text from `MaterialTheme.typography` (scales with the phone's font size), colours from the theme (WCAG AA contrast), colour never the only cue (a badge also has a label), touch targets ≥ 48 dp, headings marked for screen readers (`SectionCard` does it).
- The theme (`ui/theme/Theme.kt`) is the platform family's: navy, teal, Atkinson Hyperlegible. An app may change its accents; keep the contrast.
- Strings live in the code for now (English only). Keep them in the composables, not scattered in logic, so they can move to resources when a second language comes.

## Security

- Information about other people (clients, employees, sites) is kept behind the **app lock**. `App.kt` says when it becomes mandatory; forms refuse to store such information until the phone has a screen lock (`LocalAppLock`, `SCREEN_LOCK_NEEDED`).
- Nothing personal or confidential in logs or crash reports.
- New Android permissions go in the manifest **with a comment saying why**. Prefer an intent to another app (e.g. the camera app) over a permission.
- **Test mode** (debug builds only, on the Status page) turns the app lock off so the app can be driven on a phone during development. It never exists in a release build.

## Tests

- `./gradlew :shared:testAndroidHostTest` runs the shared tests on the development machine.
- Test the shared code where bugs are costly: repositories (the outbox in particular), sign-in, anything that calculates. Screens are checked by hand on a phone.
- Tests read as sentences: `savingANewItemStoresItAndQueuesACreate`.
