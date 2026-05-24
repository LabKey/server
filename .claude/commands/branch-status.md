Report the PR approval and CI status for a feature branch across all LabKey repos.

$ARGUMENTS is an optional feature branch name matching `fb_*` or `\d+\.\d+_fb_*`,
optionally followed by flags:
- `--monitor` — after reporting, loop: re-check every few minutes and report changes
- `--fix` — automatically apply fixes for new compilation/API failures and push them

Examples:
- `/branch-status fb_myFeature`
- `/branch-status fb_myFeature --monitor`
- `/branch-status fb_myFeature --monitor --fix`

Parse these flags out of $ARGUMENTS before using the branch name in Step 1.

## Step 0: Pick a branch (only when $ARGUMENTS is empty)

Find the repo root: `git rev-parse --show-toplevel` (call it REPO_ROOT).

Run:
```
python3 REPO_ROOT/.claude/scripts/branch-status.py --suggest
```

This prints a plain-text table. Each data line has four space-aligned columns: branch name, source tags (`local`, `github_recent`, or `local+github_recent`), up to two repos, and the last-pushed date (YYYY-MM-DD). If the output starts with "No candidate feature branches found." there are no candidates.

- If there are no candidates, tell the user to re-run with a branch name and stop.
- If there is exactly 1 data line, use that branch directly — do not call `AskUserQuestion`. State which branch was auto-selected and proceed to Step 1.
- If there are 2–4 data lines, use `AskUserQuestion` to let the user pick. Label each option as the branch name; describe it as `{first repo} — {date} ({sources})`.
- If there are 5 or more data lines, show only the first 4.

Use the selected (or auto-selected) branch as $ARGUMENTS and continue to Step 1.

## Step 1: Gather data

Find the repo root if not already known: `git rev-parse --show-toplevel` (call it REPO_ROOT).

Run (using only the branch name, not the `--monitor`/`--fix` flags):
```
python3 REPO_ROOT/.claude/scripts/branch-status.py <branch-name>
```

The default output is a full human-readable report: PR state, approval counts, task list items, and TC builds with new vs pre-existing failure classification and build IDs. Use the build IDs in the output to call `teamcity_build_log` when investigating failures.

For the monitor loop (Step 3), use `--summary` instead for a compact single-screen digest. If any needed data is missing from the text output, add a flag to the script rather than adding ad-hoc parsing.

The script discovers all repos with that branch (local workspace + GitHub `labkey-module-container` topic repos), fetches PR metadata in parallel via `gh`, and queries TeamCity for the latest build per suite. For failed suites it also fetches the failing test names and checks whether each test also fails on the primary branch. It also detects stale builds (newer commits exist since the build was queued) and lists suites within known sub-projects that haven't been triggered yet.

## Step 2: Summarize

Read the text output and produce a report with two sections.

### GitHub PRs

For each repo with a PR, show: repo name, PR title, approval state (N approved / M changes requested / K pending reviewers), CI rollup, draft status, and a "Ready" column. Group repos with no PR separately.

The "Ready" column reflects whether this PR is ready to merge, combining GitHub mergeability and task list completion:
- "Yes" — GitHub says MERGEABLE and no unchecked blocker tasks (or no task list)
- "Tasks pending" — GitHub says MERGEABLE but has unchecked blocker tasks
- "Conflicts" — GitHub says CONFLICTING (merge conflict), regardless of tasks
- "Unknown" — GitHub merge state is unknown

For the CI column, use title case: Success, Failure, Pending — not all caps.

Call out blockers explicitly: PRs needing approvals, PRs with changes requested, CI failures, draft PRs that need to be un-drafted, merge conflicts, and unchecked blocker tasks.

For each PR, the report includes task list items (from the PR body and any linked issues), shown as `[x]`/`[ ]` lines with an optional `[Issue #N]` source label when both PR and issue items are present. List unchecked items grouped by whether they are blockers or deferrable.

- **Deferrable** (OK to be incomplete at merge time): "TeamCity verify and merge" or similar CI-trigger tasks; "user education handoff", "customer comms", "documentation handoff", or any post-merge communication/education step.
- **Blockers** (must be complete before merge): "manual test", "manual testing", "automated test", "write test/tests", "QA", or any other pre-merge validation step. When in doubt, treat an unchecked item as a blocker.

Note: LabKey PRs are often approved during code review, which may happen before testing is complete. An approval does not imply testing is done — check the task list explicitly.

### TeamCity Builds

The report shows suites grouped into finished (PASS/FAIL), in-progress (RUN/WAIT), and not-yet-triggered. FAIL suites list their failures as NEW or Pre-existing, and mark stale builds with `[stale]`. Group your output into four categories (show non-empty categories only):

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

### Investigate New Failures

After the Overall Assessment, always investigate suites with new failures. For each suite marked FAIL, check whether it has any **NEW failures** (labeled `NEW` in the output, meaning not pre-existing on the primary branch). For each such suite, fetch the build log using the build ID shown in the report:

```
teamcity_build_log(buildId=<build_id>, filter="errors", count=100)
```

**Note:** For some builds, `filter="errors"` causes the TC API to return `"Not all of the messages can be shown because the build log is corrupted"` even though the log is viewable in the browser. If you get that message, fall back to fetching test failure details via the REST API instead:
```
teamcity_rest_get(path=/app/rest/testOccurrences, query=locator=build:(id:<build_id>),status:FAILURE&fields=testOccurrence(name,details))
```

Diagnose the root cause from the error output. Common patterns and how to handle them:

- **Compilation error — cannot find symbol / method not found**: A method or class was removed or renamed on this branch. Search the codebase for the broken call site (use `mcp__intellij-index__ide_find_references` or `grep -rn` across `server/modules/`) and migrate it to the new API. Check all modules, not just the one that appeared in the build log — the same removed API may be called from multiple places.
- **Test assertion failure**: Read the failing test class to understand what it asserts, then read the relevant production code changes (`git diff develop..HEAD`) to identify what broke.
- **Gradle / build configuration error**: Read the relevant `build.gradle` or `gradle.properties` for missing dependency or task configuration.
- **Selenium / UI failures** These are often pre-existing flaky tests. Before investigating further or applying any fix, assess whether the failure is caused by this branch:
  1. **Check the Test Status document** — use the Google Drive MCP tool (`mcp__claude_ai_Google_Drive__read_file_content`) with file ID `1OlGrfThVa6CHJMQ0a4LNbRvlwLyVacMFzp57ehufFFA`. Search the returned content for the test class name. If it appears in an "Intermittent failures" section, it is a known flaky test — do not investigate further or apply a fix.
  2. **Check TC history across all branches** — query test occurrences using the full TC name format (including the `"org.labkey.test.Runner: "` prefix): `teamcity_rest_get(path=/app/rest/testOccurrences, query=locator=test:(name:org.labkey.test.Runner: <FullyQualified.TestName>),affectedProject:(id:LabKey_Trunk),count:15&fields=testOccurrence(id,status,build(id,buildType(name),finishDate,branch(name))))`. If there are FAILURE results on other branches or dates prior to this branch's first commit, the test may be flaky. Check for at least one prior failure with a similar result, such as the error message, stack trace, duration until failure, etc.

If `--fix` was passed: apply the fix, commit to the appropriate subrepo on the feature branch, and push. Use the commit message format `Migrate <old API> callers to <new API>` (or equivalent). After pushing, note that TeamCity will pick up the fix on the next build trigger. Do **not** push fixes for flaky Selenium tests — those require test code changes tracked separately.

If `--fix` was not passed: describe the fix in detail (which file, which lines, what the replacement call should be) but do not modify any files.

Do not investigate suites where all failures are labeled Pre-existing — those pre-existed this branch and don't need attention.

---

## Step 3: Monitor Mode (only when `--monitor` is in $ARGUMENTS)

After completing the Step 2 report (including the failure investigation), if `--monitor` was passed, do the following before ending your turn.

### Send a push notification on meaningful change

After each re-check (not the first run), compare the current results to the previous iteration and send a `PushNotification` if something meaningful changed:

- A previously failing suite now passes → "fb_X: <suite> now passing"
- A new failure appeared that wasn't there before → "fb_X: new failure in <suite> — <test name>"
- A fix was applied and pushed (when `--fix` is active) → "fb_X: fix pushed for <suite>"
- All suites now passing and PRs approved → "fb_X: all green, ready to merge"

Keep the message under 200 characters. Do **not** notify for: routine progress with no change, suites moving from not_started to in-progress, or stale/staleness changes alone.

### Schedule the next wakeup

Choose the delay based on the current build state:

| Condition | Delay |
|---|-------|
| Any suite has `state: running` or `state: queued` | 270s  |
| All suites are finished or not_started, at least one result exists | 900s  |
| No suites triggered yet (all `state: not_started`) | 1200s |

Call `ScheduleWakeup` with:
- `delaySeconds`: per the table above
- `reason`: one sentence describing the current state (e.g. "3 suites still running, checking back soon")
- `prompt`: the exact slash command to re-enter this skill, preserving the branch name and flags — `/branch-status <branch> --monitor` (include `--fix` if it was originally passed)

The loop runs until the user explicitly asks you to stop.

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
