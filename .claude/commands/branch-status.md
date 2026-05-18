Report the PR approval and CI status for a feature branch across all LabKey repos.

$ARGUMENTS is an optional feature branch name matching `fb_*` or `\d+\.\d+_fb_*`
(e.g. `fb_fixNPE` or `26.3_fb_Item1045`). If omitted, run Step 0 first.

## Step 0: Pick a branch (only when $ARGUMENTS is empty)

Find the repo root: `git rev-parse --show-toplevel` (call it REPO_ROOT).

Run:
```
python3 REPO_ROOT/.claude/scripts/branch-status.py --suggest --json
```

This returns `{"candidates": [...]}`. Each candidate has:
- `branch`: the branch name
- `repos`: repos where it was found (list of `owner/repo` strings)
- `last_pushed`: ISO 8601 date of most recent commit or push event
- `sources`: list containing `"local"` (currently checked out in workspace) and/or `"github_recent"` (found in recent GitHub push events)

- If there are no candidates, tell the user to re-run with a branch name and stop.
- If there is exactly 1 candidate, use it directly — do not call `AskUserQuestion`. State which branch was auto-selected and proceed to Step 1.
- If there are 2–4 candidates, use `AskUserQuestion` to let the user pick. Label each option as the branch name; describe it as `{repos[0]} — {last_pushed[:10]} ({sources joined with "+"})`.
- If there are 5 or more candidates, show only the first 4.

Use the selected (or auto-selected) branch as $ARGUMENTS and continue to Step 1.

## Step 1: Gather data

Find the repo root if not already known: `git rev-parse --show-toplevel` (call it REPO_ROOT).

Run:
```
python3 REPO_ROOT/.claude/scripts/branch-status.py $ARGUMENTS --json
```

The script discovers all repos with that branch (local workspace + GitHub `labkey-module-container` topic repos), fetches PR metadata in parallel via `gh`, and queries TeamCity for the latest build per suite. For failed suites it also fetches the failing test names and checks whether each test also fails on the primary branch. It also detects stale builds (newer commits exist since the build was queued) and lists suites within known sub-projects that haven't been triggered yet.

## Step 2: Summarize

Parse the JSON and produce a report with two sections.

### GitHub PRs

For each repo with a PR, show: repo name, PR title, approval state (N approved / M changes requested / K pending reviewers), CI rollup, draft status, and mergeability. Group repos with no PR separately.

Call out blockers explicitly: PRs needing approvals, PRs with changes requested, CI failures, draft PRs that need to be un-drafted, and merge conflicts.

### TeamCity Builds

The JSON includes a top-level `latest_branch_commit_date` (ISO 8601) — this is the most recent push across all repos with the branch. Each suite entry has:
- `state`: `finished`, `running`, `queued`, or `not_started`
- `status`: `SUCCESS`, `FAILURE`, `UNKNOWN`, or `NOT_STARTED`
- `has_newer_commits`: `true` if the branch received commits after this build was queued (result may not reflect latest changes), `false` if the build is current, `null` if unknown
- `queued_at`: TC-format timestamp of when the build was queued

Group suites into four categories (show non-empty categories only):

1. **In Progress** (`state: running` or `state: queued`) — actively building; results pending
2. **Failures** (`status: FAILURE`, `state: finished`) — show first; for each:
   - Total failure count
   - **New failures** (`fails_on_primary: false`) — require immediate attention
   - **Pre-existing failures** (`fails_on_primary: true`) — not caused by this branch
   - Tests with unknown primary-branch status
   - Note if `has_newer_commits: true` — result may be outdated
3. **Passing** (`status: SUCCESS`, `state: finished`) — list with a stale warning if `has_newer_commits: true`
4. **Not Yet Triggered** (`status: NOT_STARTED`) — suites in the same sub-projects as known builds that have never run on this branch; list as a count with names

### Overall Assessment

End with a one-paragraph verdict: is this branch ready to merge? Factor in whether the shown results are current (`has_newer_commits`) or whether builds are still in progress. If not ready, state specifically what needs to happen first.
