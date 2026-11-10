#!/usr/bin/env bash
# Диагностика 4: рейс 24 в 07:41 в справочной NRW (EFA VRR) + CORS.
set -u
for BASE in https://efa.vrr.de/standard https://efa.vrr.de/vrr https://openservice-test.vrr.de/static02; do
  echo "===== $BASE"
  SF=$(curl -sS -m 20 -G "$BASE/XML_STOPFINDER_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_sf=any" \
       --data-urlencode "name_sf=Aachen Schanz (Jakobstraße)" --data-urlencode "coordOutputFormat=WGS84[dd.ddddd]" -D /tmp/h.txt)
  grep -i "access-control-allow-origin" /tmp/h.txt || echo "(нет CORS-заголовка)"
  echo "$SF" | jq -r '.locations[]? | "\(.id)\t\(.name)\t\(.type)"' | head -4
  ID=$(echo "$SF" | jq -r '[.locations[]? | select(.type=="stop")][0].id // empty')
  [ -z "$ID" ] && { echo "нет: $(echo "$SF" | head -c 200)"; continue; }
  DM=$(curl -sS -m 25 -G "$BASE/XML_DM_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_dm=stop" --data-urlencode "name_dm=$ID" \
     --data-urlencode "mode=direct" --data-urlencode "useRealtime=1" --data-urlencode "limit=60" \
     --data-urlencode "itdDate=20261110" --data-urlencode "itdTime=0730" --data-urlencode "itdDateTimeDepArr=dep" -D /tmp/h2.txt)
  grep -i "access-control-allow-origin" /tmp/h2.txt || echo "(нет CORS у DM)"
  echo "$DM" | jq -r '.stopEvents[]? | select(.transportation.number|test("^(24|4)$")) | "\(.transportation.number)\tplan=\(.departureTimePlanned)\test=\(.departureTimeEstimated // "-")\tpl=\(.location.properties.platform // .location.disassembledName // "")\tto=\(.transportation.destination.name)"' | head -20
  break
done
