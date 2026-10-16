# Template changelog

What changed in the template, newest first, and what an app made from an earlier version has to do to take each change. See [keeping-current.md](keeping-current.md).

## 0.1.0 — first version

Derived from the Mobile Auditor at commit `f384bf9`.

- **Platform connection**: API client with bearer tokens and rotating refresh, sign-in, sign-out, `GET /v1/me`, health check, AI drafting call.
- **Local-first**: works without an account (local workspace); encrypted SQLDelight/SQLCipher database; sync outbox; per-user settings.
- **Security**: app lock with the phone's fingerprint, face or PIN, mandatory by an app-defined rule; hidden from Recents while in use; test mode in debug builds.
- **UI**: theme (navy/teal, Atkinson Hyperlegible, light and dark), building blocks (`AppScreen`, `SectionCard`, `LabeledValue`, `Badge`, `PrimaryActionButton`, `EmptyState`, `FormScreen`, `FormField`, date/time/time-zone pickers), welcome, sign-in and Status screens.
- **Example feature**: Items (table, repository, tests, list and form screens), to copy and then delete.
- **Tooling**: `scripts/new-app.sh`, `mobile/run-on-phone.sh`.

Left out of the template on purpose (audit-specific in the Mobile Auditor, port if an app needs them): on-device speech recognition (whisper.cpp), voice dictation, evidence capture (camera, files), the touch lock and pocket mode, stealth mode, report generation (DOCX), client import from spreadsheets, and the clients/contacts/sites records.
