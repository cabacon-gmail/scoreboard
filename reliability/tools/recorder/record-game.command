#!/bin/sh
# Starts CRG and the game recorder together (macOS: double-click). Devices use http://<this laptop>:8000/ as usual.
# Put this file and crg-recorder.jar in the CRG folder, next to scoreboard-mac.command. Close the window to stop.
cd "$(dirname "$0")" || exit 1
JAVA=java
if [ -n "${JAVA_HOME}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA="${JAVA_HOME}/bin/java"
elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 11+ >/dev/null 2>&1; then
  JAVA="$(/usr/libexec/java_home -v 11+)/bin/java"
fi
exec "$JAVA" -jar crg-recorder.jar --crg=lib/crg-scoreboard.jar "$@"
