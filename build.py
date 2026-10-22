#!/usr/bin/env python3
"""Build a dependency-free Android APK using an installed Android SDK and JDK."""
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))
JDK = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17"))
if not (JDK / "bin/javac").is_file():
    JDK = Path("/opt/homebrew/opt/openjdk@17")
TOOLS = SDK / "build-tools/34.0.0"
ANDROID = SDK / "platforms/android-33/android.jar"
BUILD = ROOT / "build"
ENV = dict(os.environ, JAVA_HOME=str(JDK), PATH=str(JDK / "bin") + os.pathsep + os.environ.get("PATH", ""))


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, env=ENV, check=True)


def main():
    BUILD.mkdir(exist_ok=True)
    classes, dex, generated = [BUILD / name for name in ("classes", "dex", "generated")]
    for folder in (classes, dex, generated):
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir()
    run(TOOLS / "aapt2", "compile", "--dir", ROOT / "res", "-o", BUILD / "resources.zip")
    run(TOOLS / "aapt2", "link", "-o", BUILD / "resources.apk", "--manifest", ROOT / "AndroidManifest.xml",
        "-I", ANDROID, "--java", generated, "--version-code", "3", "--version-name", "0.3.0",
        "--min-sdk-version", "24", "--target-sdk-version", "33", BUILD / "resources.zip")
    sources = list((ROOT / "src").rglob("*.java")) + list(generated.rglob("*.java"))
    run(JDK / "bin/javac", "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
        "-bootclasspath", ANDROID, "-d", classes, *sources)
    jar = BUILD / "classes.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for item in classes.rglob("*.class"):
            archive.write(item, item.relative_to(classes))
    run(TOOLS / "d8", "--lib", ANDROID, "--min-api", "24", "--output", dex, jar)
    unsigned = BUILD / "unsigned.apk"
    shutil.copy2(BUILD / "resources.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        for item in dex.glob("*.dex"):
            archive.write(item, item.name)
    aligned = BUILD / "aligned.apk"
    run(TOOLS / "zipalign", "-f", "4", unsigned, aligned)
    key = ROOT / ".local-signing.p12"
    if not key.exists():
        run(JDK / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "local", "-dname", "CN=Local Android Utility", "-keyalg", "RSA", "-validity", "10000")
    output = ROOT / "红果助手.apk"
    run(TOOLS / "apksigner", "sign", "--ks", key, "--ks-key-alias", "local", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", output, aligned)
    run(TOOLS / "apksigner", "verify", "--verbose", output)
    print(output)


if __name__ == "__main__":
    main()
