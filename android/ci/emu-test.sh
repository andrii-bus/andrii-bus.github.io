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

# 2. Панель уведомлений
adb shell am start -S -W -n $ACT --es js64 "$(js 'openNotif()')"
sleep 25; shot 2-settings
adb logcat -d -v time | grep -E "ActivityTaskManager|ActivityManager|FATAL|AndroidRuntime|LABus|crash|Crash|Render|chromium.*(Error|FATAL)|Kill|kill|died|ANR" | tail -80 > shots/step2.txt

# 3. Предпросмотр виджета
adb shell am start -S -W -n $ACT --ez preview true
sleep 25; shot 3-widget

# 4. Окно «утро» прямо сейчас (по времени Аахена) → табло должно запуститься само, через будильник
FROM=$(TZ=Europe/Berlin date -d '-2 min' +%H:%M)
TO=$(TZ=Europe/Berlin date -d '+25 min' +%H:%M)
SET="LABusAndroid.saveSettings(JSON.stringify({morning:{on:true,from:'$FROM',to:'$TO',days:[1,2,3,4,5,6,7]},evening:{on:false,from:'16:30',to:'17:30',days:[1,2,3,4,5]},walkHome:4,walkWork:4,leave:true}))"
adb shell am start -S -W -n $ACT --es js64 "$(js "$SET")"
sleep 40
adb shell input keyevent KEYCODE_HOME
adb shell cmd statusbar expand-notifications
sleep 4; shot 4-scheduled
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" | head -40 > shots/notif-scheduled.txt
adb shell dumpsys alarm | grep -B1 -A4 "$PKG" | head -60 > shots/alarms.txt
adb shell cmd statusbar collapse

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
echo "--- alarms"; cat shots/alarms.txt
if grep -q "FATAL EXCEPTION" shots/logcat-full.txt && grep -A3 "FATAL EXCEPTION" shots/logcat-full.txt | grep -q "$PKG"; then
  echo "::error::Приложение упало"; exit 1
fi
grep -q "pkg=$PKG" shots/notif-scheduled.txt || { echo "::error::Табло по расписанию не появилось"; exit 1; }
echo "OK"
