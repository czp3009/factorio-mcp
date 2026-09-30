#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

git ls-files -z --cached --others --exclude-standard -- '*.c' '*.cpp' '*.cc' '*.cxx' '*.h' '*.hpp' '*.hh' '*.hxx' |
    while IFS= read -r -d '' path; do
        [[ ! -f "$path" ]] || printf '%s\0' "$path"
    done |
    xargs -0 -r clang-format --style=file -i --
