# The platform: how the app connects

Every app made from this template works the same way as the **Mobile Auditor** and talks to the **same server**. This page describes that contract from the app's side. The server itself lives in the Mobile Auditor repository (`boxkutter/mobileauditor`: `server/`, `deploy/`, and the API contract `api/openapi.yaml`). Its specification is in that repository's `docs/specification/`, chiefly *11 Platform Architecture*, *12 Data Model* and *13 Security*.

| | |
|---|---|
| **Server** | `https://mobileauditor.h03.eu` (Python/FastAPI behind the owner's reverse proxy, Docker Compose) |
| **Build setting** | `-Pplatform.baseUrl=https://…` overrides it, e.g. for a local server: `-Pplatform.baseUrl=http://192.168.1.20:8710` |
| **Code** | `mobile/shared/.../ApiClient.kt` (all calls), `AuthApi.kt` / `AiApi.kt` (wire types) |

## 1. Local-first, account optional

The app is **complete without the server**. On first start the user gives their name and organisation and starts working (`WelcomeScreen`): a *local workspace* whose session has no tokens (`Session.isLocal`). Nothing is sent anywhere.

Signing in to an account is optional (Status → *Sign in to an account*). It adds the server's services: backup and sync between devices (once built, §5), AI drafting (§6), and later sharing. Signing out returns to the local workspace, whose data stays on the phone.

**One account works in every app of the platform.** The same user and organisation sign in to the Mobile Auditor and to an app made from this template.

## 2. Identity

| Call | What it does |
|---|---|
| `POST /v1/auth/login` `{email, password, device_name}` | Returns the user, their organisation, an access token (15 min) and a refresh token |
| `POST /v1/auth/refresh` `{refresh_token}` | Swaps the refresh token for new tokens. **Single use**: the server rotates it. A session unused for 30 days expires (also the longest an app can stay offline and still refresh) |
| `POST /v1/auth/logout` `{refresh_token}` | Ends the session on the server |
| `GET /v1/me` | The signed-in user and organisation; used by the Status page to prove the token works |
| `GET /health` | `{status, version, environment}`; no token needed |

How the app handles it (`ApiClient`, `AuthRepository`, `SessionHolder`):

- Requests carry `Authorization: Bearer <access token>`. On a 401 the client refreshes once and retries. The rotated tokens are saved at once.
- If the refresh is refused (401), the server has ended the session: the device is signed out (back to the local workspace, if there is one).
- **Network errors never sign the user out.** The app keeps working offline with the stored session.
- The session is stored encrypted with a non-exportable Android Keystore key, in `noBackupFilesDir` (`EncryptedSessionStore`).
- Errors come as RFC 9457 problem details; `ApiException.detail` carries the server's explanation.

## 3. Organisations (tenancy)

Everything a user creates belongs to their organisation: the `Session.organisationId` (`Tenant`). Every synced table has an `owner_org_id`, and every query is scoped by it. A different user or organisation on the same phone gets its own repositories (`App.kt` keys on user and organisation).

## 4. Data on the phone

- **SQLDelight + SQLCipher**: the whole database is encrypted. Its random key is stored only in wrapped form, unwrapped with a Keystore key (`EncryptedDatabase`). Database and keys live in no-backup storage, and `android:allowBackup="false"`.
- **Ids are UUIDv7, generated on the phone**, so records created offline need no server round-trip.
- **Synced-table conventions** (see `Item.sq`): `id`, `owner_org_id`, `version` (0 = not yet accepted by the server), `created_at` / `updated_at` / `deleted_at` (UTC epoch ms; deletion is soft).
- **The app lock** uses the phone's own fingerprint, face or PIN (platform `BiometricPrompt`, no Play services). It becomes mandatory once the app stores information that needs protecting (the rule is in `App.kt`).

## 5. Sync

Every local write also queues an **operation in the outbox** (`outbox_operation`), in the same transaction, so the database and the outbox never disagree:

```text
UI ─► Local DB (source of truth on device) ─► Outbox (ordered operations)
                                                    │  when online
                                                    ▼
                                        POST /v1/sync  (batch)
        ◄── accepted ops · server changes since cursor · conflicts · new cursor
```

An operation is `{op_id (idempotency key), entity_type, entity_id, op_type CREATE|UPDATE|DELETE, payload, base_version}`. A CREATE carries all fields, an UPDATE only the changed ones, a DELETE none. Field names in the payload are the server's column names.

**Status:** the server's `POST /v1/sync` is **not built yet** (Mobile Auditor, Stage 3). Until then the outbox only fills up, so nothing is lost and the full history is there when sync arrives. The Status page shows the number of waiting changes.

**Entity names are shared by every app on the server.** Name each synced table after what it is, specifically enough that another app will not want the same name for something else: `risk_assessment`, `hazard`, `control_measure`, not `item` or `record`. Records the platform already has (organisations, clients, contacts, sites) should use the Mobile Auditor's names and fields, so the apps share them.

## 6. AI drafting

`POST /v1/ai/draft` `{task, context, current?}` → `{text?, model?, reason?}`. The server decides whether AI is switched on for the organisation; without an account the app never calls it (`AiDrafting`). A draft is always a suggestion that the user reviews and edits; AI never creates evidence or makes decisions.

**The server knows only the Mobile Auditor's tasks so far** (`executive_summary`, `objective`, `activities`, `follow_up`, `nonconformity`, `observation`), and its system prompt is written for audit reports. A new app's own tasks need a change on the server first (`server/app/modules/ai/routes.py` in the Mobile Auditor repository): add the tasks, and give the app its own system prompt. Until then the server answers with `reason = "Unknown drafting task: …"`, which the app shows.

## 7. What a new app needs from the server

Most apps can start with no server change at all: local-first, sign-in and the Status page work as they are. When the app grows:

| Need | Server change (Mobile Auditor repository) |
|---|---|
| Sync of the app's records | The `/v1/sync` endpoint (planned), plus tables for the app's entities (named as in §5) |
| AI drafting | Tasks and a system prompt for the app (§6) |
| App-specific reference data (like the auditor's ISO criteria) | A module with read endpoints, as `server/app/modules/criteria/` |

Keep the API contract in one place: after a server change, refresh `api/openapi.yaml` there (`uv run python -m app.cli export-openapi`) and mirror the wire types in the app's `shared` module.

## 8. Reaching the server from a phone

- Android 17+ needs the *local network* permission to reach a LAN address (the server may resolve to one at home). `MainActivity` asks for it and re-checks the connection when it is granted.
- The Status page's **Connection** card shows the DNS answer, the server's health, the signed-in account and the time taken: the first place to look when something does not connect.
