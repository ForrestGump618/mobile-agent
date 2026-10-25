# Read-only Shell Diagnostics

Use this skill when the user asks what the app's shell can see.

Allowed examples:
- `uptime`
- `ps -A | head -n 20`
- `df -h /data`
- `ip addr show wlan0`
- `getprop ro.build.version.release`

Rules:
- Read-only commands only. Never delete, install, uninstall, move or modify files.
- Put the exact command in `shell.exec`, read the exit code and stderr, and quote the real output.
- This shell runs with the app UID, not root and not ADB. Some paths are unreadable; report that
  instead of retrying with guesses.
