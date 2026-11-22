#!/usr/bin/env python3
"""A stand-in for Claude Code's input box, for the send guard's tests against a real tmux.

It draws a box like Claude Code's (a rule, the prompt mark, a rule), turns on bracketed paste, and keeps what is
pasted or typed. Enter outside a paste submits: the text is appended to OUT as one JSON line and the box is cleared.
Inside a paste a carriage return is a newline, as tmux sends one for each line feed. With --exit-on-paste it exits
as soon as a paste ends, like an agent that quits between the bridge's check and its Enter.

Usage: fake-claude.py OUT PIDFILE [--exit-on-paste]
"""
import codecs
import json
import os
import sys
import termios
import tty

START, END = b"\x1b[200~", b"\x1b[201~"
RULE = "─" * 40


def draw(text):
    shown = text.replace("\n", " ")[:60]
    sys.stdout.write("\x1b[2J\x1b[Hfake agent\r\n" + RULE + "\r\n❯ " + shown + "\r\n" + RULE + "\r\n")
    sys.stdout.flush()


def main():
    out, pidfile = sys.argv[1], sys.argv[2]
    exit_on_paste = "--exit-on-paste" in sys.argv[3:]
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    tty.setraw(fd)
    sys.stdout.write("\x1b[?2004h")
    with open(pidfile + ".tmp", "w") as f:
        f.write(str(os.getpid()))
    os.rename(pidfile + ".tmp", pidfile)
    decoder = codecs.getincrementaldecoder("utf-8")()
    text, pasting, pending = "", False, b""
    try:
        draw(text)
        while True:
            data = os.read(fd, 65536)
            if not data:
                return
            pending += data
            while pending:
                if pending.startswith(START):
                    pasting, pending = True, pending[len(START):]
                elif pending.startswith(END):
                    pasting, pending = False, pending[len(END):]
                    if exit_on_paste:
                        return
                    draw(text)
                elif pending[:1] == b"\x1b" and (START.startswith(pending) or END.startswith(pending)):
                    break  # The rest of a paste marker is still on its way.
                else:
                    byte, pending = pending[:1], pending[1:]
                    if byte == b"\r" and not pasting:
                        with open(out, "a") as f:
                            f.write(json.dumps({"text": text}) + "\n")
                        text = ""
                        draw(text)
                    elif byte == b"\r":
                        text += "\n"
                    elif pasting or byte >= b" ":
                        text += decoder.decode(byte)
            if not pasting:
                draw(text)
    finally:
        sys.stdout.write("\x1b[?2004l")
        termios.tcsetattr(fd, termios.TCSADRAIN, saved)


main()
