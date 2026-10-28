#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools/downloads .tools/sdk
fetch() {
    local filename="$1" checksum="$2" dest="$3"
    curl -fL --retry 2 -o ".tools/downloads/$filename" "https://dl.google.com/android/repository/$filename"
    local actual
    actual="$(shasum -a 1 ".tools/downloads/$filename" | cut -d ' ' -f 1)"
    [ "$actual" = "$checksum" ] || { echo "Checksum mismatch: $filename" >&2; exit 1; }
    unzip -q -o ".tools/downloads/$filename" -d "$dest"
}
fetch platform-35_r02.zip 0bb560a90a7a2cbd0dd8348224d518b638fe7949 .tools/sdk/platforms
fetch build-tools_r35_macosx.zip 93ab8ce91230e067b5add4bfa79919c52b27f072 .tools/sdk/build-tools
curl -fL --retry 2 -o .tools/downloads/platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
unzip -q -o .tools/downloads/platform-tools.zip -d .tools
echo '工具已准备；还需要本机可用的 JDK 17+。'
