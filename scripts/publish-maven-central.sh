#!/usr/bin/env bash
# Uploads this library to Maven Central as a signed bundle, so a client's build works while Green is off. The release
# workflow (.github/workflows/release.yml) runs it on GitHub from the released source commit, from this folder. The
# upload is AUTOMATIC: Central publishes it once it validates, and this waits for that answer, failing on FAILED, so
# the workflow tags a release only once it is on Maven Central. Central never unpublishes. The key and token travel
# in the environment only, never on a command line; MAVEN_SIGNING_KEY_ID names the signing subkey when the key is
# one exported without its primary's secret.
#   MAVEN_SIGNING_KEY=<armoured key> MAVEN_SIGNING_PASSWORD=<its password> MAVEN_SIGNING_KEY_ID=<8 hex digits> \
#   MAVEN_CENTRAL_TOKEN=<base64 of user:token> scripts/publish-maven-central.sh <version>
set -euo pipefail

VERSION="${1:?the version, such as 0.1.0}"
: "${MAVEN_SIGNING_KEY:?the armoured signing key}"
: "${MAVEN_CENTRAL_TOKEN:?the Central Portal token, base64 of user:token}"
PROJECT=.
CENTRAL=https://central.sonatype.com/api/v1/publisher
# Publishing has taken many minutes on Central's busy days; an hour is the most this waits.
POLL_SECONDS="${CENTRAL_POLL_SECONDS:-20}"
WAIT_SECONDS="${CENTRAL_WAIT_SECONDS:-3600}"
BUNDLE="$PROJECT/edge/build/central-bundle"

grep -q "^version = \"$VERSION\"$" "$PROJECT/edge/build.gradle.kts" || { echo "$PROJECT is not at version $VERSION" >&2; exit 1; }
[ -z "$(git status --porcelain -- "$PROJECT")" ] || { echo "$PROJECT has uncommitted changes" >&2; exit 1; }

rm -rf "$BUNDLE"
(cd "$PROJECT" && ./gradlew --no-daemon --no-configuration-cache test publishReleasePublicationToBundleRepository)
archive="$(mktemp -d)/edge-$VERSION.zip"
[ -n "$(find "$BUNDLE" -name '*.aar.asc')" ] || { echo "the bundle is not signed" >&2; exit 1; }
(cd "$BUNDLE" && zip -qr "$archive" ai)

# Central's API with the token in a curl config read from stdin, so it is on no command line.
central() {
  printf 'header = "Authorization: Bearer %s"\n' "$MAVEN_CENTRAL_TOKEN" | curl -sSf -K - -X POST "$@"
}

deployment="$(central "$CENTRAL/upload?name=edge-$VERSION&publishingType=AUTOMATIC" -F "bundle=@$archive")"
[[ "$deployment" =~ ^[0-9a-f-]{36}$ ]] || { echo "Central gave no deployment id: $deployment" >&2; exit 1; }
echo "Uploaded ai.matca:edge:$VERSION as deployment $deployment; Central publishes it once it validates"
waited=0
while :; do
  status="$(central "$CENTRAL/status?id=$deployment")"
  state="$(python3 -c 'import json, sys; print(json.load(sys.stdin)["deploymentState"])' <<< "$status")"
  case "$state" in
    PUBLISHED) echo "ai.matca:edge:$VERSION is published on Maven Central"; exit 0 ;;
    FAILED) echo "Central refused ai.matca:edge:$VERSION: $status" >&2; exit 1 ;;
  esac
  (( waited < WAIT_SECONDS )) || { echo "ai.matca:edge:$VERSION is still $state after $WAIT_SECONDS s; see central.sonatype.com/publishing" >&2; exit 1; }
  sleep "$POLL_SECONDS"
  waited=$((waited + POLL_SECONDS))
done
