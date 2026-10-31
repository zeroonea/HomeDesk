# Builds HomeDesk.apk in Docker (no Java / Android Studio needed on Windows).
# First run downloads the Android SDK (~1 GB) into the Docker volume homedesk-android-sdk.
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
docker run --rm `
    -v "${project}:/project" `
    -v homedesk-android-sdk:/opt/android-sdk `
    -v homedesk-gradle:/root/.gradle `
    -e GRADLE_USER_HOME=/root/.gradle `
    gradle:8.11.1-jdk17 bash /project/docker/build.sh
if ($LASTEXITCODE -ne 0) { throw "Build failed" }
Get-Item (Join-Path $project 'HomeDesk.apk')
