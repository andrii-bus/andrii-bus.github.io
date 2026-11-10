#!/usr/bin/env bash
# Диагностика 2: есть ли в Transitous рейс 24 Schanz → Bushof около 07:41 (06:41Z) 10.11.
set -u
API=https://api.transitous.org/api
S=de-DELFI_de:05334:1015:1:1_G
GEO=$(curl -sS "$API/v1/geocode?type=STOP&numResults=20&place=50.7753,6.0839&text=Bushof%20Aachen")
B=$(echo "$GEO" | jq -r '[.[] | select(.name|test("bushof";"i")) | select(.id|startswith("de-"))][0].id')
echo "Bushof id: $B"
echo "===== plan Schanz → Bushof, 06:35Z"
curl -sS -G "$API/v5/plan" --data-urlencode "fromPlace=$S" --data-urlencode "toPlace=$B" --data-urlencode "time=2026-11-10T06:35:00Z" \
  --data-urlencode "maxTransfers=0" --data-urlencode "transitModes=BUS" --data-urlencode "searchWindow=2700" --data-urlencode "numItineraries=10" \
 | jq -r '.itineraries[]? | [.legs[] | select(.mode!="WALK") | "\(.routeShortName) \(.startTime)→\(.endTime) sched \(.scheduledStartTime) from \(.from.name) [\(.from.track // "")]"] | join(" + ")'
echo "===== Bushof: прибытия/отправления линии 24 06:40Z–07:10Z"
curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=$B" --data-urlencode "time=2026-11-10T06:40:00Z" --data-urlencode "n=80" \
  --data-urlencode "window=1800" --data-urlencode "mode=BUS" --data-urlencode "arriveBy=true" \
 | jq -r '.stopTimes[]? | select(.routeShortName=="24") | "24 arr=\(.place.arrival) sched=\(.place.scheduledArrival) head=\(.headsign) track=\(.place.track)"'
echo "===== Schanz, все рейсы 24 06:00Z–08:00Z (n=200)"
curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=$S" --data-urlencode "time=2026-11-10T06:00:00Z" --data-urlencode "n=200" \
  --data-urlencode "window=7200" --data-urlencode "mode=BUS" \
 | jq -r '.stopTimes[]? | select(.routeShortName=="24") | "24 dep=\(.place.departure) sched=\(.place.scheduledDeparture) track=\(.place.track) head=\(.headsign)"'
echo "===== Schanz: сколько рейсов за 45 мин с 06:30Z (n=30 vs n=200)"
for N in 30 200; do
  curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=$S" --data-urlencode "time=2026-11-10T06:30:00Z" --data-urlencode "n=$N" \
    --data-urlencode "window=2700" --data-urlencode "mode=BUS" | jq -r --arg n "$N" '"n=\($n): \(.stopTimes|length) рейсов, последний \(.stopTimes[-1].place.departure)"'
done
