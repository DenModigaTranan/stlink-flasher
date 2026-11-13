#!/usr/bin/env python3
"""Turn a fresh `flutter create ... app` project into the ST-Link flasher app.

Run from the repository root after `flutter create`. Copies the Kotlin plugin, USB device
filter and Dart files into ./app, registers the plugin in MainActivity, and patches the
manifest. Fails loudly if the generated scaffold doesn't look as expected, so a Flutter
template change shows up as a clear error instead of a broken app.
"""
import glob
import os
import re
import shutil
import sys

ROOT = os.getcwd()
APP = os.path.join(ROOT, "app")


def fail(msg):
    sys.exit("assemble_app: " + msg)


def copy_into(files, dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    for f in files:
        shutil.copy(f, dest_dir)


# ---- Kotlin plugin + device filter ----
kt_files = sorted(glob.glob(os.path.join(ROOT, "android", "*.kt")))
if not kt_files:
    fail("no Kotlin files found in ./android")
copy_into(kt_files, os.path.join(APP, "android/app/src/main/kotlin/com/example/stlinkflasher"))

filt = os.path.join(ROOT, "android/res/xml/device_filter.xml")
if not os.path.exists(filt):
    fail("device_filter.xml missing")
copy_into([filt], os.path.join(APP, "android/app/src/main/res/xml"))

# ---- MainActivity: register the plugin ----
mains = glob.glob(os.path.join(APP, "android/app/src/main/kotlin/**/MainActivity.kt"), recursive=True)
if len(mains) != 1:
    fail("expected exactly one MainActivity.kt, found %d" % len(mains))
pkg = re.search(r"^package\s+([\w.]+)", open(mains[0]).read(), re.M)
if not pkg:
    fail("no package line in MainActivity.kt")
with open(mains[0], "w") as f:
    f.write(
        "package %s\n\n"
        "import com.example.stlinkflasher.StLinkFlasherPlugin\n"
        "import io.flutter.embedding.android.FlutterActivity\n"
        "import io.flutter.embedding.engine.FlutterEngine\n\n"
        "class MainActivity : FlutterActivity() {\n"
        "    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {\n"
        "        super.configureFlutterEngine(flutterEngine)\n"
        "        flutterEngine.plugins.add(StLinkFlasherPlugin())\n"
        "    }\n"
        "}\n" % pkg.group(1)
    )

# ---- AndroidManifest: USB host feature + attach filter ----
manifest_path = os.path.join(APP, "android/app/src/main/AndroidManifest.xml")
m = open(manifest_path).read()
if "device_filter" not in m:
    if m.count("<application") != 1 or m.count("</activity>") != 1:
        fail("unexpected AndroidManifest.xml layout (need one <application> and one <activity>)")
    m = m.replace(
        "<application",
        '<uses-feature android:name="android.hardware.usb.host" android:required="false" />\n    <application',
        1,
    )
    m = m.replace(
        "</activity>",
        '    <intent-filter>\n'
        '                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />\n'
        '            </intent-filter>\n'
        '            <meta-data\n'
        '                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"\n'
        '                android:resource="@xml/device_filter" />\n'
        '        </activity>',
        1,
    )
    open(manifest_path, "w").write(m)

# ---- Dart ----
dart_files = sorted(glob.glob(os.path.join(ROOT, "lib", "*.dart")))
if not dart_files:
    fail("no Dart files found in ./lib")
copy_into(dart_files, os.path.join(APP, "lib"))
with open(os.path.join(APP, "lib/main.dart"), "w") as f:
    f.write(
        "import 'package:flutter/material.dart';\n"
        "import 'flasher_screen.dart';\n\n"
        "void main() => runApp(const MaterialApp(home: FlasherScreen()));\n"
    )

print("assemble_app: ok (package %s)" % pkg.group(1))
