#!/usr/bin/env python3
"""
Usage: gather-review-diff.py <branch-name> [--skip owner/repo]

For every repo in the LabKey workspace that has BRANCH:
  Pass 1 – check existence and fetch changed-file counts via the GitHub compare API.
  Pass 2 – print a summary table, then for each repo stream any PR title/description
           as a preamble followed by the raw diff.

Local repos checked: REPO_ROOT, server/testAutomation, server/modules/*, clientAPIs/*
Remote repos checked: all GitHub repos tagged with the topic "labkey-module-container"
  that are not already covered by a local checkout.

--skip owner/repo   Mark one repo already covered (e.g. by `gh pr diff`) so it
                    appears in the summary but is not re-diffed.
"""

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


def run(*args: str, cwd: Path | None = None) -> tuple[str, bool]:
    """Run a subprocess and return (stdout.strip(), success). Never raises."""
    result = subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(cwd) if cwd else None,
    )
    return result.stdout.strip(), result.returncode == 0


def parse_owner_repo(remote_url: str) -> str:
    url = remote_url.strip().removesuffix(".git")
    # SSH:   git@github.com:owner/repo
    m = re.match(r"git@github\.com:(.+)", url)
    if m:
        return m.group(1)
    # HTTPS: https://github.com/owner/repo
    m = re.match(r"https?://github\.com/(.+)", url)
    if m:
        return m.group(1)
    return url


@dataclass
class PRInfo:
    """
    Everything we know about how a branch relates to a PR in a specific repo.

    base_branch is always set — it's what we diff against.
    title and body are empty strings when no open PR was found for this branch in
    this repo.  When present, they're printed as a preamble before the diff so
    the reviewer gets intent context for every repo in the change set, not just
    the one they started from.
    """
    base_branch: str
    title: str = ""
    body: str = ""


def resolve_base_branch(owner_repo: str, branch: str) -> PRInfo | None:
    """
    Determine the comparison base for owner_repo/branch and collect any open PR
    metadata along the way.  Returns None only on hard failure (e.g. can't reach
    the API to learn even the default branch).

    Priority order for the base branch:
      1. An open PR whose head is this branch — also captures title and body so
         the caller doesn't need a second round-trip for PR context.
      2. Inferred from a version prefix in the branch name
         (e.g. 26.3_fb_* → release26.3-SNAPSHOT).  No PR metadata via this path.
      3. The repo's default branch as a last resort.
    """
    # Step 1: look for an open PR with this branch as its head.
    # We fetch baseRefName, title, and body in one call so we don't need to make
    # a separate `gh pr view` later when printing the diff preamble.
    stdout, ok = run(
        "gh", "pr", "list",
        "--repo", owner_repo,
        "--head", branch,
        "--json", "baseRefName,title,body",
    )
    if ok and stdout:
        try:
            prs = json.loads(stdout)
            if prs:
                pr = prs[0]
                base = pr.get("baseRefName", "")
                if base:
                    return PRInfo(
                        base_branch=base,
                        title=pr.get("title", ""),
                        body=pr.get("body", ""),
                    )
        except json.JSONDecodeError:
            pass

    # Step 2: infer from a version prefix in the branch name.
    m = re.match(r"^(\d+\.\d+)_fb_", branch)
    if m:
        return PRInfo(base_branch=f"release{m.group(1)}-SNAPSHOT")

    # Step 3: fall back to the repo's default branch.
    default, ok = run("gh", "api", f"repos/{owner_repo}", "--jq", ".default_branch")
    if ok and default:
        return PRInfo(base_branch=default)

    return None


def _finish_collect(
    owner_repo: str, branch: str, pr_info: PRInfo | None
) -> tuple[str | None, str, PRInfo | None]:
    """
    Shared tail of collect_repo / collect_remote_repo, called once branch
    existence is confirmed and PR info has been resolved.

    Fetches the changed-file count and builds the one-line summary status.
    Returns (owner_repo, status_line, pr_info); pr_info is None on failure.
    """
    if pr_info is None:
        return owner_repo, "error: could not fetch repo metadata", None

    file_count_str, ok = run(
        "gh", "api",
        f"repos/{owner_repo}/compare/{pr_info.base_branch}...{branch}",
        "--jq", ".files | length",
    )
    file_count = file_count_str if ok and file_count_str else "?"

    # Include the PR title in the summary line when one exists, so the table
    # gives a quick-read of intent alongside the file count.
    status = f"{file_count} files changed  (base: {pr_info.base_branch})"
    if pr_info.title:
        status += f"  —  {pr_info.title}"

    return owner_repo, status, pr_info


def collect_repo(
    path: Path, branch: str, skip_repo: str
) -> tuple[str | None, str, PRInfo | None]:
    """
    Check a locally cloned repo for BRANCH.

    Returns (owner_repo, status_line, pr_info).
    owner_repo is None  → silently skip (not a git repo, no remote, etc.).
    pr_info is None     → repo appears in summary only (skipped or error), not diffed.
    """
    _, ok = run("git", "-C", str(path), "rev-parse", "--git-dir")
    if not ok:
        return None, "", None

    remote_url, ok = run("git", "-C", str(path), "remote", "get-url", "origin")
    if not ok:
        return None, "", None

    owner_repo = parse_owner_repo(remote_url)

    if skip_repo and owner_repo == skip_repo:
        # This repo's diff is already provided by `gh pr diff` in the calling
        # command; we acknowledge it in the summary but don't re-diff it.
        return owner_repo, "covered by PR diff", None

    # Check locally first (no network), then fall back to ls-remote.
    _, local_ok = run("git", "-C", str(path), "rev-parse", "--verify", branch)
    if local_ok:
        has_branch = True
    else:
        remote_check, _ = run(
            "git", "-C", str(path), "ls-remote", "--heads", "origin", branch
        )
        has_branch = bool(remote_check)

    if not has_branch:
        return None, "", None

    return _finish_collect(owner_repo, branch, resolve_base_branch(owner_repo, branch))


def collect_remote_repo(
    owner_repo: str, branch: str, skip_repo: str
) -> tuple[str | None, str, PRInfo | None]:
    """
    Check a GitHub repo that is NOT cloned locally for BRANCH.
    Same return contract as collect_repo.
    """
    if skip_repo and owner_repo == skip_repo:
        return owner_repo, "covered by PR diff", None

    # A 200 response confirms the branch exists; 404 means it doesn't.
    _, ok = run("gh", "api", f"repos/{owner_repo}/branches/{branch}")
    if not ok:
        return None, "", None

    return _finish_collect(owner_repo, branch, resolve_base_branch(owner_repo, branch))


def get_topic_repos() -> list[str]:
    """
    Return all owner/repo strings tagged 'labkey-module-container' that the
    authenticated user can see (public + private).  Returns [] on any error.

    The GitHub search API includes private repos the caller has access to, so
    this covers internal LabKey modules that aren't publicly listed.
    """
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


def subdirs(parent: Path) -> list[Path]:
    if not parent.is_dir():
        return []
    return sorted(d for d in parent.iterdir() if d.is_dir())


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect diffs for a branch across the LabKey multi-repo workspace."
    )
    parser.add_argument("branch", help="Feature branch name")
    parser.add_argument(
        "--skip", metavar="OWNER/REPO", default="",
        help="Omit one repo already covered by a PR diff",
    )
    args = parser.parse_args()

    branch: str = args.branch
    skip_repo: str = args.skip

    repo_root_str, ok = run("git", "rev-parse", "--show-toplevel")
    if not ok:
        print("Error: not inside a git repository", file=sys.stderr)
        sys.exit(1)
    repo_root = Path(repo_root_str)

    candidate_paths: list[Path] = [
        repo_root,
        repo_root / "server" / "testAutomation",
        *subdirs(repo_root / "server" / "modules"),
        *subdirs(repo_root / "clientAPIs"),
    ]

    summary: list[tuple[str, str]] = []          # (owner_repo, status_line)
    diff_targets: list[tuple[str, PRInfo]] = []  # repos to diff, with PR context
    seen: set[str] = set()                       # owner_repo values already processed

    # Pass 1a: locally cloned repos.
    for path in candidate_paths:
        if not path.exists():
            continue
        owner_repo, status, pr_info = collect_repo(path, branch, skip_repo)
        if owner_repo is None:
            continue
        seen.add(owner_repo)
        summary.append((owner_repo, status))
        if pr_info is not None:
            diff_targets.append((owner_repo, pr_info))

    # Pass 1b: GitHub repos tagged labkey-module-container that aren't cloned locally.
    # These are checked purely via the GitHub API — no local git operations needed.
    for owner_repo in get_topic_repos():
        if owner_repo in seen:
            # Already handled in pass 1a via the local checkout; skip to avoid
            # a duplicate summary entry and a redundant API diff fetch.
            continue
        seen.add(owner_repo)
        owner_repo_out, status, pr_info = collect_remote_repo(owner_repo, branch, skip_repo)
        if owner_repo_out is None:
            continue
        summary.append((owner_repo_out, status))
        if pr_info is not None:
            diff_targets.append((owner_repo_out, pr_info))

    # Print summary table.
    print(f"=== Repos examined for branch: {branch} ===")
    for repo, status in summary:
        print(f"  {repo:<45} {status}")
    print()

    # Pass 2: for each repo, print any PR context as a preamble then stream the diff.
    # The preamble gives the reviewer intent context per repo without needing to
    # look up each PR separately.
    #
    # Note: when called from /review-lk with a PR URL, the primary repo is passed
    # via --skip (its description is already in context from `gh pr view`), so the
    # preamble only appears for secondary repos in that flow.  In bare-branch mode
    # it appears for every repo that has an open PR for the branch.
    for owner_repo, pr_info in diff_targets:
        print(f"=== {owner_repo}  branch: {branch}  base: {pr_info.base_branch} ===")

        if pr_info.title:
            print(f"PR: {pr_info.title}")
        if pr_info.body and pr_info.body.strip():
            print()
            print(pr_info.body.strip())
        print()

        result = subprocess.run(
            [
                "gh", "api",
                f"repos/{owner_repo}/compare/{pr_info.base_branch}...{branch}",
                "-H", "Accept: application/vnd.github.v3.diff",
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if result.stdout:
            print(result.stdout, end="")
        print()


if __name__ == "__main__":
    main()
