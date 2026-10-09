#!/usr/bin/env python3
"""Build against supplied Minecraft/NeoForge libraries; never install into server mods."""
import argparse
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent


def run(command):
    result = subprocess.run(command)
    if result.returncode:
        raise SystemExit(f"Build command failed (exit {result.returncode}); see output above.")


def package(classes, output):
    metadata = tomllib.loads((ROOT / "src/main/resources/META-INF/neoforge.mods.toml").read_text())
    version = metadata["mods"][0]["version"]
    output.mkdir(parents=True, exist_ok=True)
    jar = output / f"local-async-player-save-1.21.1-{version}.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            if "Test" not in path.name:
                archive.write(path, path.relative_to(classes))
        for path in sorted((ROOT / "src/main/resources").rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(ROOT / "src/main/resources"))
    print(jar)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default=os.environ.get("MINECRAFT_CONTAINER", "minecraft-crafty"))
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--server-dir", default=os.environ.get("MINECRAFT_SERVER_DIR"), help="Minecraft directory inside the container")
    source.add_argument("--libraries-dir", default=os.environ.get("MINECRAFT_LIBRARIES_DIR"), help="local Minecraft/NeoForge libraries; requires a local Java 21 JDK")
    parser.add_argument("--java", default=os.environ.get("JAVA21"), help="Java 21 executable on the host or inside the container")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build")
    args = parser.parse_args()
    if not args.server_dir and not args.libraries_dir:
        parser.error("provide --server-dir or --libraries-dir (or corresponding environment variable)")
    if args.libraries_dir:
        java = args.java or "java"
        version_cmd = [java, "-version"]
    else:
        java = args.java or subprocess.check_output(["docker", "exec", args.container, "sh", "-ec", "if test -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java; then printf %s /usr/lib/jvm/java-21-openjdk-amd64/bin/java; else command -v java; fi"], text=True).strip()
        version_cmd = ["docker", "exec", args.container, java, "-version"]
    version = subprocess.run(version_cmd, capture_output=True, text=True)
    if version.returncode or not re.search(r'version \"21(?:[.\"]|$)', version.stderr + version.stdout):
        parser.error("a Java 21 JDK is required; use --java to select it")
    tests = sorted(path.stem for path in (ROOT / "src/test/java/local/asyncplayersave").glob("*Test.java"))
    if args.libraries_dir:
        libraries = Path(args.libraries_dir).resolve()
        jars = sorted(libraries.rglob("*.jar"), key=lambda p: (not p.name.endswith("-srg.jar"), str(p)))
        if not jars:
            parser.error("no dependency JARs in --libraries-dir")
        classpath = os.pathsep.join(map(str, jars))
        with tempfile.TemporaryDirectory(prefix="save-patch-build-") as folder:
            classes = Path(folder) / "classes"
            sources = sorted((ROOT / "src").rglob("*.java"))
            run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "21", "-target", "21", "-proc:none", "-cp", classpath, "-d", str(classes), *map(str, sources)])
            for test in tests:
                run([java, "-cp", str(classes) + os.pathsep + classpath, "local.asyncplayersave." + test])
            package(classes, args.output_dir)
        return
    target = "/tmp/local-save-patch-build-" + uuid.uuid4().hex
    run(["docker", "exec", args.container, "mkdir", "-p", target])
    try:
        run(["docker", "cp", str(ROOT / "src"), args.container + ":" + target + "/"])
        script = '''server="$1"
target="$2"
find "$server/libraries" -name '*-srg.jar' -print > "$target/jars"
find "$server/libraries" -name '*.jar' ! -name '*-srg.jar' -print | sort >> "$target/jars"
test -s "$target/jars"
CP=$(paste -sd: "$target/jars")
find "$target/src/main/java" "$target/src/test/java" -name '*.java' > "$target/sources"
"$3" -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -proc:none -cp "$CP" -d "$target/classes" @"$target/sources"
'''
        run(["docker", "exec", args.container, "sh", "-ec", script, "build", args.server_dir, target, java])
        classpath = subprocess.check_output(["docker", "exec", args.container, "sh", "-ec", 'paste -sd: "$1/jars"', "build", target], text=True).strip()
        for test in tests:
            run(["docker", "exec", args.container, java, "-cp", target + "/classes:" + classpath, "local.asyncplayersave." + test])
        args.output_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="save-patch-classes-") as folder:
            run(["docker", "cp", args.container + ":" + target + "/classes", folder])
            package(Path(folder) / "classes", args.output_dir)
    finally:
        run(["docker", "exec", args.container, "rm", "-rf", target])


if __name__ == "__main__":
    main()
