#!/usr/bin/env bash
set -u
API=https://api.transitous.org/api
T=$(date -u -d '-5 min' +%Y-%m-%dT%H:%M:00Z)
GEO=$(curl -sS "$API/v1/geocode?type=STOP&numResults=20&place=50.7753,6.0839&text=Schanz%20(Jakobstra%C3%9Fe)%20Aachen")
echo "$GEO" | jq -r '.[]|select(.name|test("schanz";"i"))|"\(.id)\t\(.name)"'
ID=$(echo "$GEO" | jq -r '[.[]|select(.name|test("schanz.*jakob";"i"))|select(.id|startswith("de-"))][0].id')
echo "===== stoptimes $ID с $T: линии 3*/13*/4 с Kaiserplatz по пути"
curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=$ID" --data-urlencode "time=$T" --data-urlencode "n=80" --data-urlencode "window=2700" --data-urlencode "mode=BUS" --data-urlencode "fetchStops=true" \
 | jq -r '.stopTimes[]? | select(.routeShortName|test("^(3|13|4)")) | "\(.routeShortName)\t\(.place.scheduledDeparture[11:16])Z\tplace=\(.place.name) [\(.place.track)] stopId=\(.place.stopId)\t→ \(.headsign)\tKP:\([.nextStops[]?.name|select(test("Kaiserplatz"))]|length)"'
