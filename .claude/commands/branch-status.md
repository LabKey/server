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

For each repo with a PR, show: repo name, PR title, approval state (N approved / M changes requested / K pending reviewers), CI rollup, draft status, and a "Ready" column. Group repos with no PR separately.

The "Ready" column reflects whether this PR is ready to merge, combining GitHub mergeability and task list completion:
- "Yes" — GitHub says MERGEABLE and no unchecked blocker tasks (or no task list)
- "Tasks pending" — GitHub says MERGEABLE but has unchecked blocker tasks
- "Conflicts" — GitHub says CONFLICTING (merge conflict), regardless of tasks
- "Unknown" — GitHub merge state is unknown

For the CI column, use title case: Success, Failure, Pending — not all caps.

Call out blockers explicitly: PRs needing approvals, PRs with changes requested, CI failures, draft PRs that need to be un-drafted, merge conflicts, and unchecked blocker tasks.

For each PR that has task list items (from `task_items` in the JSON) or linked issues with task items (from `linked_issues[].task_items`), list unchecked items grouped by whether they are blockers or deferrable. Show PR task items and linked issue task items together, labelling the source when both are present (e.g. "PR task list" vs "Issue #123 task list").

- **Deferrable** (OK to be incomplete at merge time): "TeamCity verify and merge" or similar CI-trigger tasks; "user education handoff", "customer comms", "documentation handoff", or any post-merge communication/education step.
- **Blockers** (must be complete before merge): "manual test", "manual testing", "automated test", "write test/tests", "QA", or any other pre-merge validation step. When in doubt, treat an unchecked item as a blocker.

Note: LabKey PRs are often approved during code review, which may happen before testing is complete. An approval does not imply testing is done — check the task list explicitly.

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
   - If `has_newer_commits: true`, note which repos had newer commits (from `stale_repos`) — result may be outdated
3. **Passing** (`status: SUCCESS`, `state: finished`) — list with a stale note if `has_newer_commits: true`; for stale builds include which repos had newer commits (from `stale_repos`)
4. **Not Yet Triggered** (`status: NOT_STARTED`) — suites in the same sub-projects as known builds that have never run on this branch; show only the count, not the individual names

### Overall Assessment

End with a one-paragraph verdict: is this branch ready to merge? Factor in: PR approvals, CI/TC results (and whether they are current or stale), and PR task list completion. Call out any unchecked blocker tasks (manual test, automated test, etc.) as merge blockers even if the PR is already approved. Deferrable tasks (TeamCity verify and merge, user education handoff, etc.) should not block the verdict. If not ready, state specifically what needs to happen first.

---

## Example Output

### GitHub PRs

| Repo | PR | Approvals | CI | Ready |
|---|---|---|---|---|
| LabKey/platform | [#7673 — Prevent popup widget from wrapping](url) | 1 approved | Success | Tasks pending |
| LabKey/targetedms | [#1208 — flat protein/molecule list](url) | 1 approved | Success | Yes |
| LabKey/testAutomation | [#3003 — Remove unused deprecated method](url) | 1 approved | Success | Yes |

**LabKey/platform task list:**
- [x] Code review
- [x] Manual test
- [ ] TeamCity verify and merge *(deferrable)*

**LabKey/targetedms task list:**
- [x] Code review
- [ ] Manual test *(blocker)*
- [ ] User education handoff *(deferrable)*

Blockers: LabKey/targetedms — manual test not yet checked off.

---

### TeamCity Builds

**Failures** (1)

- MS2 sqlserver — 1 new failure (STALE: newer commits exist since build)
  - org.labkey.test.tests.ms2.CometTest.testSteps

**Passing** — current (queued after latest commit 2026-05-18 21:07)

- Panorama [A] postgres
- Panorama [B] postgres
- Upgrade from 25.11
- Upgrade from 25.7
- Upgrade setup
- Upgrade validation
- build (Premium)

**Passing** — stale (queued before latest commit)

- BVT-EHR postgres
- BVT-EHR sqlserver
- MS2 Postgres
- Verify Java Build
- Verify Test Build
- build (Community)
- build_ehr

**Not Yet Triggered** (71 suites)

---

### Overall Assessment

Not ready to merge. The LabKey/targetedms PR has an unchecked manual test task. The MS2 sqlserver suite also shows a new failure (CometTest) though that build is stale — it should be re-run after the latest commits before merging.
