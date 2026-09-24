#!/usr/bin/env bash
# Dipanggil di dalam android-emulator-runner. $1 = path APK.
set -u
PKG=dev.retrobit.assistant
adb install -r "$1" || { echo "::error title=Uji asap::adb install gagal"; exit 1; }
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.READ_CONTACTS
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 10
python3 .github/scripts/smoke_ui.py
