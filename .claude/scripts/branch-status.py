#!/usr/bin/env python3
"""
Usage: branch-status.py <branch-name> [--json]

Reports the PR approval status and TeamCity CI status for a feature branch
spanning multiple LabKey GitHub repos.

GitHub section:
  - Discovers repos with the branch (workspace paths + labkey-module-container topic)
  - For each PR: state, draft, approvals/changes-requested/pending, CI rollup, mergeability

TeamCity section:
  - Derives TC project and stripped branch from the git branch name
  - Finds the latest build per suite for that branch
  - For failed suites: failure count, failed test names, and whether each test
    also fails on the primary branch (pre-existing vs new failure)
  - Detects stale builds (branch has newer commits since the build was queued)
  - Lists suites not yet triggered on this branch (within known sub-projects)

When called without a branch argument (or with --suggest), lists candidate feature
branches from local checkouts and recent GitHub push events.

TeamCity token is read from ~/.claude/mcp.json (same as MCP server config).
Override with TEAMCITY_TOKEN env var.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional


FEATURE_BRANCH_RE = re.compile(r'^(\d+\.\d+_)?fb_')


# ---------------------------------------------------------------------------
# Shared utilities (mirrors gather-review-diff.py)
# ---------------------------------------------------------------------------

def run(*args: str, cwd: Path | None = None) -> tuple[str, bool]:
    result = subprocess.run(
        args, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
        cwd=str(cwd) if cwd else None,
    )
    return result.stdout.strip(), result.returncode == 0


def parse_owner_repo(remote_url: str) -> str:
    url = remote_url.strip().removesuffix(".git")
    m = re.match(r"git@github\.com:(.+)", url)
    if m:
        return m.group(1)
    m = re.match(r"https?://github\.com/(.+)", url)
    if m:
        return m.group(1)
    return url


def is_git_repo(path: Path) -> bool:
    _, ok = run("git", "-C", str(path), "rev-parse", "--git-dir")
    return ok


def subdirs(parent: Path) -> list[Path]:
    if not parent.is_dir():
        return []
    return sorted(d for d in parent.iterdir() if d.is_dir())


def workspace_paths(repo_root: Path) -> list[Path]:
    return [
        repo_root,
        repo_root / "server" / "testAutomation",
        *subdirs(repo_root / "server" / "modules"),
        *subdirs(repo_root / "clientAPIs"),
    ]


def get_topic_repos() -> list[str]:
    stdout, ok = run(
        "gh", "api", "search/repositories",
        "--field", "q=topic:labkey-module-container",
        "--field", "per_page=100",
        "--paginate",
        "--jq", ".items[].full_name",
    )
    if not ok or not stdout:
        return []
    return [line.strip() for line in stdout.splitlines() if line.strip()]


# ---------------------------------------------------------------------------
# GitHub PR status
# ---------------------------------------------------------------------------

@dataclass
class TaskItem:
    text: str
    checked: bool


def _parse_task_list(body: str) -> list[TaskItem]:
    """Parse GitHub-flavored markdown task list items from a PR body."""
    items = []
    for line in (body or "").splitlines():
        m = re.match(r'^\s*[-*]\s+\[([ xX])\]\s+(.*)', line)
        if m:
            items.append(TaskItem(text=m.group(2).strip(), checked=m.group(1).lower() == 'x'))
    return items


@dataclass
class LinkedIssue:
    number: int
    title: str
    url: str
    task_items: list[TaskItem] = field(default_factory=list)


@dataclass
class PRStatus:
    repo: str
    pr_url: str = ""
    pr_title: str = ""
    state: str = "NO_PR"          # OPEN MERGED CLOSED NO_PR
    is_draft: bool = False
    approved: int = 0
    changes_requested: int = 0
    pending_reviewers: list[str] = field(default_factory=list)
    mergeable: str = ""           # MERGEABLE CONFLICTING UNKNOWN
    ci_rollup: str = ""           # SUCCESS FAILURE PENDING ""
    task_items: list[TaskItem] = field(default_factory=list)
    linked_issues: list[LinkedIssue] = field(default_factory=list)


def _ci_rollup(checks: list[dict]) -> str:
    """Derive an overall CI rollup from statusCheckRollup entries."""
    if not checks:
        return ""
    states = {c.get("state", "").upper() for c in checks}
    conclusions = {c.get("conclusion", "").upper() for c in checks if c.get("conclusion")}
    failing = {"FAILURE", "ERROR", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED"}
    if "ERROR" in states or "FAILURE" in states or (conclusions & failing):
        return "FAILURE"
    if "PENDING" in states or "EXPECTED" in states or "IN_PROGRESS" in states:
        return "PENDING"
    return "SUCCESS"


def fetch_pr_status(owner_repo: str, branch: str) -> PRStatus:
    """Fetch PR status for owner_repo/branch. Returns PRStatus with state=NO_PR if no PR."""
    ps = PRStatus(repo=owner_repo)

    stdout, ok = run(
        "gh", "pr", "list",
        "--repo", owner_repo,
        "--head", branch,
        "--state", "all",
        "--json", "title,url,state,isDraft,reviews,reviewRequests,mergeable,statusCheckRollup,body,closingIssuesReferences",
        "--limit", "1",
    )
    if not ok or not stdout:
        return ps

    try:
        prs = json.loads(stdout)
    except json.JSONDecodeError:
        return ps

    if not prs:
        return ps

    pr = prs[0]
    ps.pr_url = pr.get("url", "")
    ps.pr_title = pr.get("title", "")
    ps.state = pr.get("state", "OPEN").upper()
    ps.is_draft = pr.get("isDraft", False)
    ps.mergeable = pr.get("mergeable", "").upper()
    ps.ci_rollup = _ci_rollup(pr.get("statusCheckRollup", []))
    ps.task_items = _parse_task_list(pr.get("body", "") or "")

    for ref in pr.get("closingIssuesReferences", []):
        num = ref.get("number", 0)
        if not num:
            continue
        body_out, ok = run(
            "gh", "api", f"repos/{owner_repo}/issues/{num}",
            "--jq", ".body // empty",
        )
        ps.linked_issues.append(LinkedIssue(
            number=num,
            title=ref.get("title", ""),
            url=ref.get("url", ""),
            task_items=_parse_task_list(body_out if ok else ""),
        ))

    # Use the latest review state per reviewer (last review wins).
    latest_by_reviewer: dict[str, str] = {}
    for review in pr.get("reviews", []):
        login = review.get("author", {}).get("login", "?")
        state = review.get("state", "").upper()
        if state in ("APPROVED", "CHANGES_REQUESTED"):
            latest_by_reviewer[login] = state

    for state in latest_by_reviewer.values():
        if state == "APPROVED":
            ps.approved += 1
        elif state == "CHANGES_REQUESTED":
            ps.changes_requested += 1

    ps.pending_reviewers = [
        r.get("requestedReviewer", {}).get("login", "?")
        for r in pr.get("reviewRequests", [])
        if r.get("requestedReviewer", {}).get("login")
    ]

    return ps


def _check_branch_remote(owner_repo: str, branch: str) -> Optional[str]:
    """Return owner_repo if the branch exists on GitHub, else None."""
    _, ok = run("gh", "api", f"repos/{owner_repo}/branches/{branch}")
    return owner_repo if ok else None


def _check_branch_local(path: Path, branch: str) -> Optional[str]:
    """Return owner_repo if path is a git repo containing branch, else None."""
    if not is_git_repo(path):
        return None
    remote_url, ok = run("git", "-C", str(path), "remote", "get-url", "origin")
    if not ok:
        return None
    owner_repo = parse_owner_repo(remote_url)
    _, local_ok = run("git", "-C", str(path), "rev-parse", "--verify", branch)
    if local_ok:
        return owner_repo
    remote_check, _ = run("git", "-C", str(path), "ls-remote", "--heads", "origin", branch)
    return owner_repo if remote_check else None


def collect_github_statuses(branch: str, repo_root: Path) -> list[PRStatus]:
    """Discover all repos with BRANCH and fetch their PR status in parallel."""
    paths = [p for p in workspace_paths(repo_root) if p.exists()]

    with ThreadPoolExecutor(max_workers=12) as ex:
        topic_future = ex.submit(get_topic_repos)
        local_futures = [(p, ex.submit(_check_branch_local, p, branch)) for p in paths]

        seen: set[str] = set()
        for _, f in local_futures:
            result = f.result()
            if result:
                seen.add(result)

        topic_repos = topic_future.result()
        remote_futures = [
            (r, ex.submit(_check_branch_remote, r, branch))
            for r in topic_repos if r not in seen
        ]
        for _, f in remote_futures:
            result = f.result()
            if result:
                seen.add(result)

    all_repos = sorted(seen)

    with ThreadPoolExecutor(max_workers=12) as ex:
        results = list(ex.map(lambda r: fetch_pr_status(r, branch), all_repos))

    return results


# ---------------------------------------------------------------------------
# Branch suggestion (used when no branch argument is given)
# ---------------------------------------------------------------------------

def _get_branches_for_path(path: Path) -> list[tuple[str, str, str]]:
    """Return [(branch, owner_repo, last_commit_date)] for all local feature branches in path."""
    if not is_git_repo(path):
        return []
    remote_url, ok = run("git", "-C", str(path), "remote", "get-url", "origin")
    if not ok:
        return []
    owner_repo = parse_owner_repo(remote_url)
    stdout, ok = run(
        "git", "-C", str(path),
        "for-each-ref", "--format=%(refname:short)|%(creatordate:iso-strict)",
        "refs/heads/",
    )
    if not ok or not stdout:
        return []
    results = []
    for line in stdout.splitlines():
        if "|" not in line:
            continue
        branch, date = line.split("|", 1)
        branch = branch.strip()
        if FEATURE_BRANCH_RE.match(branch):
            results.append((branch, owner_repo, date.strip()))
    return results


def collect_local_branches(repo_root: Path) -> list[dict]:
    """Return candidate feature branches from all workspace repos (all local branches, not just HEAD)."""
    paths = [p for p in workspace_paths(repo_root) if p.exists()]
    seen: dict[str, dict] = {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = [ex.submit(_get_branches_for_path, p) for p in paths]
        for f in futures:
            for branch, repo, last_commit in f.result():
                if branch not in seen:
                    seen[branch] = {"branch": branch, "repos": [repo], "last_pushed": last_commit, "sources": ["local"]}
                else:
                    if repo not in seen[branch]["repos"]:
                        seen[branch]["repos"].append(repo)
                    if last_commit > seen[branch].get("last_pushed", ""):
                        seen[branch]["last_pushed"] = last_commit
    return list(seen.values())


def collect_github_recent_branches() -> list[dict]:
    """Return recently pushed feature branches from the authenticated user's GitHub event feed."""
    login, ok = run("gh", "api", "/user", "--jq", ".login")
    if not ok or not login:
        return []
    stdout, ok = run(
        "gh", "api", f"/users/{login}/events?per_page=100",
        "--paginate",
        "--jq", '.[] | select(.type == "PushEvent") | {branch: (.payload.ref | ltrimstr("refs/heads/")), repo: .repo.name, date: .created_at}',
    )
    if not ok or not stdout:
        return []
    events = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    seen: dict[str, dict] = {}
    for event in events:
        branch = event.get("branch", "")
        if not branch or not FEATURE_BRANCH_RE.match(branch):
            continue
        repo = event.get("repo", "")
        date = event.get("date", "")
        if branch not in seen:
            seen[branch] = {"branch": branch, "repos": [repo], "last_pushed": date, "sources": ["github_recent"]}
        else:
            if repo not in seen[branch]["repos"]:
                seen[branch]["repos"].append(repo)
            if date > seen[branch].get("last_pushed", ""):
                seen[branch]["last_pushed"] = date
    return list(seen.values())


def suggest_branches(repo_root: Path) -> list[dict]:
    """Collect and rank candidate feature branches from local checkouts and GitHub activity."""
    # Capture the currently checked-out branch in the main repo so it can go first.
    current_branch, ok = run("git", "-C", str(repo_root), "rev-parse", "--abbrev-ref", "HEAD")
    current_branch = current_branch if ok and current_branch and current_branch != "HEAD" else None

    with ThreadPoolExecutor(max_workers=2) as ex:
        local_future = ex.submit(collect_local_branches, repo_root)
        github_future = ex.submit(collect_github_recent_branches)
        local_branches = local_future.result()
        github_branches = github_future.result()

    merged: dict[str, dict] = {}
    for b in local_branches:
        merged[b["branch"]] = dict(b)
    for b in github_branches:
        branch = b["branch"]
        if branch in merged:
            if "github_recent" not in merged[branch]["sources"]:
                merged[branch]["sources"].append("github_recent")
            if b.get("last_pushed", "") > merged[branch].get("last_pushed", ""):
                merged[branch]["last_pushed"] = b["last_pushed"]
            for repo in b.get("repos", []):
                if repo not in merged[branch]["repos"]:
                    merged[branch]["repos"].append(repo)
        else:
            merged[b["branch"]] = dict(b)

    # Pull out the current branch so it always appears first.
    current_entry = merged.pop(current_branch, None) if current_branch else None

    result = sorted(merged.values(), key=lambda e: e.get("last_pushed") or "", reverse=True)
    if current_entry:
        result = [current_entry] + result
    return result


# ---------------------------------------------------------------------------
# TeamCity
# ---------------------------------------------------------------------------

TC_BASE = "https://teamcity.labkey.org"


def get_tc_token() -> str:
    """Read TC Bearer token from ~/.claude/mcp.json; fall back to TEAMCITY_TOKEN env var."""
    mcp_path = Path.home() / ".claude" / "mcp.json"
    if mcp_path.exists():
        try:
            data = json.loads(mcp_path.read_text())
            for server in data.get("mcpServers", {}).values():
                url = server.get("url", "")
                auth = server.get("headers", {}).get("Authorization", "")
                if "teamcity" in url.lower() and auth.startswith("Bearer "):
                    return auth.removeprefix("Bearer ")
        except (json.JSONDecodeError, KeyError):
            pass
    return os.environ.get("TEAMCITY_TOKEN", "")


def tc_get(path: str, token: str) -> dict:
    url = f"{TC_BASE}/app/rest{path}"
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"TC API {path}: HTTP {e.code}") from e


def derive_tc_params(branch: str) -> tuple[str, str, str]:
    """
    Returns (tc_branch, tc_project_id, primary_branch).
      fb_myFeature     → myFeature,  LabKey_Trunk,       develop
      26.3_fb_Item1045 → Item1045,   LabKey_263Release,  release26.3-SNAPSHOT
    """
    m = re.match(r"^(\d+\.\d+)_fb_(.*)", branch)
    if m:
        version, suffix = m.group(1), m.group(2)
        project_id = "LabKey_" + version.replace(".", "") + "Release"
        return suffix, project_id, f"release{version}-SNAPSHOT"
    m = re.match(r"^fb_(.*)", branch)
    if m:
        return m.group(1), "LabKey_Trunk", "develop"
    return branch, "LabKey_Trunk", "develop"


@dataclass
class FailedTest:
    name: str
    fails_on_primary: Optional[bool] = None  # None = could not determine


@dataclass
class BuildStatus:
    suite_name: str
    project_name: str
    build_id: int
    build_type_id: str
    status: str    # SUCCESS FAILURE UNKNOWN NOT_STARTED
    state: str     # finished running queued not_started
    finished_at: str = ""
    queued_at: str = ""
    failure_count: int = 0
    failed_tests: list[FailedTest] = field(default_factory=list)
    has_newer_commits: Optional[bool] = None  # True = branch has commits newer than this build's queue time
    stale_repos: list[str] = field(default_factory=list)  # repos with commits newer than build queue time


def _fetch_failing_tests(build_id: int, token: str, max_tests: int = 500) -> list[str]:
    try:
        data = tc_get(
            f"/testOccurrences?locator=build:(id:{build_id}),status:FAILURE,count:{max_tests}"
            "&fields=testOccurrence(name)",
            token,
        )
        # Deduplicate while preserving order (retried tests can appear more than once).
        seen: set[str] = set()
        names: list[str] = []
        for t in data.get("testOccurrence", []):
            name = t.get("name", "")
            if name and name not in seen:
                seen.add(name)
                names.append(name)
        return names
    except RuntimeError:
        return []


def _fetch_primary_failing_tests(build_type_id: str, primary_branch: str, token: str) -> Optional[set[str]]:
    """Return the set of failing test names on the primary branch, or None if unavailable."""
    try:
        data = tc_get(
            f"/builds?locator=buildType:(id:{build_type_id}),branch:(default:true)"
            ",count:1,state:finished"
            "&fields=build(id,status)",
            token,
        )
        builds = data.get("build", [])
        if not builds:
            return None
        b = builds[0]
        if b.get("status", "").upper() == "SUCCESS":
            return set()
        primary_build_id = b["id"]
        failures = _fetch_failing_tests(primary_build_id, token, max_tests=2000)
        return set(failures)
    except RuntimeError:
        return None


def _parse_tc_offset(raw: str) -> timezone:
    """Parse the timezone offset portion of a TC date string (e.g. '-0700'), defaulting to UTC."""
    offset_str = raw[15:].strip()
    if offset_str and offset_str[0] in ('+', '-') and len(offset_str) >= 5:
        sign = 1 if offset_str[0] == '+' else -1
        hours, minutes = int(offset_str[1:3]), int(offset_str[3:5])
        return timezone(timedelta(hours=sign * hours, minutes=sign * minutes))
    return timezone.utc


def _parse_tc_date(raw: str) -> str:
    """Parse a TC date string and return it formatted in local time."""
    if raw and len(raw) >= 15:
        try:
            dt = datetime.strptime(raw[:15], "%Y%m%dT%H%M%S").replace(tzinfo=_parse_tc_offset(raw))
            return dt.astimezone().strftime("%Y-%m-%d %H:%M")
        except ValueError:
            pass
    return raw


def _parse_tc_date_to_dt(raw: str) -> Optional[datetime]:
    """Parse a TC-format date string (e.g. '20260518T154500-0700') to a timezone-aware datetime."""
    if raw and len(raw) >= 15:
        try:
            return datetime.strptime(raw[:15], "%Y%m%dT%H%M%S").replace(tzinfo=_parse_tc_offset(raw))
        except ValueError:
            pass
    return None


def _format_iso_local(s: str) -> str:
    """Convert a GitHub ISO 8601 timestamp (UTC) to local time formatted as YYYY-MM-DD HH:MM."""
    if not s:
        return s
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        return dt.astimezone().strftime("%Y-%m-%d %H:%M")
    except ValueError:
        return s


def _fetch_repo_latest_commit_date(owner_repo: str, branch: str) -> Optional[str]:
    """Return the ISO 8601 author date of the latest commit on owner_repo/branch, or None."""
    stdout, ok = run(
        "gh", "api", f"repos/{owner_repo}/branches/{branch}",
        "--jq", ".commit.commit.author.date",
    )
    return stdout.strip() if ok and stdout.strip() else None


def _get_repo_commit_dates(repos: list[str], branch: str) -> dict[str, str]:
    """Return {owner_repo: ISO date} for the latest commit on each repo's branch."""
    if not repos:
        return {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {r: ex.submit(_fetch_repo_latest_commit_date, r, branch) for r in repos}
        return {r: f.result() for r, f in futures.items() if f.result()}


def _fetch_all_build_type_ids(project_id: str, token: str) -> list[tuple[str, str, str]]:
    """Return (id, name, projectName) for every build type under project_id."""
    try:
        data = tc_get(
            f"/buildTypes?locator=affectedProject:(id:{project_id})"
            "&fields=buildType(id,name,projectName)",
            token,
        )
        return [
            (bt.get("id", ""), bt.get("name", ""), bt.get("projectName", ""))
            for bt in data.get("buildType", [])
            if bt.get("id")
        ]
    except RuntimeError:
        return []


def collect_tc_builds(tc_branch: str, tc_project_id: str, primary_branch: str, token: str) -> list[BuildStatus]:
    """Fetch the latest build per suite for tc_branch, then enrich with test failure details."""
    if not token:
        print("Warning: no TeamCity token found; skipping TC section.", file=sys.stderr)
        return []

    try:
        data = tc_get(
            f"/builds?locator=branch:(name:{tc_branch}),affectedProject:(id:{tc_project_id}),count:500"
            "&fields=build(id,buildType(id,name,projectName),status,state,finishDate,queuedDate)",
            token,
        )
    except RuntimeError as e:
        print(f"Warning: could not fetch TC builds: {e}", file=sys.stderr)
        return []

    # Keep only the latest build per buildTypeId (TC returns most-recent first).
    seen_bt: dict[str, BuildStatus] = {}
    for b in data.get("build", []):
        bt = b.get("buildType", {})
        bt_id = bt.get("id", "")
        if bt_id in seen_bt:
            continue
        seen_bt[bt_id] = BuildStatus(
            suite_name=bt.get("name", bt_id),
            project_name=bt.get("projectName", ""),
            build_id=b.get("id", 0),
            build_type_id=bt_id,
            status=b.get("status", "UNKNOWN").upper(),
            state=b.get("state", ""),
            finished_at=_parse_tc_date(b.get("finishDate", "")),
            queued_at=b.get("queuedDate", ""),
        )

    builds = list(seen_bt.values())
    failed_builds = [bs for bs in builds if bs.status == "FAILURE"]

    # Fetch test failures, primary-branch baselines, and full build-type list — all in parallel.
    with ThreadPoolExecutor(max_workers=8) as ex:
        test_futures = {
            bs.build_id: ex.submit(_fetch_failing_tests, bs.build_id, token)
            for bs in failed_builds
        }
        unique_bt_ids = {bs.build_type_id for bs in failed_builds}
        primary_futures = {
            bt_id: ex.submit(_fetch_primary_failing_tests, bt_id, primary_branch, token)
            for bt_id in unique_bt_ids
        }
        all_bt_future = ex.submit(_fetch_all_build_type_ids, tc_project_id, token)

        test_results = {bid: f.result() for bid, f in test_futures.items()}
        primary_results = {bt_id: f.result() for bt_id, f in primary_futures.items()}
        all_build_types = all_bt_future.result()

    for bs in failed_builds:
        failing_names = test_results.get(bs.build_id, [])
        primary_set = primary_results.get(bs.build_type_id)
        bs.failure_count = len(failing_names)
        for name in failing_names:
            fails_on_primary = (name in primary_set) if primary_set is not None else None
            bs.failed_tests.append(FailedTest(name=name, fails_on_primary=fails_on_primary))

    # Add NOT_STARTED entries for build types that haven't run on this branch,
    # scoped to projects where we already saw at least one build (to avoid noise).
    seen_projects = {bs.project_name for bs in builds}
    for bt_id, name, project_name in all_build_types:
        if bt_id not in seen_bt and project_name in seen_projects:
            builds.append(BuildStatus(
                suite_name=name,
                project_name=project_name,
                build_id=0,
                build_type_id=bt_id,
                status="NOT_STARTED",
                state="not_started",
            ))

    return builds


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def print_report(
    branch: str,
    tc_branch: str,
    primary_branch: str,
    github: list[PRStatus],
    builds: list[BuildStatus],
    latest_commit_date: Optional[str] = None,
) -> None:
    print(f"Branch: {branch}   TC branch: {tc_branch}   Primary: {primary_branch}")
    if latest_commit_date:
        print(f"Latest branch commit: {_format_iso_local(latest_commit_date)}")
    print()

    print("=== GitHub PRs ===")
    if not github:
        print("  No repos found with this branch.")
    else:
        for ps in sorted(github, key=lambda x: x.repo):
            if ps.state == "NO_PR":
                print(f"  {ps.repo:<52}  no PR")
                continue

            review_parts = []
            if ps.approved:
                review_parts.append(f"{ps.approved} approved")
            if ps.changes_requested:
                review_parts.append(f"{ps.changes_requested} changes requested")
            if ps.pending_reviewers:
                review_parts.append(f"pending: {', '.join(ps.pending_reviewers)}")
            review_str = ", ".join(review_parts) or "no reviews"

            flags = []
            if ps.is_draft:
                flags.append("DRAFT")
            if ps.mergeable == "CONFLICTING":
                flags.append("CONFLICT")
            if ps.ci_rollup == "FAILURE":
                flags.append("CI:FAIL")
            elif ps.ci_rollup == "PENDING":
                flags.append("CI:PENDING")
            flag_str = f"  [{', '.join(flags)}]" if flags else ""

            print(f"  {ps.repo:<52}  {ps.state}  {review_str}{flag_str}")
            print(f"    {ps.pr_title}")
            print(f"    {ps.pr_url}")
    print()

    print("=== TeamCity Builds ===")
    active = [bs for bs in builds if bs.state in ("running", "queued")]
    finished = [bs for bs in builds if bs.state == "finished"]
    not_started = [bs for bs in builds if bs.status == "NOT_STARTED"]

    if not builds:
        print("  No builds found.")
        return

    if active:
        print("  -- In Progress --")
        for bs in sorted(active, key=lambda x: x.suite_name):
            mark = "RUN " if bs.state == "running" else "WAIT"
            print(f"  [{mark}] {bs.suite_name}")
        print()

    if finished:
        print("  -- Finished --")
        for bs in sorted(finished, key=lambda x: (0 if x.status == "FAILURE" else 1, x.suite_name)):
            mark = {"SUCCESS": "PASS", "FAILURE": "FAIL"}.get(bs.status, bs.status[:4])
            stale_tag = "  [stale]" if bs.has_newer_commits else ""
            print(f"  [{mark}] {bs.suite_name:<62} {bs.finished_at}{stale_tag}")

            if bs.status == "FAILURE":
                if not bs.failed_tests:
                    print(f"         {bs.failure_count} failure(s) — test names unavailable")
                else:
                    new_failures = [ft for ft in bs.failed_tests if ft.fails_on_primary is False]
                    preexisting = [ft for ft in bs.failed_tests if ft.fails_on_primary is True]
                    unknown = [ft for ft in bs.failed_tests if ft.fails_on_primary is None]

                    if new_failures:
                        print(f"         NEW failures ({len(new_failures)}):")
                        for ft in new_failures:
                            print(f"           - {ft.name}")
                    if preexisting:
                        print(f"         Pre-existing on {primary_branch} ({len(preexisting)}):")
                        for ft in preexisting:
                            print(f"           - {ft.name}")
                    if unknown:
                        print(f"         Unknown status ({len(unknown)}):")
                        for ft in unknown:
                            print(f"           - {ft.name}")

    if not_started:
        print()
        print(f"  -- Not Yet Triggered ({len(not_started)}) --")
        for bs in sorted(not_started, key=lambda x: x.suite_name):
            print(f"  [ --- ] {bs.suite_name}")


def to_dict(
    branch: str,
    tc_branch: str,
    primary_branch: str,
    github: list[PRStatus],
    builds: list[BuildStatus],
    latest_commit_date: Optional[str] = None,
) -> dict:
    return {
        "branch": branch,
        "tc_branch": tc_branch,
        "primary_branch": primary_branch,
        "latest_branch_commit_date": _format_iso_local(latest_commit_date) if latest_commit_date else None,
        "github": [
            {
                "repo": ps.repo,
                "pr_url": ps.pr_url,
                "pr_title": ps.pr_title,
                "state": ps.state,
                "is_draft": ps.is_draft,
                "approved": ps.approved,
                "changes_requested": ps.changes_requested,
                "pending_reviewers": ps.pending_reviewers,
                "mergeable": ps.mergeable,
                "ci_rollup": ps.ci_rollup,
                "task_items": [
                    {"text": ti.text, "checked": ti.checked}
                    for ti in ps.task_items
                ],
                "linked_issues": [
                    {
                        "number": li.number,
                        "title": li.title,
                        "url": li.url,
                        "task_items": [
                            {"text": ti.text, "checked": ti.checked}
                            for ti in li.task_items
                        ],
                    }
                    for li in ps.linked_issues
                ],
            }
            for ps in sorted(github, key=lambda x: x.repo)
        ],
        "teamcity": [
            {
                "suite_name": bs.suite_name,
                "project_name": bs.project_name,
                "build_id": bs.build_id,
                "build_type_id": bs.build_type_id,
                "status": bs.status,
                "state": bs.state,
                "finished_at": bs.finished_at,
                "queued_at": _parse_tc_date(bs.queued_at) if bs.queued_at else "",
                "has_newer_commits": bs.has_newer_commits,
                "stale_repos": bs.stale_repos,
                "failure_count": bs.failure_count,
                "failed_tests": [
                    {"name": ft.name, "fails_on_primary": ft.fails_on_primary}
                    for ft in bs.failed_tests
                ],
            }
            for bs in sorted(
                builds,
                key=lambda x: (
                    0 if x.status == "FAILURE" else
                    1 if x.state in ("running", "queued") else
                    2 if x.has_newer_commits else
                    3 if x.status == "NOT_STARTED" else
                    4,
                    x.suite_name,
                ),
            )
        ],
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Report PR and CI status for a feature branch across all LabKey repos."
    )
    parser.add_argument("branch", nargs="?",
                        help="Feature branch name (e.g. fb_fixNPE or 26.3_fb_fixNPE). "
                             "Omit to list candidate branches.")
    parser.add_argument("--json", dest="as_json", action="store_true",
                        help="Output structured JSON instead of human-readable text")
    parser.add_argument("--suggest", action="store_true",
                        help="List candidate branches from local checkouts and GitHub activity, then exit")
    args = parser.parse_args()

    repo_root_str, ok = run("git", "rev-parse", "--show-toplevel")
    if not ok:
        print("Error: not inside a git repository", file=sys.stderr)
        sys.exit(1)
    repo_root = Path(repo_root_str)

    if args.suggest or not args.branch:
        candidates = suggest_branches(repo_root)
        if args.as_json:
            print(json.dumps({"candidates": candidates}, indent=2))
        else:
            if not candidates:
                print("No candidate feature branches found.")
                print("Run with a branch name: branch-status.py <branch>")
            else:
                print("Candidate feature branches:")
                print()
                for c in candidates:
                    sources = "+".join(c["sources"])
                    repos = ", ".join(c["repos"][:2])
                    date = (c.get("last_pushed") or "")[:10]
                    print(f"  {c['branch']:<55}  {sources:<20}  {repos}  {date}")
        return

    tc_branch, tc_project_id, primary_branch = derive_tc_params(args.branch)
    token = get_tc_token()

    with ThreadPoolExecutor(max_workers=2) as ex:
        github_future = ex.submit(collect_github_statuses, args.branch, repo_root)
        tc_future = ex.submit(collect_tc_builds, tc_branch, tc_project_id, primary_branch, token)
        github = github_future.result()
        builds = tc_future.result()

    # Mark stale builds: which repos have commits newer than each build's queue time?
    repos_with_branch = [ps.repo for ps in github if ps.state != "NO_PR"]
    repo_commit_dates = _get_repo_commit_dates(repos_with_branch, args.branch)
    latest_commit_date = max(repo_commit_dates.values()) if repo_commit_dates else None

    if repo_commit_dates:
        for bs in builds:
            if bs.state == "finished" and bs.queued_at:
                queued_dt = _parse_tc_date_to_dt(bs.queued_at)
                if queued_dt:
                    stale = []
                    for repo, date_str in repo_commit_dates.items():
                        try:
                            if datetime.fromisoformat(date_str.replace("Z", "+00:00")) > queued_dt:
                                stale.append(repo)
                        except ValueError:
                            pass
                    bs.stale_repos = sorted(stale)
                    bs.has_newer_commits = bool(stale)

    if args.as_json:
        print(json.dumps(to_dict(args.branch, tc_branch, primary_branch, github, builds, latest_commit_date), indent=2))
    else:
        print_report(args.branch, tc_branch, primary_branch, github, builds, latest_commit_date)


if __name__ == "__main__":
    main()
