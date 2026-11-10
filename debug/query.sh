#!/usr/bin/env bash
# Диагностика 5: следующие остановки, прибытие, платформа, отмены в EFA VRR.
set -u
BASE=https://efa.vrr.de/standard
DM=$(curl -sS -m 25 -G "$BASE/XML_DM_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_dm=stop" --data-urlencode "name_dm=de:05334:1015" \
   --data-urlencode "mode=direct" --data-urlencode "useRealtime=1" --data-urlencode "limit=40" --data-urlencode "includeCompleteStopSeq=1" \
   --data-urlencode "itdDate=20261110" --data-urlencode "itdTime=0735" --data-urlencode "itdDateTimeDepArr=dep" --data-urlencode "excludedMeans=checkbox" \
   --data-urlencode "exclMOT_0=1" --data-urlencode "exclMOT_1=1" --data-urlencode "exclMOT_2=1" --data-urlencode "exclMOT_3=1" --data-urlencode "exclMOT_4=1")
echo "bytes: ${#DM}"
echo "$DM" | jq '.stopEvents[0] | del(.previousLocations) | .onwardLocations |= (.[0:2])' | head -90
echo "===== кратко"
echo "$DM" | jq -r '.stopEvents[]? | "\(.transportation.number)\t\(.departureTimePlanned)\t\(.departureTimeEstimated // "-")\t\(.location.properties.platformName // .location.properties.platform // "?")\tstatus=\(.realtimeStatus // [] | join(","))\tcanc=\(.isCancelled // false)\tonward=\([.onwardLocations[]?.name] | map(select(test("Bushof|Kaiserplatz"))) | join(","))\tarrB=\([.onwardLocations[]? | select(.name|test("Bushof")) | .arrivalTimeEstimated // .arrivalTimePlanned][0] // "-")"' | head -30
