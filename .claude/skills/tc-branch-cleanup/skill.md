---
name: tc-branch-cleanup
description: "Cancel all queued and running TeamCity builds for a branch. Intended for post-merge cleanup. Invoke as `/tc-branch-cleanup <branch>` with optional --comment."
---

# TC Branch Cleanup

## Intent

Use this skill when the user wants to cancel all queued and running TeamCity builds for a branch — typically after the branch has been merged and those builds are no longer relevant.

**This is a destructive, irreversible action.** Always show the user what will be canceled and require explicit confirmation before proceeding.

## Argument Parsing

- **branch** (required positional) — the exact branch name to cancel builds for
- **--comment** (optional) — cancellation message stored on each build in the TeamCity UI (default: `"Branch merged — canceling remaining builds"`)

If no branch name is provided, ask the user for one before proceeding.

## Execution Steps

### Step 1 — Discover builds

Run the discovery script to find all queued and running builds for the branch:

```bash
python3 .claude/scripts/tc-branch-builds.py <branch> --json
```

Parse the JSON output. It has the shape:
```json
{
  "queued": [ { "id": "...", "buildType": { "name": "...", "projectName": "..." }, ... } ],
  "running": [ { "id": "...", "buildType": { "name": "...", "projectName": "..." }, ... } ]
}
```

### Step 2 — Confirm with user

If there are no builds (both lists empty), report that and stop — nothing to do.

Otherwise, display a summary table like:

```
Found N build(s) to cancel for branch '<branch>':

  STATE    ID       PROJECT / JOB
  queued   12345    MyProject / Build & Test
  running  12346    MyProject / Deploy
  ...

This will cancel all of the above. Proceed? (yes/no)
```

**Do not proceed until the user explicitly confirms.** If they decline, stop.

### Step 3 — Cancel all builds

For each build ID (queued and running alike), run:

```bash
teamcity run cancel <id> --yes --comment "<comment>"
```

Run these sequentially, reporting success or failure for each one as you go.

### Step 4 — Summary

After all cancellations are attempted, report:
- How many succeeded
- Any that failed (with the error), so the user can investigate

## Error Handling

- If the discovery script exits non-zero, show the error and stop before asking for confirmation.
- If an individual `teamcity run cancel` fails, log the failure and continue with the remaining builds — do not abort the whole operation.
- If the `teamcity` CLI is not on PATH, tell the user to add it to their PATH or install it by following the instructions at https://www.jetbrains.com/help/teamcity/teamcity-cli.html#installing
