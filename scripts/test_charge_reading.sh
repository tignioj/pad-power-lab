#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x '/Applications/PyCharm.app/Contents/jbr/Contents/Home/bin/javac' ]; then
        export JAVA_HOME='/Applications/PyCharm.app/Contents/jbr/Contents/Home'
    else
        export JAVA_HOME="$(/usr/libexec/java_home)"
    fi
fi
mkdir -p build/test-classes
"$JAVA_HOME/bin/javac" --release 8 -Xlint:-options -d build/test-classes \
    app/src/main/java/com/padpowerlab/ChargeReading.java tests/ChargeReadingTest.java
"$JAVA_HOME/bin/java" -ea -cp build/test-classes com.padpowerlab.ChargeReadingTest
