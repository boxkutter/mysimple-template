# Template changelog

What changed in the template, newest first, and what an app made from an earlier version has to do to take each change. See [keeping-current.md](keeping-current.md).

## 0.1.5 — the risk engine with the framework's criteria (platform M16)

- **New in `RiskScoring.kt`:** the organisation's risk criteria (platform ADR 0031) and estimates (ADR 0033), ported from `iso/server/app/modules/risk_framework/scoring.py`. Everything is additive: `score` is unchanged, and an app that ignores the rest keeps working; the server's level stays authoritative.
  - `RiskScoring.rate(definition, input, criteria)`: scores with the methodology and, when `criteria` is given, on the criteria's core grid too. The result's `level` is then the common level, the methodology's own kept as `nativeLevel`, and `common` holds the common likelihood, consequence (and each category's), score and level. Opportunities stay on the methodology's own levels. Without criteria it gives what `score` gives.
  - An input's `estimate` (`{category, per_1000_years, loss, loss_low?, loss_high?}`) stands in for the likelihood and that category's level: the bands it falls into. `Rating.estimate` records them with `annualExpectation` (and `_low`, `_high`): loss × rate ÷ 1000, rounded down, in the category's unit. Amounts are `Long`. Codes: `QUALITATIVE_ONLY` (the methodology is rated by choosing levels, or an opportunity), `NO_BANDS`, `UNKNOWN_CATEGORY` (no value bands in this methodology), `MISSING_INPUT`.
  - `criteriaDefinition(criteria)` (the core grid as a definition), `validateCriteria(criteria, complete, enabled)` (3 to 10 levels numbered from 1, `SCALE_SIZE`; categories and descriptors; band checks, `INVALID_BANDS`; with `complete`, every likelihood level and every category that is always on or switched on by an `enabled` capability described), `bandedCategories`, `quantitativeReady`.
  - `checkCompatible(definition, criteria)`: a methodology fits its criteria when its `mapping` takes every native level onto a common one and never goes down, and its categories are the criteria's. Codes `UNMAPPED_LEVEL`, `NON_MONOTONIC_MAPPING`, `CATEGORY_NOT_IN_FRAMEWORK`, and for a methodology that allows estimates, `NOT_QUANTITATIVE` and `NO_BANDS`.
  - `validate` checks the new definition keys `assessment_mode` (`QUALITATIVE`, `QUANTITATIVE`, `EITHER`), `depth` (`QUICK`, `DETAILED`), `reduction_policy` (`NONE`, `AS_FAR_AS_POSSIBLE`) and `significant_from_rank`.
- **Fixtures:** the copy is re-synced from iso and gains `criteria_rebased`, `criteria_fmea_mapped`, `criteria_threat_vulnerability`, `criteria_impact_over_time`, `criteria_4x6`, `criteria_quantitative` and `invalid_criteria`. `RiskScoringFixturesTest` now rates every case with `rate` and the fixture's criteria (checking `native_level`, `common_*`, the estimate's bands and annual expectation), refuses every invalid, incomplete and incompatible set of criteria with the server's code, and fails on any part of a fixture it does not run.
- **Contract (platform M16, contract §7.7 to §7.12), notes for apps:**
  - New synced fields: `risk.origin`; `risk_assessment.rating_notes` and `estimate`; on `risk_treatment` the writable `justification`, `justification_note`, `estimated_cost`, `effort_days`, `expected_likelihood` and `expected_consequence`, and the server-owned `expected_level_label`, `expected_level_rank` and `control_set_*` fields; on `nonconformity` `risk_review`, `risk_review_note` and `recurrence_likelihood`; on `context_issue` and `party_requirement` `no_risk_reason`.
  - `risk/methodologies/published` now carries `criteria` and `criteria_current`: score locally with the criteria when present.
  - Ask for notes where the criteria's `notes.required_from_rank` requires them; the server refuses a push without them.
  - Offer an estimate only when the methodology's `assessment_mode` allows it and the criteria have bands (`RiskScoring.quantitativeReady`).
  - When accepting a risk, show `GET risk/register/{id}/rating` (with `heightened` and `objectives`) and send `objectives_confirmed`.
- To take it: copy `RiskScoring.kt`, `RiskScoringFixturesTest.kt` and the fixtures directory (or re-sync it with `iso/scripts/risk-fixtures-manifest.sh --sync`). Callers of `score` need no change; call `rate` with the published methodology's `criteria` to show the common level.

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
