#!/usr/bin/env bash
# Диагностика 7: отправления Schanz × прибытия Bushof/Kaiserplatz по AVMSTripID.
set -u
BASE=https://efa.vrr.de/standard
dm() { # stop time dep|arr limit
  curl -sS -m 25 -G "$BASE/XML_DM_REQUEST" --data-urlencode "outputFormat=rapidJSON" --data-urlencode "type_dm=stop" --data-urlencode "name_dm=$1" \
   --data-urlencode "mode=direct" --data-urlencode "useRealtime=1" --data-urlencode "limit=$4" --data-urlencode "itdDate=20261110" --data-urlencode "itdTime=$2" \
   --data-urlencode "itdDateTimeDepArr=$3" --data-urlencode "excludedMeans=checkbox" \
   --data-urlencode "exclMOT_0=1" --data-urlencode "exclMOT_1=1" --data-urlencode "exclMOT_2=1" --data-urlencode "exclMOT_3=1" --data-urlencode "exclMOT_4=1"
}
join() { # origin dest timeDep
  echo "===== $1 → $2"
  dm "$1" "$3" dep 80 > /tmp/d.json
  dm "$2" "$3" arr 120 > /tmp/a.json
  echo "dep: $(jq '.stopEvents|length' /tmp/d.json) (bytes $(wc -c </tmp/d.json)), arr: $(jq '.stopEvents|length' /tmp/a.json) (bytes $(wc -c </tmp/a.json)); последнее отпр. $(jq -r '.stopEvents[-1].departureTimePlanned' /tmp/d.json), последнее приб. $(jq -r '.stopEvents[-1].arrivalTimePlanned' /tmp/a.json)"
  jq -n --slurpfile d /tmp/d.json --slurpfile a /tmp/a.json '
    ($a[0].stopEvents | map({key: .properties.AVMSTripID, value: .}) | from_entries) as $A
    | $d[0].stopEvents[] | select(.properties.AVMSTripID != null) | . as $x | $A[$x.properties.AVMSTripID] as $y | select($y != null)
    | select(($y.arrivalTimePlanned // "") > ($x.departureTimePlanned // ""))
    | "\($x.transportation.number)\tdep \($x.departureTimePlanned[11:16])Z est \(($x.departureTimeEstimated // "-")[11:16])\tarr \($y.arrivalTimePlanned[11:16]) est \(($y.arrivalTimeEstimated // "-")[11:16])\t\($x.location.properties.platform)\t\($x.transportation.destination.name)\tcanc=\($x.isCancelled // false) \($x.realtimeStatus // [] | join(","))"' -r
  echo "без TripID: $(jq '[.stopEvents[] | select(.properties.AVMSTripID == null)] | length' /tmp/d.json) из $(jq '.stopEvents|length' /tmp/d.json)"
}
join de:05334:1015 de:05334:1001 0735
join de:05334:1015 de:05334:1011 0735
join de:05334:1001 de:05334:1015 1600
join de:05334:1011 de:05334:1015 1600
