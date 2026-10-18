#!/bin/sh
# 本机可跑的最小验证：编译 core-math 并执行自检。失败返回非 0。
set -e
KOTLINC="${KOTLINC:-/root/tools/kotlinc/bin/kotlinc}"
OUT="${OUT:-/root/build/core.jar}"
cd "$(dirname "$0")"
"$KOTLINC" src/filmmeter/core/*.kt -include-runtime -d "$OUT"
LANG=C.UTF-8 java -Dstdout.encoding=UTF-8 -jar "$OUT"
