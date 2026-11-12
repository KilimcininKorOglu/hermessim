#!/usr/bin/env bash
# Tests hermessimd's pure parts with the build machine's own C compiler (no NDK, no phone): the playout rule against
# protocol/v2/vectors/playout.txt.
set -eu
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
cc -std=c11 -Wall -Wextra -Werror -o "$out/playout_test" playout.c tests/playout_test.c
"$out/playout_test" ../../protocol/v2/vectors/playout.txt
