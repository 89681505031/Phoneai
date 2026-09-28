#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
whisper = root / "third_party" / "whisper.cpp"
gradle = whisper / "examples/whisper.android/lib/build.gradle"
if not gradle.exists():
    raise SystemExit("whisper.cpp Android library not found; run setup_whisper.sh first")

text = gradle.read_text(encoding="utf-8")
replacements = [
    ("compileSdk 34", "compileSdk 36"),
    ("targetSdk 34", "targetSdk 36"),
    ("abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'", "abiFilters 'arm64-v8a'"),
    ("ndkVersion \"25.2.9519653\"", "ndkVersion \"29.0.13113456\""),
    ("sourceCompatibility JavaVersion.VERSION_1_8", "sourceCompatibility JavaVersion.VERSION_17"),
    ("targetCompatibility JavaVersion.VERSION_1_8", "targetCompatibility JavaVersion.VERSION_17"),
    ("jvmTarget = '1.8'", "jvmTarget = '17'"),
]
for old, new in replacements:
    if new in text:
        continue
    if old not in text:
        raise RuntimeError(f"Patch anchor missing in whisper Android Gradle: {old!r}")
    text = text.replace(old, new, 1)

anchor = """        externalNativeBuild {\n            cmake {\n                // When set, builds whisper.android against the version located\n"""
replacement = """        externalNativeBuild {\n            cmake {\n                // Keep native whisper.cpp optimized even for the debug APK.\n                arguments \"-DCMAKE_BUILD_TYPE=Release\"\n                // When set, builds whisper.android against the version located\n"""
if replacement not in text:
    if anchor not in text:
        raise RuntimeError("Could not add release optimization to whisper Android module")
    text = text.replace(anchor, replacement, 1)

dep_anchor = """dependencies {
    implementation 'androidx.core:core-ktx:1.9.0'
"""
dep_replacement = """dependencies {
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0'
    implementation 'androidx.core:core-ktx:1.9.0'
"""
if "kotlinx-coroutines-android:1.11.0" not in text:
    if dep_anchor not in text:
        raise RuntimeError("Could not add coroutine dependency to whisper Android module")
    text = text.replace(dep_anchor, dep_replacement, 1)

gradle.write_text(text, encoding="utf-8")
print("PhoneAI whisper.cpp Android patches applied")
