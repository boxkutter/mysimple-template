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

## What changes (platform milestone M1)

Identity moves from email/password to **Zitadel** with OIDC authorization code + PKCE in the system browser (no Play services), tokens kept in the Keystore-wrapped session store, `GET /v1/me` returning memberships, and `X-Org-Id` on every call for multi-organisation users. The template's `AuthApi`/`AuthRepository` are updated when that lands (template changelog). Until then `/v1/auth/login|refresh|logout` work as before.

## What a new app needs from the server

See contract §7: register the app's entity names and a `SyncPolicy` per entity, add AI tasks and a system prompt, add reference-data modules as needed. After any server change, refresh `api/openapi.yaml` in `iso` and mirror the wire types in the app's `shared` module.
