#!/usr/bin/env bash
# Диагностика 3: есть ли рейс 24 в 07:41 в данных Deutsche Bahn (db.transport.rest).
set -u
for BASE in https://v6.db.transport.rest https://v5.db.transport.rest; do
  echo "===== $BASE"
  L=$(curl -sS -m 20 -G "$BASE/locations" --data-urlencode "query=Schanz (Jakobstraße), Aachen" --data-urlencode "results=5" --data-urlencode "addresses=false" --data-urlencode "poi=false")
  echo "$L" | jq -r '.[]? | "\(.id)\t\(.name)"' | head -5
  ID=$(echo "$L" | jq -r '[.[]? | select(.name|test("Schanz";"i"))][0].id // empty')
  [ -z "$ID" ] && { echo "нет ответа: $(echo "$L" | head -c 300)"; continue; }
  D=$(curl -sS -m 25 -G "$BASE/stops/$ID/departures" --data-urlencode "when=2026-11-10T07:30:00+01:00" --data-urlencode "duration=60" \
      --data-urlencode "results=100" --data-urlencode "bus=true")
  echo "$D" | jq -r '(.departures // .)[]? | select(.line.name|test("24|4$")) | "\(.line.name)\tplan=\(.plannedWhen)\twhen=\(.when)\tpl=\(.platform // .plannedPlatform)\tdir=\(.direction)"' | head -30
  break
done
