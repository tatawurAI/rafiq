# Recorded event streams

Each `<name>.events.jsonl` file is what the bridge logged for one session: one Rafiq `Event` per line, `{session_id, seq, ts_ms, payload}`, in `seq` order. The app's tests replay these to check how real event streams turn into screen state.

They are made from the ACP fixtures in `bridge/src/test/resources/acp/`. Each fixture is played by the fake ACP agent and runs through the real ACP adapter, session manager and SQLite event log, so the files show exactly what your phone would receive.

| File | What happens |
|---|---|
| `with-subagents.events.jsonl` | A prompt starts two helpers in the background. Each helper gets its own thread with its instructions, tool calls and report. The main thread replies, the turn ends, and then the agent sends a follow-up on its own, which ends without a new prompt. |
| `child-approval.events.jsonl` | A helper asks for permission. You allow it once, the helper is cancelled, and the turn ends. |
| `approval-turn-cancel.events.jsonl` | The main thread asks for permission and you cancel the turn instead, so the approval is resolved as cancelled. |
| `resume.events.jsonl` | A session the log already has is reattached after a bridge restart, and the agent picks up where it left off. |

## Regenerating

Run this from the repository root after a change to the adapter's mapping or to an ACP fixture, then review the diff:

```sh
./gradlew :bridge:recordEventFixtures
```

The task is not part of `test`. It rewrites every file here. The output repeats exactly from run to run: the clock steps a quarter second per event from a fixed start, ids the bridge makes are counted, and the session id and project path are fixed (`ses_weather_app`, `/home/user/weather-app`). The recorder is `bridge/src/test/kotlin/ai/tatawur/rafiq/bridge/RecordEventFixtures.kt`.

The ACP fixtures are scrubbed recordings. Before you commit new output, check it holds no personal data: no email addresses, no home paths other than `/home/user`, and no account or sign-in details.
