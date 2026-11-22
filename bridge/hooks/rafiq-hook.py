#!/usr/bin/env python3
"""Rafiq's hook program for Claude Code.

Claude Code runs this for each hook event in a project where you installed Rafiq's hooks. It forwards the
event JSON, the tmux pane and the agent's process id to your Rafiq bridge over a Unix socket in the bridge's
data folder. It never uses the network.

For a PermissionRequest it waits for an answer from your phone. It prints a decision only when the bridge
sends a valid one, and it builds that decision itself from the checked fields. In every other case (an
error, a timeout, the bridge not running, a reply that is malformed, too big or unknown) it prints nothing
and exits 0, so Claude Code shows its own dialog at the desk. It never prints or logs its environment.

Usage: rafiq-hook.py --data-dir <RAFIQ_DATA_DIR>

Python 3 standard library only. See bridge/src/main/kotlin/ai/tatawur/rafiq/bridge/hook/HookContract.kt for
the wire format.
"""

import json
import os
import re
import socket
import sys

WIRE_VERSION = 1
MAX_EVENT_BYTES = 1 << 20
MAX_REPLY_BYTES = 64 * 1024
AGENT = "claude-code"
# Observer events must never slow Claude Code down. A permission request waits for your phone. The bridge
# gives up after 600 s and Claude Code kills the hook at its 900 s timeout, so this only bounds a stuck bridge.
EVENT_TIMEOUT_S = 5
PERMISSION_TIMEOUT_S = 880
MAX_MESSAGE_CHARS = 2000
TOOL_NAME = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
RULE = re.compile(r"^([A-Za-z0-9_.:-]{1,128})(?:\((.*)\))?$", re.DOTALL)


def data_dir(argv):
    """The value of --data-dir, or None."""
    for index, arg in enumerate(argv):
        if arg == "--data-dir" and index + 1 < len(argv):
            return argv[index + 1]
    return None


def read_event():
    raw = sys.stdin.buffer.read(MAX_EVENT_BYTES + 1)
    if len(raw) > MAX_EVENT_BYTES:
        return None
    event = json.loads(raw.decode("utf-8"))
    return event if isinstance(event, dict) else None


def agent_pid():
    value = os.environ.get("CLAUDE_PID", "")
    return int(value) if value.isdigit() else None


def read_reply(sock):
    """One line of at most MAX_REPLY_BYTES, or None when the bridge closes first or sends too much."""
    data = b""
    while b"\n" not in data:
        chunk = sock.recv(8192)
        if not chunk:
            return None
        data += chunk
        if len(data) > MAX_REPLY_BYTES + 1:
            return None
    line = data.split(b"\n", 1)[0]
    if len(line) > MAX_REPLY_BYTES:
        return None
    return line.decode("utf-8")


def output(decision):
    return {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": decision}}


def session_rule(rule, tool_name):
    """The rule as Claude Code's permission update, or None. It may only cover the tool that asked."""
    if not isinstance(rule, str):
        return None
    match = RULE.match(rule.strip())
    if match is None or match.group(1) != tool_name:
        return None
    entry = {"toolName": tool_name}
    if match.group(2) is not None:
        entry["ruleContent"] = match.group(2)
    return entry


def question_answers(answers, tool_input):
    """The input for AskUserQuestion with the chosen answers, or None if they do not fit its questions."""
    if not isinstance(answers, dict) or not answers or not isinstance(tool_input, dict):
        return None
    questions = tool_input.get("questions")
    if not isinstance(questions, list):
        return None
    asked = {q.get("question") for q in questions if isinstance(q, dict)}
    for key, value in answers.items():
        if not isinstance(key, str) or not isinstance(value, str) or key not in asked:
            return None
    updated = dict(tool_input)
    updated["answers"] = {key: value for key, value in answers.items()}
    return updated


def decision_for(reply, event):
    """Builds Claude Code's decision object from a checked reply. None means ask at the desk."""
    if not isinstance(reply, dict):
        return None
    tool_name = event.get("tool_name")
    if not isinstance(tool_name, str) or not TOOL_NAME.match(tool_name):
        return None
    kind = reply.get("decision")
    if kind == "allow" and tool_name != "AskUserQuestion":
        return output({"behavior": "allow"})
    if kind == "deny":
        message = reply.get("message")
        if message is None:
            message = "Denied from your phone."
        if not isinstance(message, str):
            return None
        return output({"behavior": "deny", "message": message[:MAX_MESSAGE_CHARS]})
    if kind == "allow_session" and tool_name != "AskUserQuestion":
        entry = session_rule(reply.get("rule"), tool_name)
        if entry is None:
            return None
        update = {"type": "addRules", "rules": [entry], "behavior": "allow", "destination": "session"}
        return output({"behavior": "allow", "updatedPermissions": [update]})
    if kind == "answers" and tool_name == "AskUserQuestion":
        updated = question_answers(reply.get("answers"), event.get("tool_input"))
        if updated is None:
            return None
        return output({"behavior": "allow", "updatedInput": updated})
    return None


def run(argv):
    folder = data_dir(argv)
    pane = os.environ.get("TMUX_PANE")
    # Outside tmux there is no pane for the bridge to match, so there is nothing to send.
    if not folder or not pane:
        return None
    event = read_event()
    if event is None:
        return None
    waits = event.get("hook_event_name") == "PermissionRequest"
    with open(os.path.join(folder, "hook", "credential"), "r", encoding="utf-8") as handle:
        credential = handle.read().strip()
    request = {
        "v": WIRE_VERSION,
        "credential": credential,
        "kind": "permission" if waits else "event",
        "agent": AGENT,
        "pane": pane,
        "agent_pid": agent_pid(),
        "event": event,
    }
    line = (json.dumps(request, separators=(",", ":")) + "\n").encode("utf-8")
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        sock.settimeout(PERMISSION_TIMEOUT_S if waits else EVENT_TIMEOUT_S)
        sock.connect(os.path.join(folder, "hook", "hook.sock"))
        sock.sendall(line)
        if not waits:
            return None
        # Do not shut down the write side: the bridge reads a hang-up as "the desk answered, stop waiting".
        text = read_reply(sock)
    finally:
        sock.close()
    if text is None:
        return None
    return decision_for(json.loads(text), event)


def main():
    try:
        decision = run(sys.argv[1:])
        if decision is not None:
            sys.stdout.write(json.dumps(decision, separators=(",", ":")))
            sys.stdout.flush()
    except Exception:  # noqa: BLE001 - every failure means "ask at the desk", and says nothing.
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
