#!/usr/bin/env python3
"""Run the speed-test integration suite on a dedicated API 36 Android emulator."""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import threading

from speed_https_fixture import Fixture


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.pbuchman.duduhome"
RUNNER = f"{PACKAGE}.test/{PACKAGE}.speedqa.SpeedIntegrationChecks"
TILE_RUNNER = f"{PACKAGE}.test/{PACKAGE}.ui.SpeedTileChecks"


def command(*args, **options):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **options)


def adb_text(adb, *args):
    return command(*adb, *args, stdout=subprocess.PIPE).stdout.strip()


def openssl(temp, *args):
    subprocess.run(("openssl", *args), cwd=temp, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)


def make_fixture_overlay(temp):
    res = temp / "res"
    (res / "raw").mkdir(parents=True)
    (res / "xml").mkdir()
    openssl(temp, "req", "-x509", "-nodes", "-newkey", "rsa:2048", "-days", "1",
            "-subj", "/CN=Dudu Home emulator fixture CA",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-keyout", "ca.key", "-out", "res/raw/speed_fixture_ca.pem")
    openssl(temp, "req", "-nodes", "-newkey", "rsa:2048", "-subj", "/CN=127.0.0.1",
            "-keyout", "loopback.key", "-out", "loopback.csr")
    (temp / "leaf.ext").write_text("subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\n")
    openssl(temp, "x509", "-req", "-in", "loopback.csr", "-CA", "res/raw/speed_fixture_ca.pem",
            "-CAkey", "ca.key", "-CAserial", "ca.srl", "-CAcreateserial", "-days", "1", "-extfile", "leaf.ext",
            "-out", "loopback.crt")
    (temp / "AndroidManifest.xml").write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/speed_fixture_tls" />
</manifest>
""")
    (res / "xml" / "speed_fixture_tls.xml").write_text("""<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="false">
        <domain>127.0.0.1</domain>
        <trust-anchors><certificates src="@raw/speed_fixture_ca" /></trust-anchors>
    </domain-config>
</network-security-config>
""")
    init = temp / "fixture.gradle"
    init.write_text("""gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.pluginManager.withPlugin('com.android.application') {
            def fixture = new File(System.getenv('DUDU_SPEED_FIXTURE_DIR'))
            project.layout.buildDirectory.set(new File(fixture, 'build'))
            project.android.sourceSets.debug.manifest.srcFile(new File(fixture, 'AndroidManifest.xml'))
            project.android.sourceSets.debug.res.srcDir(new File(fixture, 'res'))
        }
    }
}
""")
    return init


def installed_proof(adb, apk, out, package=PACKAGE):
    # Hash the installed base via readable emulator filesystem and compare the exact input APK.
    installed = adb_text(adb, "shell", "pm", "path", package).removeprefix("package:")
    actual = adb_text(adb, "shell", "sha256sum", installed).split()[0]
    expected = hashlib.sha256(apk.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError("installed APK SHA-256 mismatch")
    out.write_text(expected + "\n")
    if package == PACKAGE:
        package_info = adb_text(adb, "shell", "dumpsys", "package", package)
        (out.parent / "installed-version.txt").write_text("\n".join(
            line.strip() for line in package_info.splitlines() if "versionCode=" in line or "versionName=" in line) + "\n")


def pull_evidence(adb, out):
    journal = adb_text(adb, "shell", "run-as", PACKAGE, "cat", "no_backup/diagnostics.txt")
    events = (" SPEED_TEST_", " DISPATCH_GATE ", " QUEUE_GATE_", " GATE_", " ATTEMPT_", " PROGRESS_",
              " MEDIA_ENQUEUED ", " YANOSIK_", " MOTION_", " DEPARTURE_", " DEPARTURE ID=", " QUEUE_STATE ")
    (out / "app-diag.txt").write_text("\n".join(line for line in journal.splitlines()
                                               if any(event in line for event in events)) + "\n")
    evidence_path = f"/sdcard/Android/data/{PACKAGE}/files"
    files = adb_text(adb, "shell", "ls", evidence_path).splitlines()
    for name in files:
        if name.startswith("speed-qa-") and name.endswith(".png"):
            command(*adb, "pull", f"{evidence_path}/{name}", out / name, stdout=subprocess.DEVNULL)


def passed(report):
    return "PASS:" in report and "FAIL:" not in report and "INSTRUMENTATION_CODE: -1" in report


def run(adb, app_apk, test_apk, out, scenario, port, check_tile=False, locale=None, gallery_label=False):
    out.mkdir(mode=0o700, parents=True, exist_ok=True)
    command(*adb, "install", "-r", app_apk)
    installed_proof(adb, app_apk, out / "apk-sha256.txt")
    command(*adb, "install", "-r", test_apk)
    installed_proof(adb, test_apk, out / "test-apk-sha256.txt", PACKAGE + ".test")
    command(*adb, "shell", "pm", "clear", PACKAGE)
    command(*adb, "shell", "pm", "clear", f"{PACKAGE}.test")
    if locale:
        adb_text(adb, "shell", "cmd", "locale", "set-app-locales", PACKAGE, "--user", "0", "--locales", locale)
        actual = adb_text(adb, "shell", "cmd", "locale", "get-app-locales", PACKAGE, "--user", "0")
        if f"[{locale}]" not in actual:
            raise RuntimeError("fixture locale was not applied")
        (out / "application-locale.txt").write_text(locale + "\n")
    tile_ok = True
    if check_tile:
        report = adb_text(adb, "shell", "am", "instrument", "-w", TILE_RUNNER)
        (out / "tile-instrumentation.txt").write_text(report + "\n")
        print(report, flush=True)
        tile_ok = passed(report)
    print(f"Running {scenario} on {adb[-1]}", flush=True)
    gallery_args = ("-e", "gallery_label", "true") if gallery_label else ()
    report = adb_text(adb, "shell", "am", "instrument", "-w", "-e", "scenario", scenario,
                      "-e", "port", str(port), *gallery_args, RUNNER)
    (out / "instrumentation.txt").write_text(report + "\n")
    print(report, flush=True)
    try:
        pull_evidence(adb, out)
    except subprocess.CalledProcessError:
        if "PASS:" in report:
            raise
    return tile_ok and passed(report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--scenario", choices=["all", "success", "failures", "bounds", "lifecycle", "network", "cloudflare"],
                        default="all")
    parser.add_argument("--locale", choices=["pl-PL"], help="set Polish app locale after clearing emulator data")
    parser.add_argument("--gallery-label", action="store_true", help="label the synthetic success captures in the UI")
    parser.add_argument("--cloudflare", action="store_true", help="also test the unmodified APK against Cloudflare")
    parser.add_argument("--tile", action="store_true", help="also check the speed tile on clean emulator app data")
    args = parser.parse_args()
    if args.gallery_label and (args.scenario != "success" or args.cloudflare):
        parser.error("--gallery-label requires the local success fixture")
    if not args.serial.startswith("emulator-") or not args.serial[9:].isdigit():
        parser.error("use the dedicated local emulator serial")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    if adb_text(adb, "shell", "getprop", "ro.kernel.qemu") != "1" or adb_text(adb, "shell", "getprop", "ro.build.version.sdk") != "36":
        parser.error("requires the dedicated API 36 Android emulator")
    settings = [("system", "accelerometer_rotation"), ("system", "user_rotation"),
                ("global", "mobile_data"), ("global", "wifi_on")]
    original = [adb_text(adb, "shell", "settings", "get", group, key) for group, key in settings]
    fixed_rotation = adb_text(adb, "shell", "wm", "fixed-to-user-rotation")
    dimensions = adb_text(adb, "shell", "wm", "size").splitlines()[-1].rsplit(" ", 1)[-1]
    width, height = (int(value) for value in dimensions.split("x"))
    evidence = args.evidence.resolve()
    if evidence == ROOT or ROOT in evidence.parents:
        parser.error("store synthetic emulator evidence outside the repository")
    evidence.mkdir(mode=0o700, parents=True, exist_ok=True)
    env = {**os.environ, "ANDROID_HOME": str(sdk)}
    results = []
    standard = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    standard_test = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    try:
        command(*adb, "shell", "wm", "fixed-to-user-rotation", "enabled")
        command(*adb, "shell", "wm", "user-rotation", "lock", "1" if width < height else "0")
        command(*adb, "shell", "svc", "data", "enable")
        command(*adb, "shell", "svc", "wifi", "enable")
        command(ROOT / "gradlew", "assembleDebug", "assembleDebugAndroidTest", env=env)
        if args.scenario == "cloudflare" or args.cloudflare:
            results.append(run(adb, standard, standard_test, evidence / "cloudflare", "cloudflare", 0, args.tile, args.locale))
        if args.scenario != "cloudflare":
            with tempfile.TemporaryDirectory(prefix="dudu-speed-fixture-") as tmp:
                temp = Path(tmp)
                init = make_fixture_overlay(temp)
                command(ROOT / "gradlew", "-I", init, "assembleDebug", "assembleDebugAndroidTest",
                        env={**env, "DUDU_SPEED_FIXTURE_DIR": tmp})
                output = evidence / args.scenario
                output.mkdir(mode=0o700, exist_ok=True)
                with (output / "https-fixture.txt").open("w") as trace, Fixture(
                    temp / "loopback.crt", temp / "loopback.key", trace
                ) as server:
                    port = server.server_address[1]
                    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
                    server_thread.start()
                    command(*adb, "reverse", f"tcp:{port}", f"tcp:{port}")
                    try:
                        results.append(run(adb, temp / "build/outputs/apk/debug/app-debug.apk",
                                           temp / "build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                                           output, args.scenario, port, args.tile and not args.cloudflare,
                                           args.locale, args.gallery_label))
                    finally:
                        try:
                            server.shutdown()
                            command(*adb, "reverse", "--remove", f"tcp:{port}")
                        finally:
                            command(*adb, "install", "-r", standard)
                            command(*adb, "install", "-r", standard_test)
                            installed_proof(adb, standard, evidence / "restored-apk-sha256.txt")
                            installed_proof(adb, standard_test, evidence / "restored-test-apk-sha256.txt", PACKAGE + ".test")
    finally:
        command(*adb, "shell", "wm", "fixed-to-user-rotation", fixed_rotation)
        for (group, key), value in zip(settings[:2], original[:2]):
            if value == "null":
                command(*adb, "shell", "settings", "delete", group, key)
            else:
                command(*adb, "shell", "settings", "put", group, key, value)
        command(*adb, "shell", "svc", "data", "enable" if original[2] == "1" else "disable")
        command(*adb, "shell", "svc", "wifi", "enable" if original[3] == "1" else "disable")
    return 0 if all(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
