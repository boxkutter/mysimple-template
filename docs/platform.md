# The platform: how the app connects

Every app made from this template works the same way as **My Simple Auditor** (the Mobile Auditor) and talks to the **same server**: the **My Simple ISO platform**, whose home is [`boxkutter/iso`](https://github.com/boxkutter/iso). The authoritative description of the contract from the app's side is that repository's [`docs/specification/platform-contract.md`](https://github.com/boxkutter/iso/blob/main/docs/specification/platform-contract.md); this page summarises it and says where the template's code for each part lives. The code is ported from My Simple Auditor (`boxkutter/mobileauditor`, `mobile/shared/.../auth/` and `.../sync/`), written generically.

| | |
|---|---|
| **Server** | Build settings (below). Debug builds default to the platform's development stack (`https://api.mysimple.localhost`, `https://auth.mysimple.localhost`); release builds have no default |
| **Server code and contract** | `boxkutter/iso`: `server/`, `api/openapi.yaml`, `docs/specification/platform-contract.md` |
| **App code** | `mobile/shared/.../ApiClient.kt` (all calls), `AuthApi.kt` / `AiApi.kt` (wire types), `auth/` (sign-in, sessions), `sync/` (sync engine, attachments, workspace attach) |

## Local-first, account optional (contract §1)

The app is complete without the server. On first start the user gets a **local workspace** (`Session.isLocal`): a user id and an organisation id generated on the phone, nothing sent anywhere. Signing in adds organisation workspaces, sync and AI drafting. Before sign-in the only calls are the sign-in calls themselves and `GET /health` (the Status page's connection check).

## Identity (contract §2)

- **Sign-in**: OIDC authorization code + PKCE (S256) with the platform's Zitadel, in the **system browser** (Android Custom Tabs; no Google Play services, no web view). `POST /v1/auth/discover {email}` first says which Zitadel organisation the address signs in to; the authorize URL asks for `openid profile email offline_access urn:zitadel:iam:user:resourceowner urn:zitadel:iam:org:id:{id}` with the email as `login_hint`. The password is typed on Zitadel's page, never in the app. Code: `auth/Oidc.kt` (URLs, PKCE, state and nonce checks, token exchange and refresh: plain HTTP, unit-tested), `auth/AuthRepository.kt`; the browser is `composeApp/.../ui/auth/OidcBrowser.android.kt`, and `MainActivity` hands the redirect to `OidcRedirects`.
- **One redirect scheme per app** (RFC 8252 §7.1): `eu.mysimple.<slug>:/oidc/callback`, post-logout `eu.mysimple.<slug>:/oidc/logout`. The template's is `eu.mysimple.simpletemplate`, set once in `androidApp/build.gradle.kts` (`oidcScheme`: the manifest's intent filter, `BuildConfig.OIDC_SCHEME`); `scripts/new-app.sh` renames it with the slug. Each app is its own Zitadel **native** application with that redirect URI (see "What a new app needs").
- **Tokens** are kept in the Keystore-wrapped session store (`EncryptedSessionStore`: the open workspace, the local workspace and the account). The access token is refreshed at Zitadel on a 401; the account ends only when Zitadel refuses the refresh token, never on a network error (the app keeps working offline).
- **Workspaces**: `GET /v1/me` returns the user and their memberships. The user picks the workspace on the Status page (the local workspace and each organisation); every call then carries **`X-Org-Id`** (`ApiClient`'s organisation header; the token never decides the organisation). Signing out keeps all local data and returns to the local workspace; signing in as a different account removes the previous account's organisation workspaces (Sync, "Several accounts on one phone").
- **Approvals** that need a recent sign-in (`403 reauthentication_required`): `AuthRepository.reauthenticate` signs in again with `prompt=login&max_age=300`; the new ID token is in `Session.idToken`, to send as `X-Id-Token`.

### Build settings

A Gradle property (`-P…`) or `mobile/local.properties` (git-ignored, per machine):

| Setting | What | Default |
|---|---|---|
| `platform.baseUrl` | The platform API | debug: `https://api.mysimple.localhost`; release: none |
| `platform.oidcIssuer` | The platform's Zitadel | debug: `https://auth.mysimple.localhost`; release: none |
| `simpletemplate.oidcClientId` | This app's native OIDC client in Zitadel (renamed to `<slug>.oidcClientId` by `new-app.sh`) | none: set it |
| `platform.devCa` | The development stack's CA certificate (`iso/deploy/traefik/certs/ca.crt`); debug builds trust it | none |

A release built without them cannot sign in, rather than reaching the wrong server. Debug builds resolve `*.mysimple.localhost` to the device's loopback (`PlatformNetwork`); `mobile/tools/dev-device.sh /path/to/iso` forwards an emulator's port 443 to the development stack and makes Chrome accept its certificates. With the iso dev stack running, its Zitadel bootstrap writes each native client's id to `iso/zitadel/out/bootstrap.json`.

## Organisations (contract §3)

Everything a user creates belongs to an organisation: every synced row carries **`owner_org_id`** and every query is scoped by it (`Tenant`); on the server, Postgres row-level security enforces the same boundary.

### Making the local workspace an organisation's

The local workspace never syncs. Signed in, the user can choose on the Status page to bring it into an organisation (`sync/WorkspaceAttach.kt`, platform plan §7):

- **adopt**: a new organisation is created with the local workspace's own id (`POST /v1/orgs {id, display_name, timezone}`; the user becomes its Owner), so the records keep their `owner_org_id`;
- **remap**: the records move into an organisation the account already belongs to: `owner_org_id`, `managed_by_org_id` and `organisation_id` naming the local organisation are rewritten.

In both, the local user id becomes the platform user's (every `user_id` / `*_user_id` column, and the user's settings on the phone), the queued operations are rewritten and re-owned in the same database transaction, the mapping is kept in `id_remap`, and a queued `organisation` record of the local workspace is dropped (the platform has its own). The local workspace then ends and the organisation workspace opens; its sync sends the records. **Personal data**: everything in the local workspace, including information about other people, goes to the organisation; the confirmation says so.

My Simple Auditor also moves a single personal *audit* into an organisation through an adoption opened on the platform (contract §3.1, iso ADR 0025: `POST /v1/orgs/{org_id}/performance/audits/adoptions`, sync creates, then `…/complete`). That flow is audit-specific and not in the template; an app that needs to move one record tree at a time can follow `mobileauditor/mobile/shared/.../sync/AuditMove.kt`, with an adoption endpoint of its own on the platform.

## Data on the phone (contract §4)

UUIDv7 ids generated on the device; `version` 0 until accepted; epoch-millisecond timestamps; soft deletes; references declared with `REFERENCES` but not enforced (sync delivers records in any order); SQLCipher; the app lock.

## Sync (contract §5)

- **Outbox**: every local write queues `{op_id, entity_type, entity_id, op_type, payload, base_version}` in `outbox_operation` **in the same transaction** (`data/Outbox.kt`), tagged with the workspace it was made in. Only that organisation's sync sends it; the local workspace's operations stay on the phone until it is attached. A repository passes the record's local version, which moves only when the platform accepts a change; the outbox bases each operation on the version the platform will hold when it arrives (the last unsent, not refused operation's base + 1 for the same record, else the local version; a CREATE is 0), so several changes made before a sync are sent as a chain, not as conflicts with each other.
- **Sending and receiving** (`sync/SyncEngine.kt`): `POST /v1/sync` with `X-Org-Id` and `X-Device-Id` (a stable id per account on the phone: see "Several accounts on one phone" below), the organisation's own **cursor** and the queued operations; paged until the platform has nothing more (`has_more`; a page can be short because the feed is scoped to the user's assignments, §5.3). Per result:

  | Status | What the app does |
  |---|---|
  | `ACCEPTED` | drops the operation, stores the `server_version` |
  | `CONFLICT`, `EDIT_WINS` | keeps both versions in `sync_conflict` for the user (Status page: keep mine, which queues a new UPDATE on the platform's version, or use theirs); an `EDIT_WINS` brings the record back |
  | `RETRY_LATER` | keeps the operation and sends it again (a parent is not on the platform yet) |
  | `REJECTED`, `INVALID_TRANSITION`, `DUPLICATE_ID` | keeps it with the reason, never resends; the user can discard it, which restarts the feed so the platform's records come back |

- **Change feed**: records are written into the local tables **generically**, table-driven: each entity the app registers (`EntityType` in `data/Outbox.kt`, a `SyncEntity` each) names its local table, whose columns are the platform's column names. Keys the table lacks are ignored; local-only columns are never overwritten; a change still waiting on the phone is not overwritten by a pull. A tombstone (`deleted`) removes the record and every row that refers to it by a declared foreign key, with their queued changes and files. Entities the app does not register are skipped.
- **Wipe**: when the membership ends, the platform answers with a `wipe` list. The app deletes everything of that organisation on the phone (every table with `owner_org_id`, what hangs on those rows, the queued changes, conflicts, cursor and files: `sync/WorkspacePurge.kt`, derived from the schema), acknowledges with `POST /v1/sync/wipe-ack`, and the workspace leaves the switcher (`AuthRepository.workspaceRemoved`).
- **Before sending**, the app keeps back what the platform would refuse and says why (`SyncRegistry.problem`): an entity it does not register, one the organisation owns (`Ownership.Organisation`), a field the device may not change (`Ownership.Mixed`), and, when the entity lists its `platformColumns`, a key that is not a column (since M8 the server rejects unknown keys).
- **Several accounts on one phone**: the platform registers a device id to the first account that syncs with it and refuses it for any other (`401` "This device belongs to another account"), so the phone keeps one `X-Device-Id` per account (`SyncEngine(userId)`; the id the installation had before goes to the first account that syncs), and on that `401` makes a new id for the account and tries once more. The phone also remembers whose its organisation data is (`sync/PhoneAccounts.kt`): signing in as another account removes the previous account's organisation workspaces (rows, files, queued changes, conflicts, cursors, by `WorkspacePurge`; the local workspace stays, and the platform keeps the records, which come back when that account signs in again). If the previous account has changes not sent (queued, refused or in conflict), the sign-in screen warns first and offers to sign in as that account again and sync, or to discard them (`auth/AccountChange.kt`, `UnsentChangesException`). Personal data: the previous account's user id, name and email stay on the phone, in the encrypted database, with its data until another account signs in.
- **When**: `sync/WorkspaceSync.kt` syncs when an organisation workspace opens, a few seconds after a local change, every five minutes while the app is open, and on "Sync now" (Status page, `ui/SyncCard.kt`).
- **Entity names are global**: every app shares them. Name tables specifically (`risk_assessment`, not `item`) and reuse the platform's names for organisations, contacts, sites and processes (`auditable_unit`). The reserved names are listed in contract §5.1.
- **The example entity `item` is not a platform entity.** The template's Items show every layer, but the server answers `REJECTED` to them: an app replaces them with its own entities, registered on the platform first (below).

The platform's column lists can be generated rather than typed: My Simple Auditor's `mobile/tools/platform_columns.py` reads the platform's sync policies (`iso/server/app/modules/sync/policies.py`) and writes a Kotlin map of each entity's columns, kinds and ownership. An app with many entities can do the same for its `platformColumns`.

### Attachments (contract §5.2)

`sync/Attachments.kt` moves files for every registered entity that declares `FileColumns` (`file_name` local-only, `sha256`, `mime_type`, `original_name`, `file_object_id`): an upload is `POST /v1/files/uploads` → each part `PUT` to its pre-signed URL (with a client that sends no platform credentials) → `POST …/complete`; the platform verifies the SHA-256, scans and encrypts, and deduplicates by hash per organisation. A record whose `file_object_id` the platform filled in and that has no file here is downloaded (`GET /v1/files/{id}/content`) and checked against its hash. Files live in the app's private storage (`DirectoryFileStorage`). The template's Items carry no file; linking a record to its `file_object` is the entity's `SyncPolicy` on the platform.

## AI drafting (contract §6)

`POST /v1/ai/draft {task, context, current?}`, for organisation workspaces (AI is opt-in per organisation; `reason` says why there is no draft). A draft is always a suggestion; never without an account (`data/AiDrafting.kt`).

## What a new app needs from the server

Changes in `boxkutter/iso`, not here (contract §7):

1. **Its own sign-in client**: add the app to `NATIVE_APP_SCHEMES` in `iso/zitadel/bootstrap.py` with its scheme (`eu.mysimple.<slug>`), run the bootstrap, and put the client id in `<slug>.oidcClientId`.
2. **Its entities**: register each entity name and a `SyncPolicy` per entity (`server/app/modules/<module>/sync.py`: columns, field ownership, assignment scope, state transitions, file links), with tables that carry `owner_org_id` and row-level security. Until then the server rejects the app's operations.
3. **AI tasks** and a system prompt, if the app drafts text; **reference data** modules as needed.

After a server change, refresh `api/openapi.yaml` in iso and mirror the wire types in the app's `shared` module.

## Not in the template

From My Simple Auditor, on purpose: the criteria packages and their versions (`PlatformCriteria`, `CriteriaVersions`), the audit trail's gaps (`TrailGaps`), the generated column lists (`PlatformColumns`), assignments ("Assigned to you", `POST …/assignment`), moving a personal audit (`AuditMove`), report fixtures, and purchases of paid library packs with Google Play Billing (an optional store dependency the template does not carry; see My Simple Auditor's `androidApp/src/play` and iso ADR 0028 if an app sells content).

## Risk methodologies (platform M13, M16)

An organisation rates risks with its own methodologies (platform ADR 0022, ADR 0030). In an organisation workspace an app reads them read-only from `GET /v1/orgs/{org_id}/risk/methodologies/published` and scores locally with `shared/risk/RiskScoring` (the same fixed methods as the server, tested against the same fixtures) to show the level while the user rates; the server re-rates what is synced and its level counts. `risk_criteria` is organisation-owned since M13, and an app cannot set a risk's status to `ACCEPTED` (the push answers `INVALID_TRANSITION`): acceptance needs the authority for the risk's level and happens on the platform. Contract §7.6.

Since M16 the organisation's risk framework carries **criteria** (platform ADR 0031): common likelihood and consequence scales, consequence categories, a core grid and its levels, onto which each methodology maps its own scales. The published methodology comes with `criteria` (or null) and `criteria_current`. Score with `RiskScoring.rate(definition, input, criteria)`: its `level` is the common level, `nativeLevel` the methodology's own and `common` the rating on the core grid. Contract §7.7.

- **Notes:** an assessment's `rating_notes` say why each level was chosen. Where the criteria's `notes.required_from_rank` is set, a rating at or above that rank needs a note for its likelihood and for each consequence rated; ask for them, as the server refuses a push without them. Contract §7.8.
- **Estimates:** where the methodology's `assessment_mode` is `QUANTITATIVE` or `EITHER` and the criteria have frequency and value bands (`RiskScoring.quantitativeReady`), an assessment may carry an `estimate` (`{category, per_1000_years, loss, loss_low?, loss_high?}`) instead of chosen levels; `rate` gives the bands it falls into and the annual expectation, as the server does. Contract §7.9.
- **Acceptance:** acceptance stays on the platform, but an app that offers it shows `GET risk/register/{id}/rating` (the rating in words, `heightened` and `objectives`) first and sends `objectives_confirmed` with `POST risk/register/{id}/accept` when the risk threatens objectives. Contract §7.11.
