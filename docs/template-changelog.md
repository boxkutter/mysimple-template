# Template changelog

What changed in the template, newest first, and what an app made from an earlier version has to do to take each change. See [keeping-current.md](keeping-current.md).

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
