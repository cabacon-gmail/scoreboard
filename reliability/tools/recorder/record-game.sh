#!/bin/sh
# Starts CRG and the game recorder together (Linux). Devices use http://<this laptop>:8000/ as usual.
# Put this file and crg-recorder.jar in the CRG folder, next to scoreboard.sh. Ctrl-C stops both.
cd "$(dirname "$0")" || exit 1
JAVA=java
if [ -n "${JAVA_HOME}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then JAVA="${JAVA_HOME}/bin/java"; fi
exec "$JAVA" -jar crg-recorder.jar --crg=lib/crg-scoreboard.jar "$@"
