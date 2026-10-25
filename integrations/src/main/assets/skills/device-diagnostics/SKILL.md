# Device Diagnostics

Use this skill when the user asks how the phone is doing, how much storage is left, which Android
version is running, or which apps are installed.

Steps:
1. Call `device.info` and read the real values (never guess).
2. If the user asked about apps, call `app.list` (third-party apps by default).
3. Summarize: Android version, model, free storage, battery, and anything that looks low.
4. If a value is missing from the tool output, say it is unavailable rather than inventing it.

Do not run destructive commands. Do not claim root access: this app is not rooted.
