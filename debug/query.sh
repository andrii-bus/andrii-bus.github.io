#!/usr/bin/env bash
set -u
API=https://api.transitous.org/api
S=de-DELFI_de:05334:1015:1:1_G
T=$(date -u -d '-2 min' +%Y-%m-%dT%H:%M:00Z)
echo "===== Transitous Schanz, линия 4, с $T"
for ID in $(curl -sS "$API/v1/geocode?type=STOP&numResults=20&place=50.7753,6.0839&text=Schanz%20(Jakobstra%C3%9Fe)%20Aachen" | jq -r '.[]|select(.name|test("schanz.*jakob";"i"))|.id'); do
  echo "-- stop $ID"
  curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=$ID" --data-urlencode "time=$T" --data-urlencode "n=80" --data-urlencode "window=3600" --data-urlencode "mode=BUS" --data-urlencode "fetchStops=true" \
  | jq -r '.stopTimes[]? | select(.routeShortName=="4") | "4 sched=\(.place.scheduledDeparture[11:16]) dep=\(.place.departure[11:16]) track=\(.place.track) head=\(.headsign) next=\([.nextStops[]?.name][0:14]|join(" > "))"'
done
echo "===== EFA Schanz, линия 4"
TT=$(TZ=Europe/Berlin date -d '-2 min' +%H%M); DD=$(TZ=Europe/Berlin date +%Y%m%d)
curl -sS -m 25 -G "https://efa.vrr.de/standard/XML_DM_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_dm=stop" --data-urlencode "name_dm=de:05334:1015" \
  --data-urlencode "mode=direct" --data-urlencode "useRealtime=1" --data-urlencode "limit=60" --data-urlencode "itdDate=$DD" --data-urlencode "itdTime=$TT" --data-urlencode "itdDateTimeDepArr=dep" \
  --data-urlencode "excludedMeans=checkbox" --data-urlencode "exclMOT_0=1" --data-urlencode "exclMOT_1=1" --data-urlencode "exclMOT_2=1" --data-urlencode "exclMOT_3=1" --data-urlencode "exclMOT_4=1" \
 | jq -r '.stopEvents[]? | select(.transportation.number=="4") | "4 plan=\(.departureTimePlanned[11:16])Z est=\((.departureTimeEstimated//"-")[11:16]) pl=\(.location.properties.platform) dest=\(.transportation.destination.name)"'
