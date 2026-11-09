#!/usr/bin/env bash
# Проверка APK в эмуляторе: экран, настройки, виджет, табло по расписанию, проверочное уведомление.
set -u
PKG=io.github.andriibus.labus
ACT=$PKG/.MainActivity
mkdir -p shots
shot() {
  adb exec-out screencap -p > "shots/$1.png"
  echo "снимок $1 · на экране: $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -s ' ')"
}
js() { printf '%s' "$1" | base64 -w0; }

adb install -r LA-Bus.apk || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell settings put system time_12_24 24
adb logcat -G 16M; adb logcat -c

# 1. Главный экран
adb shell am start -W -n $ACT
sleep 30; shot 1-app

# 2. Список рейсов с колокольчиками, открыт выбор «за сколько минут»
OPEN="(function go(){const k=keyOf(dirs()[0]);if(!state[k]||!state[k].trips.length)return setTimeout(go,1000);openSheet(k);setTimeout(()=>{const b=document.querySelector('#sheetList .rb');b&&b.click();},600);})()"
adb shell am start -S -W -n $ACT --es js64 "$(js "$OPEN")"
sleep 30; shot 2-reminder-pick
adb logcat -d -v time | grep -E "ActivityTaskManager|FATAL|AndroidRuntime|LABus" | tail -40 > shots/step2.txt
# 3. Предпросмотр виджета
adb shell am start -S -W -n $ACT --ez preview true
sleep 25; shot 3-widget
# переключатель на виджете: Bushof ⇄ Kaiserplatz
adb shell am broadcast -n $PKG/.Receiver -a io.github.andriibus.labus.TOGGLE
sleep 3
adb shell am start -S -W -n $ACT --ez preview true
sleep 25; shot 3b-widget-kaiserplatz

# 4. Напоминание о ближайшем рейсе (через ~1–2 мин): сначала слежение в шторке, потом «Пора выходить»
SET="(function go(){const d=dirs()[0],k=keyOf(d);if(!state[k]||!state[k].trips.length)return setTimeout(go,1000);const t=state[k].trips.find(t=>!t.cancelled&&new Date(t.dep)-Date.now()>150000);if(!t)return;const m=Math.floor((new Date(t.dep)-Date.now())/60000);LABusAndroid.setReminder(JSON.stringify({key:t.key,line:t.line,from:d.from,to:d.to,dep:new Date(t.dep).getTime(),depSched:new Date(t.depSched||t.dep).getTime(),track:t.track||'',lead:Math.max(1,m-1)}));})()"
adb shell am start -S -W -n $ACT --es js64 "$(js "$SET")"
sleep 30
adb shell input keyevent KEYCODE_HOME
adb shell cmd statusbar expand-notifications
sleep 4; shot 4a-tracking
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" | head -40 > shots/notif-tracking.txt
adb shell cmd statusbar collapse
FIRED=0
for i in $(seq 1 30); do
  if adb shell dumpsys notification --noredact | grep -q "Пора выходить"; then FIRED=1; break; fi
  sleep 10
done
adb shell cmd statusbar expand-notifications
sleep 4; shot 4b-reminder
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" | head -40 > shots/notif-scheduled.txt
adb shell cmd statusbar collapse
echo "reminder fired: $FIRED"

# 5. Кнопка «Проверить уведомление»
adb shell am start -S -W -n $ACT --ez test_notify true
sleep 25
adb shell input keyevent KEYCODE_HOME
adb shell cmd statusbar expand-notifications
sleep 4; shot 5-test
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" | head -40 > shots/notif-test.txt
adb shell cmd statusbar collapse

adb logcat -d -v time | grep -vE "chromium|cr_|WebView|Chrome" > shots/logcat-full.txt
grep -E "LABus|FATAL|AndroidRuntime" shots/logcat-full.txt | grep -v "^--" > shots/log.txt || true
cat shots/log.txt | tail -60
echo "--- notif-scheduled"; cat shots/notif-scheduled.txt
echo "--- notif-test"; cat shots/notif-test.txt
if grep -q "FATAL EXCEPTION" shots/logcat-full.txt && grep -A3 "FATAL EXCEPTION" shots/logcat-full.txt | grep -q "$PKG"; then
  echo "::error::Приложение упало"; exit 1
fi
[ "$FIRED" = "1" ] || { echo "::error::Напоминание «Пора выходить» не пришло"; exit 1; }
echo "OK"
