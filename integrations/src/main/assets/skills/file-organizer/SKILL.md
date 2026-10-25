# Workspace File Organizer

Use this skill when the user asks to tidy the workspace or group files.

Steps:
1. `file.list` with `recursive: true` to see the current workspace.
2. Decide target folders by extension: documents (md, txt, pdf), data (csv, json), images (png, jpg),
   scripts (py, js, sh). Skip the folders `artifacts`, `logs` and `skills`.
3. Move each file with `file.move` (one call per file, verify each result).
4. Write `exports/workspace-index.md` with a table of original path, new path and size.
5. Report how many files were moved and list anything skipped.

Only operate inside the agent workspace: paths outside it are rejected by the tools.
