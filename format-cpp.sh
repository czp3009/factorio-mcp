#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

git ls-files -z -- '*.c' '*.cpp' '*.cc' '*.cxx' '*.h' '*.hpp' '*.hh' '*.hxx' |
    xargs -0 -r clang-format --style=file -i --
