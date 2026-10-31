#!/bin/bash
# Signature/manifest sanity check + Android lint (run via docker like build.ps1).
cd /project
BT=/opt/android-sdk/build-tools/35.0.0
$BT/apksigner verify --print-certs HomeDesk.apk | head -2
$BT/aapt2 dump badging HomeDesk.apk | head -3
gradle --no-daemon -q lintRelease >/dev/null 2>&1
grep -E 'severity="(Error|Fatal|Warning)"' -A3 app/build/reports/lint-results-release.xml | grep -E ' id=| message=' | sed 's/^ *//' | head -60
