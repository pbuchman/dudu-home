#!/usr/bin/env python3
"""Capture the app gallery on a dedicated, disposable Android emulator."""

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.pbuchman.duduhome"
APP_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
STANDARD_CAPTURES = (
    "home-speed.png",
    "street-two-line.png",
    "street-marquee-a.png",
    "street-marquee-b.png",
    "street-full-after-tap.png",
)
SPEED_CAPTURES = ("speed-running.png", "speed-result.png")
DIMENSIONS = (2000, 1200)
DENSITY = 240
LOCALE = "pl-PL"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def app_source_sha256():
    sources = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--",
         "app", "build.gradle", "settings.gradle", "gradle.properties", "gradle", "gradlew",
         "scripts/capture-readme.py", "scripts/check-speed-emulator.py", "scripts/speed_https_fixture.py"],
        cwd=ROOT,
    )
    digest = hashlib.sha256()
    for filename in sorted(set(sources.split(b"\0")) - {b""}):
        path = ROOT / os.fsdecode(filename)
        if path.is_file():
            digest.update(filename + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def version_information(package_details):
    return {
        "version": re.search(r"\bversionName=(\S+)", package_details)[1],
        "version_code": int(re.search(r"\bversionCode=(\d+)", package_details)[1]),
    }


def png_dimensions(path):
    with path.open("rb") as capture:
        header = capture.read(24)
    if header[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR":
        raise RuntimeError(f"{path.name} is not a PNG capture")
    return struct.unpack(">II", header[16:24])


def capture_speed(serial, env, version, target):
    with tempfile.TemporaryDirectory(prefix="dudu-gallery-speed-") as directory:
        subprocess.run([sys.executable, ROOT / "scripts/check-speed-emulator.py", serial, directory,
                        "--scenario", "success", "--locale", LOCALE, "--gallery-label"],
                       cwd=ROOT, env=env, check=True)
        fixture = Path(directory) / "success"
        fixture_version = version_information((fixture / "installed-version.txt").read_text())
        if fixture_version != version or (fixture / "application-locale.txt").read_text().strip() != LOCALE:
            raise RuntimeError("fixture metadata do not match the standard gallery build")
        for name in SPEED_CAPTURES:
            (target / name).write_bytes((fixture / f"speed-qa-{name.removeprefix('speed-')}").read_bytes())
        return {
            "apk_sha256": (fixture / "apk-sha256.txt").read_text().strip(),
            "instrumentation_apk_sha256": (fixture / "test-apk-sha256.txt").read_text().strip(),
            "notes": ("Same application source with emulator-only loopback TLS trust. "
                      "Instrumentation labels local synthetic measurements in the provider field."),
            "files": SPEED_CAPTURES,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("--release", action="store_true", help="capture only the seven 1.5.1 additions on API 36")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
               or Path.home() / "Library/Android/sdk")
    adb = [sdk / "platform-tools/adb", "-s", args.serial]

    def run(*command):
        return subprocess.check_output([*adb, *command], text=True, stderr=subprocess.PIPE, timeout=90).strip()

    def installed_apk_hash(package, apk):
        installed = run("shell", "pm", "path", package).removeprefix("package:")
        actual = run("shell", "sha256sum", installed).split()[0]
        expected = sha256(apk)
        if expected != actual:
            raise RuntimeError(f"installed APK differs from local build for {package}")
        return actual

    if (not re.fullmatch(r"emulator-\d+", args.serial)
            or run("shell", "getprop", "ro.kernel.qemu") != "1"
            or run("shell", "getprop", "ro.hardware") not in {"ranchu", "goldfish"}):
        parser.error("use an explicitly selected disposable emulator")
    sdk_level = int(run("shell", "getprop", "ro.build.version.sdk"))
    if sdk_level < 33:
        parser.error("capture requires app locale support on Android API 33 or later")
    if args.release and sdk_level != 36:
        parser.error("the loopback fixture requires an API 36 emulator")

    env = {**os.environ, "ANDROID_HOME": str(sdk)}
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
    sources_hash = app_source_sha256()
    subprocess.run([ROOT / "gradlew", "assembleDebug", "assembleDebugAndroidTest"], cwd=ROOT, env=env, check=True)
    run("shell", "wm", "size", "1200x2000")
    run("shell", "wm", "density", str(DENSITY))
    run("shell", "wm", "fixed-to-user-rotation", "enabled")
    run("shell", "wm", "user-rotation", "lock", "1")
    run("shell", "settings", "put", "system", "font_scale", "1.0")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        run("shell", "settings", "put", "global", setting, "0")
    actual_density = int(run("shell", "wm", "density").splitlines()[-1].rsplit(" ", 1)[-1])
    actual_font_scale = float(run("shell", "settings", "get", "system", "font_scale"))
    if actual_density != DENSITY or actual_font_scale != 1.0:
        raise RuntimeError("display density or font scale does not match the gallery settings")

    run("install", "-r", str(APP_APK))
    run("install", "-r", str(TEST_APK))
    standard_hash = installed_apk_hash(PACKAGE, APP_APK)
    standard_test_hash = installed_apk_hash(PACKAGE + ".test", TEST_APK)
    version = version_information(run("shell", "dumpsys", "package", PACKAGE))
    if args.release and version != {"version": "1.5.1", "version_code": 38}:
        raise RuntimeError("--release captures require version 1.5.1 and code 38")
    run("shell", "pm", "clear", PACKAGE)
    run("shell", "pm", "clear", PACKAGE + ".test")
    run("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--user", "0", "--locales", LOCALE)
    locale = run("shell", "cmd", "locale", "get-app-locales", PACKAGE, "--user", "0")
    if f"[{LOCALE}]" not in locale:
        raise RuntimeError("gallery locale was not applied")
    run("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    if not args.release and sdk_level >= 33:
        run("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    report = run("shell", "am", "instrument", "-w", "-e", "gallery", "release" if args.release else "true",
                 PACKAGE + ".test/" + PACKAGE + ".SafetyChecks")
    if "PASS:" not in report or "FAIL:" in report or "INSTRUMENTATION_CODE: -1" not in report:
        raise RuntimeError(report)
    print(report, flush=True)

    target = ROOT / "build/ui-checks" / ("gallery-1.5.1" if args.release else "gallery")
    target.mkdir(parents=True, exist_ok=True)
    remote = f"/sdcard/Android/data/{PACKAGE}/files"
    standard_captures = STANDARD_CAPTURES if args.release else json.loads(
        run("shell", "cat", f"{remote}/gallery-files.json"))
    for name in standard_captures:
        if Path(name).name != name or not name.endswith(".png"):
            raise RuntimeError("invalid capture name")
        run("pull", f"{remote}/{name}", str(target / name))

    builds = {
        "standard_debug": {
            "apk_sha256": standard_hash,
            "instrumentation_apk_sha256": standard_test_hash,
            "notes": "Standard source build with synthetic navigation and renderer fixtures.",
            "files": standard_captures,
        },
    }
    names = list(standard_captures)
    if args.release:
        builds["standard_debug"]["notes"] += " Street frames use the map drawn by the test APK."
        builds["loopback_https_fixture_debug"] = capture_speed(args.serial, env, version, target)
        if (installed_apk_hash(PACKAGE, APP_APK) != standard_hash
                or installed_apk_hash(PACKAGE + ".test", TEST_APK) != standard_test_hash):
            raise RuntimeError("restored application APKs do not match the standard gallery build")
        if builds["loopback_https_fixture_debug"]["apk_sha256"] == standard_hash:
            raise RuntimeError("loopback captures must identify the separate fixture APK")
        names[1:1] = SPEED_CAPTURES

    for name in names:
        if png_dimensions(target / name) != DIMENSIONS:
            raise RuntimeError(f"{name} has unexpected capture dimensions")
    if app_source_sha256() != sources_hash:
        raise RuntimeError("build or instrumentation sources changed during capture")
    manifest = {
        "application_commit": commit,
        "working_tree_changes": dirty,
        "build_sources_sha256": sources_hash,
        **version,
        "capture_date": datetime.now().astimezone().date().isoformat(),
        "emulator_build_fingerprint": run("shell", "getprop", "ro.build.fingerprint"),
        "dimensions": DIMENSIONS,
        "density": actual_density,
        "locale": LOCALE,
        "font_scale": actual_font_scale,
        "synthetic": True,
        "builds": builds,
        "files": names,
        "file_sha256": {name: sha256(target / name) for name in names},
    }
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"PASS: {len(names)} emulator captures verified in {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
