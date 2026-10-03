# Wi-Fi Guardian 🛡️📶

**Is your Pixel's Wi-Fi randomly refusing to turn on, or dropping by itself — sometimes taking Bluetooth with it?**
This free, open-source app won't repair your phone, but it may give you your Wi-Fi back. Here is the path that got there.

*[Leer en español](README.es.md)*

---

## The problem

On some Pixel phones (found on a **Pixel 8 Pro**, likely others with the same Broadcom Wi-Fi/Bluetooth chip) the Wi-Fi chip randomly stops responding:

- Wi-Fi shows as on, but the phone says **"Wi-Fi is disabled"** and can't scan.
- Or it works for a while and then **drops by itself** and won't come back until you restart.
- Bluetooth sometimes crashes in a loop at the same time.
- Android may even keep **rolling back Google Play system updates** and restarting, because it thinks the updates broke Bluetooth.

Factory images, OTA sideloads, network resets and clearing app data did not fix it.

## What we found

We pulled bugreports and kernel logs. Every failure looks the same:

```
Link is not up, try count: 10, linksts: DETECT QUIET
pcie link up fail
hang reason: PCIE_RC_LINK_UP_FAIL
```

The processor talks to the Wi-Fi chip over a **PCIe link**, and that link fails to come up. Two key findings:

1. **It fails when the chip wakes up.** The Wi-Fi driver puts the chip to sleep after only **0.5 s without traffic** (`DHD Idle state!! idletime: 5, wdtick: 100`), so the link is torn down and re-established **thousands of times per hour**. A faulty link eventually fails at one of those wake-ups.
2. **Heat makes it much worse.** Our app logged battery temperature at every failure:

| Boot / event | Result | Battery temp |
|---|---|---|
| 13:07 | ❌ Wi-Fi did not start | 34.1 °C |
| 13:22 | ❌ Started and dropped | 31.8 °C |
| 13:41 | ❌ Did not start | 30.8 °C |
| 11:32 (in use) | ❌ Dropped | ~33.8 °C |
| 11:57 | ✅ Worked | 28.0 °C |
| 13:57 | ✅ Worked | 25.1 °C |

Above ~30 °C it failed, below ~28 °C it worked. That points to a heat-sensitive physical connection (for example a cracked solder joint) — a **hardware** fault. The phone does not need to feel hot: 31 °C feels "normal" in your hand.

## What the app does

**🛡️ It protects actively — it is not just a monitor.**
It sends a tiny 1-byte packet to your router every **0.2 seconds**, only over Wi-Fi. The chip is never idle for 0.5 s, so the driver never puts it to sleep, and the fragile link never has to be re-established — which is exactly where it fails. Think of keeping an engine idling because you know the starter motor is weak.

- It does **not** modify the chip, the driver or the system. **No root needed.**
- Traffic only goes to your local router (or `8.8.8.8` if no router address is known), never over mobile data.
- When Wi-Fi is not connected, it does nothing.

**📊 It watches and warns.**
- Detects when the chip goes down (including at boot) and notifies you.
- Logs temperature and tells you **when the phone has cooled down enough to restart**.
- Starts automatically when the phone turns on.

## If your Wi-Fi is already down

1. **Turn the phone off completely** (not restart).
2. **Let it cool down for 15–20 minutes**: out of its case, unplugged, in a cool place or in front of a fan.
   ⚠️ **Never put it in the freezer or the fridge** — condensation forms inside the phone when you take it out and can kill it.
3. **Turn it on, open Wi-Fi Guardian and turn on Wi-Fi.**
4. Keep using it normally; the app keeps the chip awake and warns you if it drops again.

Tips: avoid heavy use while charging (charging heats the phone), and take off a thick case if you have one.

## Install

1. Download `WifiGuardian-x.y.z.apk` from **[Releases](../../releases)**.
2. Open it on your phone and allow installing apps from that source when Android asks.
3. Open the app, allow notifications and tap **Allow** on the background card.

Requires Android 14 or newer. Tested on a Pixel 8 Pro with Android 17.

### Settings

- **Protect with screen off** — keeps protecting while the phone is in your pocket, at the cost of extra battery use. Off by default.
- **Temperature limits** — defaults are 28 °C (safe to restart) and 30 °C (risk zone), measured on one Pixel 8 Pro. Adjust them for your phone.

### Optional: detailed diagnostics (ADB)

If you want the app to also save Android's internal Wi-Fi logs (useful for bug reports):

```
adb shell pm grant io.github.tournique80.wifiguardian android.permission.READ_LOGS
adb shell pm grant io.github.tournique80.wifiguardian android.permission.DUMP
```

Android asks for confirmation ("allow this time") and removes these grants on every reboot. Logs are saved in `/sdcard/Android/data/io.github.tournique80.wifiguardian/files/`.

## Honest limits

- This is a **workaround for a hardware problem, not a repair**. If the link is too damaged, nothing in software will help.
- It cannot prevent a failure **at power-on** (the link has to be established at least once); the cool-down steps above help there.
- Keeping the chip awake uses some extra battery.
- Only tested on one phone so far. Please share your results in the [Issues](../../issues) — they help everyone.

## Build from source

No Gradle needed. With JDK 17 and the Android SDK (build-tools 36, platform android-36):

```
JAVA_HOME=... BUILD_TOOLS=.../build-tools/36.0.0 ANDROID_JAR=.../platforms/android-36/android.jar ./build.sh
```

## License

MIT — free to use, share and improve.

---

Made with ❤️ in Chile 🇨🇱 · by **Totihue**
