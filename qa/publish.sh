#!/bin/zsh
# Publish a new 1VIEW build: builds both editions, uploads a GitHub release and points
# version.json at it so installed sticks offer the update.
#
#   qa/publish.sh 1.1.2 "What changed, one line"
#
# Before running: bump versionCode and versionName in app/build.gradle.kts (versionCode must
# go up by at least one; version.json carries that number). Needs `gh auth login` done once.
set -euo pipefail
NAME=${1:?version name, e.g. 1.1.2}
NOTES=${2:-"1VIEW $NAME"}
REPO=marcday92-cpu/1VIEW
cd "$(dirname "$0")/.."
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 GRADLE_USER_HOME=$PWD/.gradle-home
CODE=$(grep -E '^\s*versionCode = ' app/build.gradle.kts | grep -oE '[0-9]+')
grep -q "versionName = \"$NAME\"" app/build.gradle.kts || { echo "app/build.gradle.kts versionName is not $NAME"; exit 1; }
echo "Building $NAME (code $CODE)"
./gradlew --console=plain -q testDeetvDebugUnitTest assembleDeetvRelease assembleBrowserRelease
STAGE=$(mktemp -d)
cp app/build/outputs/apk/browser/release/*.apk "$STAGE/1VIEW-Browser.apk"
cp app/build/outputs/apk/deetv/release/*.apk "$STAGE/1VIEW-DeeTV.apk"
mkdir -p dist && cp app/build/outputs/apk/*/release/*.apk dist/
(cd "$STAGE" && shasum -a 256 1VIEW-Browser.apk 1VIEW-DeeTV.apk > SHA256SUMS.txt)
gh release create "v$NAME" "$STAGE/1VIEW-Browser.apk" "$STAGE/1VIEW-DeeTV.apk" "$STAGE/SHA256SUMS.txt" \
  --repo "$REPO" --title "1VIEW $NAME" --notes "$NOTES"
CLONE=$(mktemp -d)
gh repo clone "$REPO" "$CLONE" -- -q
cat > "$CLONE/version.json" <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "apk": {
    "browser": "https://github.com/$REPO/releases/download/v$NAME/1VIEW-Browser.apk",
    "deetv": "https://github.com/$REPO/releases/download/v$NAME/1VIEW-DeeTV.apk"
  },
  "notes": "$NOTES"
}
JSON
(cd "$CLONE" && git -c user.name=marcday92-cpu -c user.email=marcday92-cpu@users.noreply.github.com commit -qam "version.json: $NAME (code $CODE)" && git push -q origin HEAD)
echo "Published https://github.com/$REPO/releases/tag/v$NAME; sticks will offer $NAME within about five minutes."
