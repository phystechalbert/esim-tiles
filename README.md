# eSIM Tiles

One-tap Quick Settings tiles that switch which stored eSIM sits in your phone's second SIM slot.
Your main SIM (data and calls) stays on.

Made for people who keep several home numbers on eSIMs just to receive SMS codes from banks and
government services, and are tired of going through Settings → SIMs every time.

> **Open test.** Works on a Pixel 9 Pro XL with Android 17. Other phones are unknown: please tell
> us how it went on yours (see [Help test](#help-test)).

## How it works

Android can hold many eSIM profiles but keeps only one or two of them on. Turning a profile on
normally takes six or more taps in Settings. eSIM Tiles does the same with one tap:

- you pick your **main SIM**, which the app never turns off;
- you pick up to **six eSIMs** for the tiles; each gets its own tile in Quick Settings;
- a tap on a tile turns that eSIM on in place of the one in the second slot (about 15 seconds);
- after the switch, data, calls and texts go back to the SIMs they were on.

The system calls that switch eSIMs are reserved for system apps and ADB. The app uses them through
[Shizuku](https://shizuku.rikka.app/), which lends apps the rights of a USB-debugging session
without root.

## What the app does not do

- It never deletes, downloads or edits an eSIM profile. It only turns stored ones on.
- It has no internet permission and does not read your messages.
- It refuses to switch when the result is unclear: no main SIM picked, more than one extra SIM on,
  a physical SIM in the second slot, during a call, or if the switch would hit the main SIM.

## Requirements

- Android 14 or newer, with eSIM.
- [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api), started via
  Wireless debugging. It stops after every reboot; Shizuku 13.6+ can start itself on a trusted Wi-Fi.
- Two SIMs on at the same time: either a physical SIM plus an eSIM, or two eSIMs (MEP).

## Install

1. Install Shizuku and start it.
2. Download `esim-tiles-<version>.apk` from [Releases](../../releases) and install it.
3. Open eSIM Tiles, allow Shizuku, pick the main SIM and the eSIMs for the tiles.
4. Tap **Add tiles to Quick Settings**.

## Risks

Switching turns off the eSIM that is in the second slot: calls and texts to that number won't
reach you until you switch back. The app relies on hidden Android interfaces; a system update or
another phone maker's firmware may break it or make it behave differently. Know how to turn a SIM
on by hand in Settings → SIMs before you rely on the tiles while travelling.

## Help test

Open an [issue](../../issues) with:

- phone model and Android version;
- main SIM physical or eSIM; how many eSIMs are stored;
- did the switch work, how long it took, any error text.

| Phone | Android | Setup | Result |
|---|---|---|---|
| Pixel 9 Pro XL | 17 | eSIM + eSIM (MEP) | works |

## Build

JDK 17+, Android SDK with platform 37.

```
./gradlew assembleRelease
```

Release signing reads `.signing/keystore.properties`; without it the release build is signed with
the debug key.

## Credits

[Shizuku API](https://github.com/RikkaApps/Shizuku-API) (Apache 2.0),
[AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache 2.0).

## License

MIT

---

## По-русски

Плитки в шторке, которые одним тапом ставят нужную eSIM во второй слот. Основная симка с
интернетом и звонками не трогается. Для тех, кто держит домашние номера на eSIM ради SMS-кодов от
банков и Госуслуг.

Нужны Android 14+, eSIM и [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
Приложение только включает уже сохранённые профили: ничего не удаляет и не скачивает, интернета у
него нет, SMS не читает. Проверено на Pixel 9 Pro XL с Android 17; про другие телефоны пока
неизвестно, поэтому расскажи в [issues](../../issues), как оно у тебя.
