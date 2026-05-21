#!/usr/bin/env bash
# Baut das LifeKalender-Modul aus dem Quellcode.
# Voraussetzungen: JDK 17+, Maven 3.9+.
set -euo pipefail

cd "$(dirname "$0")"

# 1. lifeos-core-Stub-Jar erzeugen, falls er fehlt (z. B. frischer Checkout).
#    Der Stub enthaelt nur die API-Signaturen der core.*-Interfaces, die der
#    LifeOS-Host zur Laufzeit bereitstellt.
if [ ! -f lifeos-stub/lifeos-core-stub.jar ]; then
  echo "[build] Kompiliere LifeOS-Interface-Stubs…"
  rm -rf lifeos-stub/out lifeos-stub/lib
  mvn -q dependency:copy-dependencies -DoutputDirectory=lifeos-stub/lib \
      -DincludeArtifactIds=javafx-graphics,javafx-base
  mkdir -p lifeos-stub/out
  javac --release 17 -cp "lifeos-stub/lib/*" -d lifeos-stub/out lifeos-stub/src/core/*.java
  (cd lifeos-stub/out && jar cf ../lifeos-core-stub.jar core/)
  rm -rf lifeos-stub/out lifeos-stub/lib
fi

# 2. Modul-Jar bauen.
mvn -q -DskipTests package

echo "[build] Fertig."
