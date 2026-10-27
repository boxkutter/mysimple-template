# The platform: how the app connects

Every app made from this template works the same way as the **Mobile Auditor** and talks to the **same server**: the **My Simple ISO platform**, whose home is [`boxkutter/iso`](https://github.com/boxkutter/iso). The authoritative description of the contract from the app's side is that repository's [`docs/specification/platform-contract.md`](https://github.com/boxkutter/iso/blob/main/docs/specification/platform-contract.md); this page is a summary and points there for every detail.

| | |
|---|---|
| **Server** | `https://mobileauditor.h03.eu` until the platform's own domain exists (build setting `-Pplatform.baseUrl=https://…` overrides it) |
| **Server code and contract** | `boxkutter/iso`: `server/`, `api/openapi.yaml`, `docs/specification/platform-contract.md` |
| **App code** | `mobile/shared/.../ApiClient.kt` (all calls), `AuthApi.kt` / `AiApi.kt` (wire types) |

## What stays the same

- **Local-first, account optional** (contract §1): the app is complete without the server; nothing is sent before sign-in.
- **Organisations** (§3): every synced row carries **`owner_org_id`** and every query is scoped by it; on the server, Postgres row-level security enforces the same boundary.
- **Data on the phone** (§4): UUIDv7 ids generated on the device; `version` 0 until accepted; epoch-millisecond timestamps; soft deletes; SQLCipher; the app lock.
- **Sync** (§5): every write queues `{op_id, entity_type, entity_id, op_type, payload, base_version}` in the outbox; `POST /v1/sync` returns accepted ops, results, server changes, wipe instructions and a new cursor. Entity names are global; name tables specifically (`risk_assessment`, not `item`) and reuse the platform's names for organisations, contacts, sites and processes (`auditable_unit`). The 37 reserved names are listed in the contract.
- **AI drafting** (§6): `POST /v1/ai/draft {task, context, current?}`; a draft is always a suggestion; never without an account.

## What changed on the server (platform M1–M2, 25 Oct 2026) and is pending in the apps

- **Identity** (§2): the server now authenticates with **Zitadel** only. OIDC authorization code + PKCE in the system browser (no Play services), discovery by email (`POST /v1/auth/discover`), tokens kept in the Keystore-wrapped session store, `GET /v1/me` returning memberships, and `X-Org-Id` on every call. The password endpoints are gone, so an app on this template cannot sign in until its `AuthApi`/`AuthRepository` are rewritten (template changelog will say which version carries it). Local workspaces attach by **adopt** (`POST /v1/orgs` with the local organisation id) or **remap** (§3.1).
- **Sync** (§5): `POST /v1/sync` is live with `X-Org-Id` and `X-Device-Id`, result statuses `ACCEPTED, DUPLICATE_ID, CONFLICT, EDIT_WINS, INVALID_TRANSITION, RETRY_LATER, REJECTED`, a change feed with tombstones, an opaque cursor per organisation and a `wipe` list the device must honour and acknowledge (`POST /v1/sync/wipe-ack`).
- **Attachments** (§5.2): `POST /v1/files/uploads` → pre-signed part URLs → `complete`; the server verifies the SHA-256, scans and encrypts; `file_object` arrives through the feed and evidence rows are linked by hash.
- **AI drafting** (§6): opt-in per organisation; `reason` says why there is no draft.

## What a new app needs from the server

See contract §7: register the app's entity names and a `SyncPolicy` per entity, add AI tasks and a system prompt, add reference-data modules as needed. After any server change, refresh `api/openapi.yaml` in `iso` and mirror the wire types in the app's `shared` module.

## Risk methodologies (platform M13)

An organisation rates risks with its own methodologies (platform ADR 0022, ADR 0030). In an organisation workspace an app reads them read-only from `GET /v1/orgs/{org_id}/risk/methodologies/published` and scores locally with `shared/risk/RiskScoring` (the same fixed methods as the server, tested against the same fixtures) to show the level while the user rates; the server re-rates what is synced and its level counts. `risk_criteria` is organisation-owned since M13, and an app cannot set a risk's status to `ACCEPTED` (the push answers `INVALID_TRANSITION`): acceptance needs the authority for the risk's level and happens on the platform. Contract §7.6.
