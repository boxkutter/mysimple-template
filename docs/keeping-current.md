# Keeping the template and the apps current

The template is the shared starting point of the platform's apps. It stays useful only if improvements flow both ways.

```text
   Mobile Auditor ──┐                         ┌──► new app (copy at a template commit)
                    ├─► improvements ─► template
   Risk Assessor ───┘   (generic ones)        └──► existing apps take updates they want
```

## What belongs in the template

Things **every** app of the platform needs, done the same way:

- connecting to the platform: API client, sign-in and session handling, the account on the Status page;
- local-first storage: the encrypted database, the outbox, settings;
- security: the app lock, encrypted storage, test mode;
- the look: theme, font, building blocks (cards, forms, pickers, buttons);
- the build: Gradle setup, versions, scripts;
- the docs on the above.

Not in the template: anything about one app's domain (audits, risk assessments), even if a second app might want something similar. When two apps really need the same domain feature (for example clients, contacts and sites), move it into the template on purpose, as its own change.

## Giving an improvement back

When work on an app improves something generic (a fix in the session refresh, a better form field, a dependency upgrade):

1. Make the change in the template, written generically (no app names), with its docs.
2. Add an entry to [template-changelog.md](template-changelog.md): what changed, and what an app has to do to take it.
3. Check it still builds and the shared tests pass.

Rename back as you copy: the template uses placeholder names (the package, the class prefix, the slug and the display name; `scripts/new-app.sh` lists them), so code copied from an app needs the app's names replaced with the template's.

## Taking an update into an app

Each app records the template commit it was made from, or last updated to, in `.template-version`.

1. Read the template changelog from that commit on.
2. Bring in the changes the app wants. Usually by hand: the files are the template's with the app's names. For a bigger update, `git diff <old>..<new>` in the template repository shows exactly what to apply.
3. Update `.template-version` to the template commit you are now level with.

## Where the template came from

The template was derived from the Mobile Auditor at commit `f384bf9` (*Branding: app icon, launch screen and logo*). The Mobile Auditor is still the reference for how the platform works; when it changes something generic, bring it into the template the same way.
