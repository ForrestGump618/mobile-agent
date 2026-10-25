# Scheduled Check

This skill is guidance only: scheduling is enforced by Android WorkManager and is created from the
Tasks screen, not by a model tool.

When the user asks for a recurring check:
1. Confirm the goal, the interval and the data source.
2. Tell them to open Tasks > Schedules and create it there (or use the schedule tool once enabled).
3. Explain that Android may delay the run due to Doze/battery restrictions; results are stored as a
   task with its own log.
4. Each run creates a normal task, so checkpoints, cancellation and failure reporting apply.

Do not claim a schedule exists until it is listed in the Schedules section.
