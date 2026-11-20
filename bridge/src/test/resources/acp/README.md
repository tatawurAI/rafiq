# ACP fixtures

Each line is `{"dir": "out" | "in", "msg": <JSON-RPC message>}`. `out` is client to agent and `in` is agent to client. `FakeAcpAgent` in the test sources replays a file as the agent: it writes each `in` line and waits for each `out` line from the bridge.

Recorded from `@agentclientprotocol/claude-agent-acp` 0.109.0 in a two-file sample project at `/home/user/weather-app`. The prompt asked for two helper agents in parallel, one to summarise `README.md` and one to report the highest temperature in `forecast.txt`.

- `with-subagents.jsonl`: the client sent `clientCapabilities.subagents: {}`. The parent announces each helper with `subagent_update` and each helper's tool calls arrive on its own session id. The helpers ran in the background, so their work arrives after the `session/prompt` answer.
- `without-subagents.jsonl`: no capability. The helpers' tool calls arrive on the parent session, tagged `_meta.claudeCode.parentToolUseId`.

Account and sign-in notifications were removed, the command list, helper type list and quota metadata were reduced to neutral values, and local paths were rewritten to `/home/user/...`.

Written by hand, for traffic the recordings do not contain:

- `child-approval.jsonl`: a helper session asks for permission, and the bridge answers and then cancels the helper.
- `unknown-update.jsonl`: an update type that ACP does not define.
- `turn-end-order.jsonl`: one helper finishes before the turn's answer and one after, to pin the wire order of the turn's end.
- `burst-ordering.jsonl`: 300 message chunks before the turn's answer and one after.
- `dies-mid-turn.jsonl`: the agent exits in the middle of a turn, with no answer to the prompt.
- `garbage-and-long-lines.jsonl`: a line that is not JSON and a line longer than the test's limit, then normal output.
- `resume.jsonl`: an agent that offers `session/resume` reattaches to an existing session.
- `approval-turn-cancel.jsonl`: an approval is open on the main thread when you cancel the turn, or close the session.
