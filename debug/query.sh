#!/usr/bin/env bash
set -u
API=https://api.transitous.org/api
T=$(date -u -d '-5 min' +%Y-%m-%dT%H:%M:00Z)
echo "===== Kaiserplatz ids"
curl -sS "$API/v1/geocode?type=STOP&numResults=20&place=50.7753,6.0839&text=Kaiserplatz%20Aachen" | jq -r '.[]|select(.name|test("kaiserplatz";"i"))|"\(.id)\t\(.name)"'
for ID in $(curl -sS "$API/v1/geocode?type=STOP&numResults=20&place=50.7753,6.0839&text=Kaiserplatz%20Aachen" | jq -r '.[]|select(.name|test("kaiserplatz";"i"))|.id' | head -3); do
  echo "===== Transitous $ID с $T (все линии, с упоминанием Schanz)"
  curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=$ID" --data-urlencode "time=$T" --data-urlencode "n=80" --data-urlencode "window=3600" --data-urlencode "mode=BUS" --data-urlencode "fetchStops=true" \
  | jq -r '.stopTimes[]? | "\(.routeShortName)\t\(.place.scheduledDeparture[11:16])Z\t\(.place.name) [\(.place.track)]\t→ \(.headsign)\tschanz: \([.nextStops[]?.name|select(test("schanz";"i"))]|join(","))\t| \([.nextStops[]?.name][0:6]|join(" > "))"'
done
echo "===== Все остановки со словом Schanz"
curl -sS "$API/v1/geocode?type=STOP&numResults=30&place=50.7753,6.0839&text=Schanz%20Aachen" | jq -r '.[]|"\(.id)\t\(.name)"'
echo "===== EFA Kaiserplatz, линии 13*"
TT=$(TZ=Europe/Berlin date -d '-5 min' +%H%M); DD=$(TZ=Europe/Berlin date +%Y%m%d)
curl -sS -m 25 -G "https://efa.vrr.de/standard/XML_DM_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_dm=stop" --data-urlencode "name_dm=de:05334:1011" \
  --data-urlencode "mode=direct" --data-urlencode "useRealtime=1" --data-urlencode "limit=80" --data-urlencode "itdDate=$DD" --data-urlencode "itdTime=$TT" --data-urlencode "itdDateTimeDepArr=dep" \
  --data-urlencode "excludedMeans=checkbox" --data-urlencode "exclMOT_0=1" --data-urlencode "exclMOT_1=1" --data-urlencode "exclMOT_2=1" --data-urlencode "exclMOT_3=1" --data-urlencode "exclMOT_4=1" \
 | jq -r '.stopEvents[]? | select(.transportation.number|test("^13")) | "\(.transportation.number) plan=\(.departureTimePlanned[11:16])Z est=\((.departureTimeEstimated//"-")[11:16]) pl=\(.location.properties.platform) dest=\(.transportation.destination.name)"'
