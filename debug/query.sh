#!/usr/bin/env bash
# Разовая диагностика: что Transitous вернул для Schanz (Jakobstraße) утром 10.11.
set -u
API=https://api.transitous.org/api
GEO=$(curl -sS "$API/v1/geocode?type=STOP&numResults=20&place=50.7753,6.0839&text=Schanz%20(Jakobstra%C3%9Fe)%20Aachen")
echo "$GEO" | jq -r '.[] | select(.name|test("schanz";"i")) | "\(.id)\t\(.name)\t\([.areas[]?.name]|join(","))"'
for ID in $(echo "$GEO" | jq -r '.[] | select(.name|test("schanz.*jakob";"i")) | .id'); do
  echo "===== stopId $ID"
  for V in v5 v4 v3 v1; do
    R=$(curl -sS -G "$API/$V/stoptimes" --data-urlencode "stopId=$ID" --data-urlencode "time=2026-11-10T06:30:00Z" \
         --data-urlencode "n=30" --data-urlencode "window=2700" --data-urlencode "mode=BUS" --data-urlencode "fetchStops=true")
    if echo "$R" | jq -e '.stopTimes' >/dev/null 2>&1; then echo "api $V"; break; fi
  done
  echo "$R" | jq -r '.stopTimes[] | "\(.routeShortName)\tdep=\(.place.departure)\tsched=\(.place.scheduledDeparture)\tname=\(.place.name)\ttrack=\(.place.track)\tpdt=\(.pickupDropoffType // "-")\tcanc=\(.cancelled // false)/\(.tripCancelled // false)\trt=\(.realTime)\thead=\(.headsign)\tnext=\([.nextStops[]?.name][0:12]|join(" > "))"'
done
