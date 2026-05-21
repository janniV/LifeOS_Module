#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f lifeos-stub/lifeos-core-stub.jar ]; then
  echo "[build] Kompiliere LifeOS-Interface-Stubs..."
  rm -rf lifeos-stub/out lifeos-stub/lib
  mvn -q dependency:copy-dependencies -DoutputDirectory=lifeos-stub/lib \
      -DincludeArtifactIds=javafx-graphics,javafx-base
  mkdir -p lifeos-stub/out
  javac --release 17 -cp "lifeos-stub/lib/*" -d lifeos-stub/out lifeos-stub/src/core/*.java
  (cd lifeos-stub/out && jar cf ../lifeos-core-stub.jar core/)
  rm -rf lifeos-stub/out lifeos-stub/lib
fi
mvn -q -DskipTests package
echo "[build] Fertig."
