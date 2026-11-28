#!/usr/bin/env bash
# Проверка нового правила на живых данных: Kaiserplatz → Schanz (Jakobstraße|Boxgraben)
set -u
API=https://api.transitous.org/api
T=$(date -u -d '-2 min' +%Y-%m-%dT%H:%M:00Z)
curl -sS -G "$API/v5/stoptimes" --data-urlencode "stopId=de-AVV-Aachen_de:05334:1011:1:1" --data-urlencode "time=$T" --data-urlencode "n=80" --data-urlencode "window=2700" --data-urlencode "mode=BUS" --data-urlencode "fetchStops=true" \
 | jq -r '.stopTimes[]? | . as $x | ([.nextStops[]?.name] | map(test("schanz.*(jakob|boxgraben)";"i")) | index(true)) as $i | select($i != null)
   | "\($x.routeShortName)\t\($x.place.scheduledDeparture[11:16])Z\t[\($x.place.track)]\t→ \($x.nextStops[$i].name) приб. \($x.nextStops[$i].arrival[11:16])Z\t(\((($x.nextStops[$i].arrival|fromdate) - ($x.place.departure|fromdate))/60) мин)"'
