# Template changelog

What changed in the template, newest first, and what an app made from an earlier version has to do to take each change. See [keeping-current.md](keeping-current.md).

## 0.2.0 — sign-in and sync against the platform (platform M1, M8, M9)

The platform connection, ported from My Simple Auditor and written generically: an app made from the template signs in and syncs against today's platform. `docs/platform.md` describes it.

- **Sign-in** (contract §2): OIDC authorization code + PKCE in the system browser (Custom Tabs, no Play services), discovery by email (`POST /v1/auth/discover`), refresh at Zitadel (the account ends only when the refresh is refused, never offline). New: `shared/.../auth/Oidc.kt`, `Sha256.kt`, `PlatformNetwork.kt`, `androidMain/.../auth/SecureRandom.android.kt`, `composeApp/.../ui/auth/OidcRedirects.kt`, `OidcBrowser.android.kt`. Rewritten: `AuthApi.kt` (discover, memberships, `POST /v1/orgs`), `ApiClient.kt` (`X-Org-Id` on every call, no password endpoints), `auth/AuthRepository.kt`, `auth/Session.kt` (workspaces, the account kept apart, ID token), `EncryptedSessionStore` (an account file), `LoginScreen.kt` (email only; the password is typed on the sign-in page).
- **One redirect scheme per app**: `eu.mysimple.simpletemplate:/oidc/callback`, set as `oidcScheme` in `androidApp/build.gradle.kts` (manifest intent filter, `BuildConfig.OIDC_SCHEME`), renamed by `new-app.sh` to `eu.mysimple.<slug>`. `MainActivity` is `singleTask` and hands the redirect to `OidcRedirects`.
- **Workspaces** (contract §3.2): the local workspace and the account's organisations, switched on the Status page (`ui/WorkspacesCard.kt`); a wiped organisation leaves the switcher.
- **Attaching the local workspace** (`sync/WorkspaceAttach.kt`): adopt (a new organisation takes the local id) or remap (into an existing one), rewriting ids and queued operations in one transaction, with `id_remap`. Flagged for personal data: everything in the local workspace goes to the organisation, and the confirmation says so.
- **Sync** (contract §5): `sync/SyncApi.kt`, `SyncEngine.kt`, `RecordWriter.kt`, `WorkspacePurge.kt`, `WorkspaceSync.kt`, `ui/SyncCard.kt`. `POST /v1/sync` with `X-Org-Id` and `X-Device-Id`, every result status handled, a cursor per organisation, conflicts kept for the user, wipe and `POST /v1/sync/wipe-ack`. **Table-driven**: an app registers its entities (`EntityType` now carries a `SyncEntity`: wire name, local table, ownership, optional platform columns, local-only columns, file columns), and pulled records are written into the tables generically.
- **Attachments** (contract §5.2): `sync/Attachments.kt`, a generic upload (parts, complete, SHA-256) and download for any entity that declares `FileColumns`; `DirectoryFileStorage` on Android.
- **Outbox**: `outbox_operation` gains `owner_org_id` (the workspace) and `rejected`; `sync_conflict`, `id_remap` and `file_transfer` are new (`Sync.sq`). `Outbox.created/updated/deleted` take the `Tenant` first. `LocalStore` takes the SQL driver.
- **Build settings**: `platform.baseUrl`, `platform.oidcIssuer`, `platform.devCa`, `simpletemplate.oidcClientId` (Gradle property or `local.properties`); debug builds default to the platform's development stack and can trust its CA (a generated network security config); release builds have no defaults. `mobile/tools/dev-device.sh` points an emulator at the development stack. Version 0.2.0.
- **The example `item` is not a platform entity**: the server rejects it. It stays as the example to copy; an app registers its own entities on the platform (contract §7).
- Left out (audit-specific in My Simple Auditor): moving one personal audit into an organisation (`AuditMove`, contract §3.1), criteria packages and versions, trail gaps, assignments, generated platform columns (the generator idea is in `docs/platform.md`), report fixtures, Google Play Billing.

To take it, in an app made from 0.1.x:

1. Copy the new files above (with the app's names: package, class prefix, slug). Replace `AuthApi.kt`, `ApiClient.kt`, `auth/`, `LoginScreen.kt`, `StatusScreen.kt`'s account card (now `WorkspacesCard` and `SyncCard`), and `AppContainer.kt` (it takes `OidcSettings`, `appVersion` and `files`, builds `WorkspaceSync` per organisation workspace, and exposes `attach`). Add `repos.sync?.start(this)` in a `LaunchedEffect(repos)` in `AppShell.kt`.
2. Turn each `EntityType` into `ENTITY(SyncEntity("wire_name", …))` and pass the `Tenant` to the outbox calls. Every synced table's columns must be the platform's names.
3. `Sync.sq`: take the new version. Before the app's first release, edit it in place; after it, add a migration that adds `owner_org_id TEXT NOT NULL DEFAULT ''` (filled from each payload's `owner_org_id`, or the local workspace's id) and `rejected INTEGER NOT NULL DEFAULT 0` to `outbox_operation`, and creates the three new tables.
4. `androidApp`: take `build.gradle.kts` (set `oidcScheme` to `eu.mysimple.<slug>`), the manifest's `singleTask`, intent filter and `networkSecurityConfig`, `res/xml/network_security_config.xml`, `MainActivity` and the `Application`. Set `platform.*` and `<slug>.oidcClientId` in `local.properties`.
5. On the platform: register the app's native client in `iso/zitadel/bootstrap.py` and its entities with a `SyncPolicy` each (`docs/platform.md`, "What a new app needs").

## 0.1.4 — the shared risk scoring engine (platform M13)

- **New:** `mobile/shared/src/commonMain/kotlin/app/simpletemplate/shared/risk/RiskScoring.kt`, pure Kotlin (no database, no platform code): the platform's fixed set of risk scoring methods (likelihood × consequence, sum, matrix, FMEA risk priority number and action-priority table, threat × vulnerability × consequence, impact over time), consequence categories, factors, levels with their response, rank, time to treat and review frequency. `RiskScoring.validate(definition)` and `RiskScoring.score(definition, input)` take the platform's JSON (a methodology's approved `definition` from `GET /v1/orgs/{org_id}/risk/methodologies/published`). Errors carry the server's codes.
- **Tested against the server's fixtures:** `mobile/shared/src/commonTest/fixtures/risk_scoring/` is a byte-identical copy of `iso/server/tests/fixtures/risk_scoring/` with the same `MANIFEST.sha256`; `RiskScoringFixturesTest` runs every case and checks the copy against the manifest. To update: change the fixtures in iso first, then `iso/scripts/risk-fixtures-manifest.sh --sync <this repo>/mobile/shared/src/commonTest/fixtures/risk_scoring`, then make both engines pass. Never edit the copy here.
- **Contract (platform ADR 0030 §6):** `risk_criteria` is organisation-owned since M13 (a device's write is `REJECTED`); a device can no longer set a risk's status to `ACCEPTED` (`INVALID_TRANSITION`: acceptance checks the authority for the level, on the platform); `risk` and `risk_assessment` gain optional and server-owned columns (the level, appetite, methodology version, `consequences`, `factors`). An app that ignores them keeps working.
- To take it: copy the `risk` package and its test with the fixtures. Nothing else changes.

## 0.1.3 — server contract notes

- The platform now synchronises the risk registers (`risk_criteria`, `risk`, `risk_assessment`, `risk_treatment`, `hazard`, `ohs_incident`, `environmental_aspect`) through `POST /v1/sync` with the registers' own rules applied (a field assessment is scored and rolled up by the server). Specification for the app that uses them: `iso/docs/specification/my-simple-risk.md`.
- Organisations attached to a Ligature business sign in through that business's identity hub; an app signing in against the hub's Zitadel works with no API change (platform contract §7.3).
- Nothing to do in an existing app.

## 0.1.2 — the platform's identity, sync and attachment endpoints exist

Documentation only; no code change yet. `docs/platform.md` describes what the server now does (Zitadel OIDC with PKCE, `POST /v1/sync`, `POST /v1/files/uploads`, per-organisation AI) and what the template's `AuthApi`/`AuthRepository` and outbox sender must become. The password endpoints no longer exist on the server, so an app built from 0.1.x cannot sign in until that code lands (it needs an Android SDK to build; the environment that produced the server side had none).

## 0.1.1 — the server is the My Simple ISO platform

Documentation only; no code change, nothing to take.

- The server, its deploy stack and `api/openapi.yaml` moved from `boxkutter/mobileauditor` to the My Simple ISO platform (`boxkutter/iso`). `docs/platform.md` is now a summary that points at `iso/docs/specification/platform-contract.md`, the authoritative contract.
- Announced for platform milestone M1: identity moves to Zitadel (OIDC authorization code + PKCE in the system browser; `GET /v1/me` returns memberships; `X-Org-Id` per call). The template's `AuthApi`/`AuthRepository` change then, with a changelog entry describing how to take it.

## 0.1.0 — first version

Derived from the Mobile Auditor at commit `f384bf9`.

- **Platform connection**: API client with bearer tokens and rotating refresh, sign-in, sign-out, `GET /v1/me`, health check, AI drafting call.
- **Local-first**: works without an account (local workspace); encrypted SQLDelight/SQLCipher database; sync outbox; per-user settings.
- **Security**: app lock with the phone's fingerprint, face or PIN, mandatory by an app-defined rule; hidden from Recents while in use; test mode in debug builds.
- **UI**: theme (navy/teal, Atkinson Hyperlegible, light and dark), building blocks (`AppScreen`, `SectionCard`, `LabeledValue`, `Badge`, `PrimaryActionButton`, `EmptyState`, `FormScreen`, `FormField`, date/time/time-zone pickers), welcome, sign-in and Status screens.
- **Example feature**: Items (table, repository, tests, list and form screens), to copy and then delete.
- **Tooling**: `scripts/new-app.sh`, `mobile/run-on-phone.sh`.

Left out of the template on purpose (audit-specific in the Mobile Auditor, port if an app needs them): on-device speech recognition (whisper.cpp), voice dictation, evidence capture (camera, files), the touch lock and pocket mode, stealth mode, report generation (DOCX), client import from spreadsheets, and the clients/contacts/sites records.
