#!/usr/bin/env bash
# Builds the LifeOS Scrobbler module from source.
# Requires: JDK 17+, Maven 3.9+.
set -euo pipefail

cd "$(dirname "$0")"

# 1. Materialise the lifeos-core stub jar if it's missing (e.g. fresh checkout).
if [ ! -f lifeos-stub/lifeos-core-stub.jar ]; then
  echo "[build] Compiling LifeOS interface stubs…"
  cd lifeos-stub
  rm -rf out lib
  mvn -q -f ../pom.xml dependency:copy-dependencies -DoutputDirectory=lib -DincludeScope=provided
  mkdir -p out
  javac --release 17 -cp "lib/*" -d out src/core/*.java
  (cd out && jar cf ../lifeos-core-stub.jar core/)
  cd ..
fi

# 2. Build the module jar.
mvn -q -DskipTests package

# 3. Refresh the dist bundle.
mkdir -p dist
cp target/scrobbler.jar dist/scrobbler.jar
( cd dist && rm -f lifeos-scrobbler-1.0.0.zip && zip -q lifeos-scrobbler-1.0.0.zip scrobbler.jar README.md example-settings.json )

echo "[build] Done. Artefacts:"
ls -la dist/
