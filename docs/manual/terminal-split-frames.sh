#!/bin/sh
# Checks that the app's terminal draws a character or escape sequence that arrives in two pieces.
#
# Run it inside a tmux session that your phone has open in Terminal. It writes each case once whole, then once
# for every byte position it can be split at, with a pause between the two halves. The pause makes tmux read
# the halves separately, so they reach the bridge, and your phone, in separate frames.
#
# What you should see on your phone, for every case:
# - Each split line looks exactly like the "whole" line of the same case above it.
# - The "|" at the end of each line is in the same column as on the "whole" line.
# - No line shows a replacement character, a question mark, a box, or text such as "[31m".
# - In the colour case, "red" is red on every line, and the "|" is not.
#
# Usage: sh docs/manual/terminal-split-frames.sh [pause-in-seconds]
set -eu

pause=${1:-0.3}

# Writes the bytes given as octal escapes, one argument per byte, from index $1 to $2 (1-based, inclusive),
# in a single write.
write_bytes() {
    from=$1
    to=$2
    shift 2
    format=""
    i=0
    for byte in "$@"; do
        i=$((i + 1))
        if [ "$i" -ge "$from" ] && [ "$i" -le "$to" ]; then format="$format$byte"; fi
    done
    # The format string is built only from the fixed byte lists below.
    # shellcheck disable=SC2059
    printf "$format"
}

run_case() {
    label=$1
    shift
    count=$#
    printf '%-6s whole ' "$label"
    write_bytes 1 "$count" "$@"
    printf '\033[0m|\n'
    split=1
    while [ "$split" -lt "$count" ]; do
        printf '%-6s %2d+%-2d ' "$label" "$split" $((count - split))
        write_bytes 1 "$split" "$@"
        sleep "$pause"
        write_bytes $((split + 1)) "$count" "$@"
        printf '\033[0m|\n'
        split=$((split + 1))
    done
    printf '\n'
}

# U+1F600, four bytes in UTF-8.
run_case emoji '\360' '\237' '\230' '\200'

# U+1F469 U+200D U+1F4BB, a woman and a laptop joined by a zero-width joiner into one glyph.
run_case zwj '\360' '\237' '\221' '\251' '\342' '\200' '\215' '\360' '\237' '\222' '\273'

# ESC [ 31 m, then "red", then a reset. The 2+ line is the split right after ESC [.
run_case colour '\033' '[' '3' '1' 'm' 'r' 'e' 'd' '\033' '[' '0' 'm'
