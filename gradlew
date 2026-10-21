#!/bin/sh
# Minimal local launcher. Run `gradle wrapper --gradle-version 8.13` to generate the OFFICIAL wrapper JAR.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath "$ROOT/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle -p "$ROOT" "$@"
fi
printf '%s\n' 'Gradle 8.13 is required but not installed. Open in Android Studio or install Gradle 8.13, then run `gradle wrapper --gradle-version 8.13`.' >&2
exit 1
