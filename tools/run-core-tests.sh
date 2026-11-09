#!/usr/bin/env bash
# Compiles :core + its tests with the Kotlin compiler bundled in Gradle and runs them (offline).
set -e
cd "$(dirname "$0")/.."
G=/opt/gradle-8.14.3/lib
CP="$G/kotlin-stdlib-2.0.21.jar:$G/jsoup-1.15.3.jar"
KC="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-script-runtime-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/kotlin-daemon-embeddable-2.0.21.jar:$(ls $G/trove4j*.jar 2>/dev/null | head -1):$(ls $G/annotations-*.jar 2>/dev/null | head -1):$(ls $G/kotlinx-coroutines-core-jvm*.jar | head -1)"
OUT=build-local; rm -rf $OUT; mkdir -p $OUT
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$CP" -d $OUT -jvm-target 17 -Xallow-kotlin-package \
  $(find core/src/main/kotlin core/src/test/kotlin tools/teststub tools/TestRunner.kt -name '*.kt') 2>&1 | grep -v "^warning" || true
TESTS=$(cd core/src/test/kotlin && find . -name '*Test.kt' | sed 's|^\./||; s|\.kt$||; s|/|.|g')
java -cp "$OUT:$CP" TestRunnerKt $TESTS
