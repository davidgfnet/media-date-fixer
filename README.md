# Media Date Fixer

Simple Android app that fixes the dates of photos and videos in bulk, e.g. WhatsApp media whose
mtime was reset by a backup restore. It ships some advance mode for power users.

This was mostly coded using LLMs

## Build

Requires a JDK 17+ and an Android SDK with `platforms;android-36` and `build-tools;36.0.0`.

```sh
make ANDROID_SDK=$HOME/android-sdk          # -> mediadatefixer.apk (signed with a local debug.keystore)
make test                                   # core unit tests on the JVM (no SDK needed)
make ANDROID_SDK=$HOME/android-sdk run      # adb install + launch
make ANDROID_SDK=$HOME/android-sdk unsigned # -> build/unsigned.apk
```

## Icons and other art

All icons are vector drawables created by an LLM using some other elements and media as reference.
Icons and widgets have used "Phosphor Icons" as well as "Google Material Icons" as reference
(MIT and Apache licensed respectively).


