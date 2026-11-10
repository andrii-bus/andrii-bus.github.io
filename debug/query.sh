#!/usr/bin/env bash
# Диагностика 6: поиск маршрутов EFA VRR без пересадок Schanz → Bushof / Kaiserplatz и обратно.
set -u
BASE=https://efa.vrr.de/standard
for DEST in "Aachen Bushof" "Aachen Kaiserplatz"; do
  SF=$(curl -sS -m 20 -G "$BASE/XML_STOPFINDER_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_sf=stop" --data-urlencode "name_sf=$DEST")
  echo "$SF" | jq -r '[.locations[]? | select(.type=="stop")][0:2][] | "\(.id)\t\(.name)"'
done
trip() {
  local O=$1 D=$2 T=$3
  echo "===== $O → $D @ $T"
  R=$(curl -sS -m 30 -G "$BASE/XML_TRIP_REQUEST2" --data-urlencode "outputFormat=rapidJSON" \
     --data-urlencode "type_origin=any" --data-urlencode "name_origin=$O" --data-urlencode "type_destination=any" --data-urlencode "name_destination=$D" \
     --data-urlencode "itdDate=20261110" --data-urlencode "itdTime=$T" --data-urlencode "itdTripDateTimeDepArr=dep" \
     --data-urlencode "maxChanges=0" --data-urlencode "calcNumberOfTrips=12" --data-urlencode "useRealtime=1" \
     --data-urlencode "excludedMeans=checkbox" --data-urlencode "exclMOT_0=1" --data-urlencode "exclMOT_1=1" --data-urlencode "exclMOT_2=1" --data-urlencode "exclMOT_3=1" --data-urlencode "exclMOT_4=1" \
     --data-urlencode "useProxFootSearch=0" --data-urlencode "routeType=leasttime")
  echo "bytes ${#R}"
  echo "$R" | jq -r '.journeys[]? | [.legs[] | select(.transportation.product.class? != 99 and .transportation.product.class? != 100)] | select(length==1) | .[0] |
    "\(.transportation.number)\tdep=\(.origin.departureTimePlanned) est=\(.origin.departureTimeEstimated // "-")\tarr=\(.destination.arrivalTimePlanned) est=\(.destination.arrivalTimeEstimated // "-")\tfrom=\(.origin.name) [\(.origin.properties.platformName // .origin.properties.platform // "")]\tto=\(.destination.name)\tdir=\(.transportation.destination.name)\tstatus=\(.realtimeStatus // [] | join(","))\tid=\(.transportation.properties.globalId // "")"'
}
trip de:05334:1015 de:05334:2825 0735
trip de:05334:1015 de:05334:1000 0735
