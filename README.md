# stlink-flasher
# ST-Link flasher for Android (Flutter)

Talks to an ST-Link V2 clone over USB OTG and flashes STM32F1-style controllers (STM32F103 / GD32F103) via SWD. No desktop tools involved.

## Files

| File | Purpose |
|---|---|
| `android/StLink.kt` | ST-Link USB protocol (bulk transfers, SWD, memory + core control) |
| `android/Stm32F1.kt` | Flash layer: info, backup, erase, program, verify, RDP removal |
| `android/RamLoader.kt` | Runs a 76-byte Thumb routine from SRAM to program flash (much faster than word-by-word over SWD) |
| `tools/flashloader.S` | Source of that routine |
| `tools/build_loader.py` | Assembles it with `llvm-mc` and prints the Kotlin array for `RamLoader.kt` |
| `tools/test_flashloader.py` | Runs the assembled routine in a Thumb emulator against a flash-controller model (success, PGERR, write-protect, empty, watchdog) |
| `android/StLinkFlasherPlugin.kt` | Flutter MethodChannel/EventChannel bridge, USB permission, background thread |
| `android/res/xml/device_filter.xml` | USB IDs for ST-Link variants |
| `lib/stlink_flasher.dart` | Dart API |
| `lib/flasher_screen.dart` | Ready-made screen: connect, backup, pick firmware, flash, remove read protection (needs `file_picker: ^8.0.0`) |

## Building without Android Studio (GitHub builds the APK)

1. Create a new private repository on github.com and upload everything in this folder to it (drag the files in, including the hidden-looking `.github` folder), or `git push` it.
2. Open the repo's **Actions** tab, choose **Build APK**, then **Run workflow** (it also runs on every push to `main`). Allow a few minutes.
3. Open the finished run, download the **stlink-flasher-apk** artifact (a zip containing `app-debug.apk`), unzip it and copy the APK to the phone. Allow "install unknown apps" when asked.
4. If the build fails, open the failed step and send me the red error lines. The first build of never-compiled code will likely need a small fix or two.

The workflow generates a fresh Flutter Android project, runs `ci/assemble_app.py` to add the plugin, USB filter, manifest entries and screen, then builds. You don't need Flutter or Android Studio installed locally.

## Integrating into ScooterHub

1. Copy the three `.kt` files into `android/app/src/main/kotlin/com/example/stlinkflasher/` (keep the package name, or change it in all three), and `device_filter.xml` into `android/app/src/main/res/xml/`.
2. Copy `lib/stlink_flasher.dart` into your app's `lib/`.
3. `AndroidManifest.xml`:
   ```xml
   <uses-feature android:name="android.hardware.usb.host" android:required="false" />
   <!-- inside the main <activity> -->
   <intent-filter>
       <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
   </intent-filter>
   <meta-data
       android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
       android:resource="@xml/device_filter" />
   ```
4. `MainActivity.kt`:
   ```kotlin
   override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
       super.configureFlutterEngine(flutterEngine)
       flutterEngine.plugins.add(com.example.stlinkflasher.StLinkFlasherPlugin())
   }
   ```
5. Make sure `androidx.core:core-ktx` is available (it is in a default Flutter project).

## Usage

```dart
await StLinkFlasher.connect();                      // connect(underReset: true) if firmware disables SWD
final info = await StLinkFlasher.targetInfo();      // devId, flash size, RDP state
final backup = await StLinkFlasher.backup();        // ALWAYS do this first
StLinkFlasher.progress.listen((p) => print('${p.stage} ${p.percent}%'));
await StLinkFlasher.flash(firmwareBytes);           // erase -> write -> verify -> reset
await StLinkFlasher.disconnect();
```

## UI screen

Push `FlasherScreen()` from anywhere in ScooterHub. The flow is backup first: Flash stays disabled until a backup has been saved, or you tick the "flash without a backup" box. A read-protected chip only offers the (destructive) read-protection removal.

## First run on real hardware

Do these in order, on a spare board (a blue pill is ideal) before the scooter:

1. `connect()`, then **Run diagnostics** in the screen (or `StLinkFlasher.diagnose()`). It never writes flash and reports PASS/FAIL per layer: SWD answer, core halt, chip ID, SRAM read/write, core-register access, flash read, RAM-loader self-test, reset. The first FAIL tells you which layer to debug; send me that line.
2. `backup()` and compare it with a known dump.
3. Flash a small known-good image with verify on.
4. Only then try the scooter, with a backup saved first.

If "RAM loader self-test" fails but everything above it passes, flashing still works, just through the slow path.

## How the RAM loader works

1. `flash()` halts the core, unlocks flash and copies the stub to `0x20000000`, then runs it on zero bytes as a self-test.
2. After erasing, each 4 KB block of the image goes to an SRAM buffer at `0x20000100`. The core is started with interrupts masked and runs the stub, which programs halfwords, waits for BSY, checks PGERR/WRPRTERR and ends in BKPT with R0 = 0 or the failing status.
3. Blocks that are all `0xFF` are skipped. Watchdogs are frozen while the core is halted (DBGMCU) and the stub also kicks the IWDG.

The stub uses SRAM `0x20000000-0x20001200` (fits the 6 KB F103 parts). It clobbers the firmware's RAM, which is fine since the chip is reset at the end. After changing `flashloader.S`, run `python3 tools/build_loader.py`, paste the array into `RamLoader.kt`, and run `python3 tools/test_flashloader.py`.

## Wiring

ST-Link `SWDIO`, `SWCLK`, `GND` to the controller's SWD pads. Add `NRST` to the controller's reset line if you want "connect under reset". Power the scooter normally and do not also feed it from the ST-Link 3.3V pin. Clones are 3.3V logic.

## Limitations (read before relying on it)

- **Not tested on hardware.** It was written against the open-source stlink protocol and the STM32F1 reference manual; expect to debug the first real connection (USB endpoints and clone quirks are the usual culprits).
- **Connect-under-reset needs NRST wired.** If the firmware remaps or disables the SWD pins and NRST isn't connected, the chip won't answer. The under-reset path (drive NRST low, attach, catch the reset vector, release) is written but, like everything here, untested on hardware.
- **RAM loader is untested on real silicon.** The stub is assembled with llvm-mc and passes an emulator test suite, but the emulator is my own model of the STM32F1 flash controller. Core-register writes, running from SRAM and BKPT halt detection through a clone ST-Link are the parts only hardware can confirm. Before erasing, `flash()` self-tests the loader and silently falls back to the slow direct-write path if it fails (`lastWriteUsedLoader` tells you which was used).
- **ST-Link V2 (your clone) is the target.** The V2-1 and V3 product IDs are in the USB filter, but V3 probes may need extra commands I haven't implemented, so treat them as untested.
- **F1-only.** Targets with another DBGMCU ID are refused on purpose. AT32/MM32/F0/F3 etc. need their own register maps.
- **Read protection.** If RDP is set, backup is impossible and removing it mass-erases the stock firmware. The option-byte reload needs a power cycle.
- A raw-image sanity check (stack pointer + reset vector) blocks encrypted or wrong files by default. Many vendor update packages are encrypted and will be rejected; that is the check working.
- Keep the phone charged and the OTG cable firm. A flash interrupted partway leaves the controller unbootable until reflashed over SWD.
