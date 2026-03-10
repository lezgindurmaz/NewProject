#!/bin/bash
# TOKEN should be set as an environment variable
REPO="lezgindurmaz/NewProject"
VERSION="v2.0-DroidSU"

echo "Building DroidSU APK..."
./gradlew assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"

if [ -f "$APK_PATH" ]; then
    echo "Uploading DroidSU to GitHub Releases..."
    RELEASE_ID=$(curl -s -H "Authorization: token $TOKEN" \
        -d "{\"tag_name\": \"$VERSION\", \"title\": \"DroidSU Release $VERSION\", \"body\": \"Yeni nesil KernelPatch tabanlı root yönetimi DroidSU. Modüller hazır gelir.\", \"draft\": false, \"prerelease\": false}" \
        "https://api.github.com/repos/$REPO/releases" | grep -m 1 "id" | tr -cd '[:digit:]')

    curl -H "Authorization: token $TOKEN" \
        -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary @"$APK_PATH" \
        "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=DroidSU-Manager.apk"
    echo "Release complete!"
else
    echo "Build failed, APK not found."
fi
