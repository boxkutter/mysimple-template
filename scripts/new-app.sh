#!/usr/bin/env bash
# Creates a new app from this template: copies it and renames everything that names the template.
#
#   scripts/new-app.sh --name "Risk Assessor" --dest ../riskassessor [--slug riskassessor] [--package app.riskassessor]
#
#   --name     The app's name as people see it (launcher, welcome screen, app lock).
#   --dest     Where to create the new app (must not exist yet).
#   --slug     Lower-case letters and digits; names the Gradle project, the database file and the
#              Keystore keys. Default: the name in lower case without spaces.
#   --package  Kotlin package and Android application id. Default: app.<slug>.
#
# The template's own git history is not copied. The new app records the template commit it came
# from in .template-version (see docs/keeping-current.md).
set -euo pipefail

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

name="" dest="" slug="" package=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --name) name=${2:-}; shift 2 ;;
        --dest) dest=${2:-}; shift 2 ;;
        --slug) slug=${2:-}; shift 2 ;;
        --package) package=${2:-}; shift 2 ;;
        -h|--help) usage ;;
        *) echo "Unknown argument: $1" >&2; usage ;;
    esac
done
[[ -n $name && -n $dest ]] || usage

slug=${slug:-$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')}
package=${package:-app.$slug}
# "Risk Assessor" → "RiskAssessor": the prefix of the app's classes (application, database)
prefix=$(printf '%s' "$name" | sed 's/[^A-Za-z0-9]/ /g' | awk '{ for (i = 1; i <= NF; i++) printf "%s%s", toupper(substr($i, 1, 1)), substr($i, 2) }')

[[ $slug =~ ^[a-z][a-z0-9]*$ ]] || { echo "--slug must be lower-case letters and digits, starting with a letter: '$slug'" >&2; exit 1; }
[[ $package =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || { echo "--package must look like app.riskassessor: '$package'" >&2; exit 1; }
[[ $name =~ ^[A-Za-z][A-Za-z0-9\ -]*$ && $prefix =~ ^[A-Z][A-Za-z0-9]*$ ]] ||
    { echo "--name must start with a letter and use only letters, digits, spaces and hyphens: '$name'" >&2; exit 1; }
[[ ! -e $dest ]] || { echo "$dest already exists" >&2; exit 1; }

template=$(cd "$(dirname "$0")/.." && pwd)
template_commit=$(git -C "$template" rev-parse --short HEAD 2>/dev/null || echo unknown)

# Copy without git history, build output or local settings
mkdir -p "$dest"
dest=$(cd "$dest" && pwd)
tar -C "$template" \
    --exclude=.git --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=.idea \
    --exclude=local.properties --exclude='*.iml' --exclude=./scripts/new-app.sh \
    -cf - . | tar -C "$dest" -xf -
rmdir "$dest/scripts" 2>/dev/null || true # this script belongs to the template

# Text replacements, most specific first. Escapes the replacement for sed.
esc() { printf '%s' "$1" | sed 's/[\/&|]/\\&/g'; }
package_path=${package//./\/}
find "$dest" -type f ! -path '*/gradle/wrapper/*' -print0 |
    while IFS= read -r -d '' file; do
        grep -Iq . "$file" || continue # skip binary files (fonts, images)
        sed -i \
            -e "s|app\.simpletemplate|$(esc "$package")|g" \
            -e "s|app/simpletemplate|$(esc "$package_path")|g" \
            -e "s|SimpleTemplate|$(esc "$prefix")|g" \
            -e "s|simpletemplate|$(esc "$slug")|g" \
            -e "s|Simple Template|$(esc "$name")|g" \
            "$file"
    done

# Move the package directories (Kotlin sources and SQLDelight files)
find "$dest" -type d -path '*/app/simpletemplate' -prune -print0 |
    while IFS= read -r -d '' dir; do
        root=${dir%/app/simpletemplate}
        mkdir -p "$root/$package_path"
        # Move the contents, then remove the old directories if they are now empty
        (shopt -s dotglob; mv "$dir"/* "$root/$package_path"/)
        rmdir "$dir" 2>/dev/null || true
        rmdir "$root/app" 2>/dev/null || true
    done
find "$dest" -name 'SimpleTemplateApplication.kt' -execdir mv {} "${prefix}Application.kt" \;

# The new app's own README (the template's README describes the template)
cat > "$dest/README.md" <<README
# $name

A mobile-first app of the platform, created from the mysimple-template (template commit \`$template_commit\`).

- **How it connects to the platform:** [docs/platform.md](docs/platform.md)
- **How the code is organised, and how to add a feature:** [docs/conventions.md](docs/conventions.md)
- **Finishing the set-up of a new app:** [docs/new-app.md](docs/new-app.md)
- **Taking template updates, and giving improvements back:** [docs/keeping-current.md](docs/keeping-current.md)

## Getting started

Requires the Android SDK (\`sdk.dir\` in \`mobile/local.properties\`).

\`\`\`bash
cd mobile
./gradlew :androidApp:assembleDebug     # → androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:testAndroidHostTest   # shared-module unit tests
./run-on-phone.sh                       # build, install and launch on a phone (wireless debugging)
\`\`\`

The platform's addresses and this app's sign-in client are build settings (\`mobile/local.properties\` or \`-P…\`): see [docs/platform.md](docs/platform.md#build-settings).
README
printf '%s\n' "$template_commit" > "$dest/.template-version"

left=$(grep -rIl --exclude-dir=.git -e simpletemplate -e SimpleTemplate -e 'Simple Template' "$dest" || true)
echo "Created $name in $dest"
echo "  package $package · slug $slug · classes ${prefix}… · from template $template_commit"
[[ -z $left ]] || { echo "Still naming the template (check by hand):"; echo "$left" | sed 's/^/  /'; }
echo "  sign-in redirect eu.mysimple.$slug:/oidc/callback (register it on the platform: docs/platform.md, \"What a new app needs\")"
echo "Next: docs/new-app.md (branding, the app's own records and their names on the platform, git repository)."
