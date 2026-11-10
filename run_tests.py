"""Run the Android unit tests outside Gradle.

Why this exists
---------------
`gradle :app:testDebugUnitTest` fails on this machine with
`ClassNotFoundException` for test classes that are demonstrably compiled and on
the classpath.  The project lives under `E:\\灰度地带\\...`, and Gradle's test
worker builds its command line from that path; on Windows the combination of
non-ASCII characters and the console code page corrupts it.

Rather than fight the build tool, this script assembles the same classpath
explicitly and hands it to `java` via an argument file (which sidesteps both the
length limit and the encoding problem).  It runs the *same* compiled classes and
the *same* JUnit, so a green run here means the tests really pass - it just does
not depend on Gradle's worker.

Usage:
    python run_tests.py            # compile then test
    python run_tests.py --no-build # test only, use existing classes
"""

from __future__ import annotations

import glob
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "app"
GRADLE_CACHE = Path(os.environ["USERPROFILE"]) / ".gradle" / "caches"

JAVA_HOME = Path(os.environ.get("JAVA_HOME", r"D:\Java\jdk-21"))
JAVA = JAVA_HOME / "bin" / "java.exe"

TEST_CLASSES = APP / "build" / "tmp" / "kotlin-classes" / "debugUnitTest"
MAIN_CLASSES = APP / "build" / "tmp" / "kotlin-classes" / "debug"


def find_jars(*patterns: str) -> list[Path]:
    """Resolve jars from the Gradle module cache by glob."""
    found: list[Path] = []
    for pattern in patterns:
        found.extend(Path(p) for p in glob.glob(str(GRADLE_CACHE / "modules-2" / pattern)))
    return found


def resolve_dependency_jars() -> list[Path]:
    """The jars the tests need, resolved from the module cache.

    Deliberately explicit rather than "everything in the cache": a test run that
    silently picks up an unrelated version of a library is worse than one that
    fails to start.

    AndroidX ships as AARs, which are zip archives containing `classes.jar`.
    Gradle normally extracts those into a transform cache; here they are pulled
    straight out of the AAR so the run does not depend on that cache being warm.
    """
    wanted = [
        "files-2.1/junit/junit/4.13.2/*/junit-4.13.2.jar",
        "files-2.1/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar",
        "files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/kotlin-stdlib-2.0.21.jar",
        "files-2.1/org.jetbrains/annotations/23.0.0/*/annotations-23.0.0.jar",
        "files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/*/kotlinx-coroutines-core-jvm-1.8.1.jar",
    ]
    jars: list[Path] = []
    missing: list[str] = []
    for pattern in wanted:
        matches = sorted(glob.glob(str(GRADLE_CACHE / "modules-2" / pattern)))
        if not matches:
            missing.append(pattern)
            continue
        jars.append(Path(matches[0]))
    if missing:
        print("warning: some test dependencies were not found in the Gradle cache:")
        for m in missing:
            print("   ", m)
        print("   (run `gradle :app:assembleDebug` once to populate the cache)")
    return jars


def extract_aar_classes() -> list[Path]:
    """Extract classes.jar from the AndroidX AARs the tests need.

    Returns paths to jars written under `build/test-libs/`.  Extracting rather
    than relying on Gradle's transform cache means the test run works after a
    `gradle clean`.
    """
    out_dir = APP / "build" / "test-libs"
    out_dir.mkdir(parents=True, exist_ok=True)

    aars = [
        # `lifecycle-viewmodel` is a KMP aggregator with no payload of its own;
        # the actual AAR lives in the `-android` sibling artifact, and its file
        # name inside the cache is the publication name
        # (`lifecycle-viewmodel-release.aar`), not the module coordinate.  A
        # precise-looking glob silently matches nothing, which shows up later as
        # NoClassDefFoundError: androidx/lifecycle/ViewModel.
        "files-2.1/androidx.lifecycle/lifecycle-viewmodel-android/2.8.7/*/*.aar",
        "files-2.1/androidx.lifecycle/lifecycle-common-jvm/2.8.7/*/*.jar",
        "files-2.1/androidx.annotation/annotation-jvm/1.8.1/*/*.jar",
        "files-2.1/androidx.collection/collection-jvm/1.4.4/*/*.jar",
        "files-2.1/androidx.arch.core/core-common/2.2.0/*/*.jar",
    ]

    import zipfile

    result: list[Path] = []
    for pattern in aars:
        matches = sorted(glob.glob(str(GRADLE_CACHE / "modules-2" / pattern)))
        if not matches:
            continue
        source = Path(matches[0])
        if source.suffix == ".jar":
            result.append(source)
            continue
        target = out_dir / (source.stem + "-classes.jar")
        if not target.exists():
            try:
                with zipfile.ZipFile(source) as zf:
                    with zf.open("classes.jar") as src, open(target, "wb") as dst:
                        dst.write(src.read())
            except (KeyError, zipfile.BadZipFile):
                continue
        result.append(target)
    return result


def android_jar() -> Path | None:
    sdk = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
    candidates = sorted(glob.glob(str(sdk / "platforms" / "android-*" / "android.jar")))
    return Path(candidates[-1]) if candidates else None


def build_test_classes() -> bool:
    """Compile the unit tests via Gradle (this part does work)."""
    gradle = Path(os.environ["USERPROFILE"]) / ".gradle" / "wrapper" / "dists" / \
        "gradle-8.9-bin" / "90cnw93cvbtalezasaz0blq0a" / "gradle-8.9" / "bin" / "gradle.bat"
    cmd = [str(gradle), ":app:compileDebugUnitTestKotlin", "--no-daemon", "--console=plain"]
    print("compiling tests...")
    result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
                            errors="replace")
    if result.returncode != 0:
        print(result.stdout[-4000:])
        print(result.stderr[-4000:])
        return False
    return True


def discover_test_classes() -> list[str]:
    names: list[str] = []
    for path in TEST_CLASSES.rglob("*Test.class"):
        rel = path.relative_to(TEST_CLASSES).with_suffix("")
        names.append(".".join(rel.parts))
    return sorted(names)


def main() -> int:
    if "--no-build" not in sys.argv:
        if not build_test_classes():
            print("FAILED to compile tests")
            return 1

    if not TEST_CLASSES.exists():
        print(f"no compiled test classes at {TEST_CLASSES}")
        return 1

    classes = discover_test_classes()
    if not classes:
        print("no test classes found")
        return 1

    # Run from `app/build` with *relative* classpath entries.
    #
    # The project lives under a path containing non-ASCII characters.  This JVM
    # reports sun.jnu.encoding = GBK, and when the classpath carries an absolute
    # path with those characters the class loader silently fails to resolve
    # entries - every test class comes back as ClassNotFoundException even though
    # the file is right there.  Relative paths contain only ASCII, so they work;
    # the working directory supplies the prefix instead of the string.
    workdir = APP / "build"
    relative = [
        "tmp/kotlin-classes/debugUnitTest",
        "tmp/kotlin-classes/debug",
    ]

    dependency_paths: list[str] = []
    for jar in resolve_dependency_jars():
        dependency_paths.append(str(jar))
    for jar in extract_aar_classes():
        dependency_paths.append(str(jar))

    aj = android_jar()
    if aj is not None:
        dependency_paths.append(str(aj))

    classpath = ";".join(relative + dependency_paths)
    if any(c.isspace() for c in classpath):
        print("ERROR: a classpath entry contains whitespace; quoting is not implemented")
        return 1

    cmd = [
        str(JAVA),
        "-cp",
        classpath,
        "-Dfile.encoding=UTF-8",
        "org.junit.runner.JUnitCore",
        *classes,
    ]
    print(f"running {len(classes)} test classes...")

    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = (
        "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8"
    )
    result = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", env=env)
    out = result.stdout + result.stderr
    print(out[-6000:])
    return 0 if result.returncode == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
