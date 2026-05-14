#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :aegis-core:backend:bootRun
