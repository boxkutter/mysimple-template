# Starting a new app from the template

## 1. Create it

From the template repository:

```bash
scripts/new-app.sh --name "Risk Assessor" --dest ../riskassessor
# optional: --slug riskassessor --package app.riskassessor
```

The script copies the template (without its git history), renames the package, application id, classes, database file, Keystore keys and the app's name, and records the template commit it came from in `.template-version`. It lists anything that still names the template.

Then create the app's own GitHub repository and push the new folder to it.

## 2. Make it the app's own

Work through this list in the new app's repository (this file is copied there too):

- [ ] **What the app is for**: the one sentence on the welcome screen (`ui/WelcomeScreen.kt`), and a short product description in `README.md`. A `docs/specification/` like the Mobile Auditor's pays off as soon as the app is more than a few screens.
- [ ] **Branding**: replace the placeholder mark, icon and logo (`branding/README.md` lists every file). Keep the platform's navy unless the app needs its own colour.
- [ ] **The app's own records**: replace the example **Items** with them ([conventions.md, "Adding a feature"](conventions.md#adding-a-feature)), then delete Items: `Item.sq`, `domain/Items.kt`, `ItemRepository.kt`, `ItemRepositoryTest.kt`, `ui/items/`, the `ITEM` entity type, the routes and tab in `AppShell.kt`, `items` in `Repositories`.
- [ ] **When the app lock becomes mandatory**: the rule in `App.kt` (the example: once any item is stored).
- [ ] **Bottom tabs**: the app's main areas in `AppShell.kt`, Status last.
- [ ] **Permissions**: only those the app needs, each with a comment (`androidApp/src/main/AndroidManifest.xml`).
- [ ] **Synced entity names**: specific to the app, as described in [platform.md, "Sync"](platform.md#sync-contract-5). The example `item` is not a platform entity.
- [ ] **Sign-in**: the app's redirect scheme is `eu.mysimple.<slug>` (`oidcScheme` in `androidApp/build.gradle.kts`); register its native client on the platform and set `<slug>.oidcClientId` and the `platform.*` settings in `mobile/local.properties` ([platform.md, "Build settings"](platform.md#build-settings)).
- [ ] **Server needs**: the native client, the app's entities with a `SyncPolicy` each, AI drafting tasks and reference data are changes in the platform repository, `boxkutter/iso` ([platform.md, "What a new app needs from the server"](platform.md#what-a-new-app-needs-from-the-server)).
- [ ] **Version**: `versionCode` / `versionName` in `androidApp/build.gradle.kts`.
- [ ] **Before the first release**: freeze the database schema ([conventions.md, "Database changes"](conventions.md#database-changes)).

## 3. Check it runs

```bash
cd mobile
./gradlew :shared:testAndroidHostTest
./run-on-phone.sh
```

On the phone: start without an account, add an item (or the app's first record), and check the Status page: *Local database ok*, and the Connection card reaching the server. Then sign in with a platform account (the sign-in page opens in the browser) and check the Connection card shows *signed in as …*, the organisation workspaces are listed, and the sync card shows what was sent (the example `item` comes back *Not accepted*: it is not a platform entity).
