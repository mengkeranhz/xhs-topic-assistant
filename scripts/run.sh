#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if ! java -version 2>&1 | grep -q 'version "21'; then
  echo "JDK 21 is required. Current:" >&2; java -version >&2; exit 1;
fi
if [[ ! -f config/application-local.yml ]]; then
  cp config/application-local.example.yml config/application-local.yml
  echo "Created config/application-local.yml"
fi
if [[ -x ./mvnw ]]; then
  ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local --spring.config.additional-location=optional:file:./config/"
else
  mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local --spring.config.additional-location=optional:file:./config/"
fi
