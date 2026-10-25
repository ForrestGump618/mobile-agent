# Permissions and security

## Declared permissions

| Permission | Why | When it matters |
| --- | --- | --- |
| `INTERNET` | LLM providers, `http.request`, MCP | Any network tool |
| `ACCESS_NETWORK_STATE` | Diagnostics network check | Diagnostics |
| `POST_NOTIFICATIONS` | Foreground "agent working" notification, schedule results | Android 13+ |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep the process alive while a task runs | Any running task |
| `RECEIVE_BOOT_COMPLETED` | Re-register schedules and pause interrupted tasks | Reboot |
| `WAKE_LOCK` | WorkManager may hold a wake lock for scheduled work | Schedules |
| `QUERY_ALL_PACKAGES` | `app.list` / `app.launch` need to see installed packages | App tools |
| `BIND_ACCESSIBILITY_SERVICE` (service-level) | Only the system can bind the accessibility service | UI automation |
| Shizuku provider authority | Binder discovery for the Shizuku API | Shizuku |

Not requested: root, `MANAGE_EXTERNAL_STORAGE`, `READ/WRITE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`,
`READ_SMS`, `READ_CONTACTS`, location, camera, microphone, `REQUEST_INSTALL_PACKAGES`.

File access outside the app is only possible through the system file picker (Storage Access
Framework): the user selects a document, the app copies it into its private workspace, and the URI
permission is not assumed to survive forever.

## Secret storage

- API keys and MCP tokens are encrypted with AES-256/GCM using a key generated in the Android
  Keystore (`mobile_agent_master_key`, non-exportable).
- Ciphertext (IV + payload, Base64) is stored in `files/secret-vault.json`.
- Plaintext exists only in memory while a request is built.
- The database, checkpoints, audit events and chat transcripts never contain the key; tool arguments
  are redacted before logging.
- Deleting a provider deletes its secret entry.

Limitations: a compromised device with the app's UID can request decryption, and Android Keystore
does not protect against a fully compromised OS. There is no biometric gate on the vault in this
version.

## Risk model

| Level | Examples | Default behavior |
| --- | --- | --- |
| LOW | `device.info`, `file.list/read`, `ui.snapshot`, `memory.search` | allowed after capability checks |
| MEDIUM | `file.write/move`, `http.request`, `app.launch`, `ui.click/set_text`, `schedule.create`, MCP tools | allowed; appears in the audit log |
| HIGH | `adb.exec`, `file.delete`, `vision.analyze`, `skill.run` | **confirmation dialog** |
| CRITICAL | reserved for policy extensions | always confirmation, even in `AUTO_APPROVE_SAFE` |

The model cannot raise or lower a risk level; it only selects a registered tool, whose spec is fixed
in code. Tool arguments are validated against the declared JSON schema before authorization.

Hard denials (no override by the model):

- Shizuku tool when Shizuku is not running / permission not granted / disabled in settings.
- Accessibility tools when the service is not connected or disabled in settings.
- Vision when "send screenshots to cloud models" is off.
- Local shell when disabled.
- `http.request` to loopback, RFC1918, link-local or `.local` hosts.
- Catastrophic shell patterns (`rm -rf /`, `mkfs`, `dd of=/dev/block`, ...).

Approval semantics: the approval dialog blocks the tool call. Timeout (5 minutes), dismissal, back
press or app death all mean **rejected**. There is no auto-approve default.

## Data visibility

- The UI shows the tool, target, arguments, risk and reversibility before approval.
- Every tool call and every state change is written to `agent_events` with time and task id.
- The task export and diagnostics export run through `Redactor`, which masks bearer tokens, `sk-`
  keys, GitHub/Slack tokens and `api_key=`/`token=` values.
- Screenshots stay in the app workspace unless the user explicitly enables cloud vision and the model
  calls `vision.analyze`.
- Memory entries marked sensitive are excluded from prompts.

## Workspace isolation

All file tools resolve paths through `Workspace.resolve`, which canonicalizes and rejects anything
outside `files/workspace`. The workspace root itself cannot be deleted. Imports are capped at 20 MB;
reads at 512 KB; writes at 2 MB; HTTP bodies at 200 KB.

## Audit and export

Events are queryable by task id and execution id in Logs. The diagnostics export includes health
items and the last 200 events, redacted, written to `workspace/exports/`. No credentials are
included. Exports are not uploaded automatically.
