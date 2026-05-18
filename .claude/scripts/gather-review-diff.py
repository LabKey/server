#!/usr/bin/env python3
"""
Usage: gather-review-diff.py <branch-name> [--skip owner/repo]
       gather-review-diff.py --pr-url <GitHub-PR-URL>
       gather-review-diff.py --local

Branch mode: for every repo in the LabKey workspace that has BRANCH:
  Pass 1 – check existence and fetch changed-file counts via the GitHub compare API.
  Pass 2 – print a summary table, then for each repo stream any PR title/description
           as a preamble followed by the raw diff.

  Local repos checked: REPO_ROOT, server/testAutomation, server/modules/*, clientAPIs/*
  Remote repos checked: all GitHub repos tagged with the topic "labkey-module-container"
    that are not already covered by a local checkout.

  --skip owner/repo   Mark one repo already covered (e.g. by `gh pr diff`) so it
                      appears in the summary but is not re-diffed.

PR URL mode (--pr-url): accept a GitHub PR URL; fetch all metadata and diffs internally.
  Absorbs the separate gh pr view / gh pr diff calls.  Collection of related repos is
  parallelized so the primary diff and secondary checks run concurrently.

Local mode (--local): diff working-tree changes across all repos in the workspace.
  No GitHub API calls are made. Runs `git diff HEAD` per repo (falls back to
  `git diff --cached` for repos with no commits yet). Excludes .idea and server/configs.
"""

import argparse
import json
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
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
    if not is_git_repo(path):
        return None, "", None

    remote_url, ok = run("git", "-C", str(path), "remote", "get-url", "origin")
    if not ok:
        return None, "", None

    owner_repo = parse_owner_repo(remote_url)

    if skip_repo and owner_repo == skip_repo:
        # This repo's diff is already provided externally (gh pr diff or --pr-url);
        # acknowledge it in the summary but don't re-diff it.
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


def workspace_paths(repo_root: Path) -> list[Path]:
    return [
        repo_root,
        repo_root / "server" / "testAutomation",
        *subdirs(repo_root / "server" / "modules"),
        *subdirs(repo_root / "clientAPIs"),
    ]


def is_git_repo(path: Path) -> bool:
    _, ok = run("git", "-C", str(path), "rev-parse", "--git-dir")
    return ok


def run_local_mode(repo_root: Path) -> None:
    for path in workspace_paths(repo_root):
        if not path.exists():
            continue

        if not is_git_repo(path):
            continue

        diff, ok = run(
            "git", "-C", str(path), "diff", "HEAD", "--",
            ".", ":(exclude).idea", ":(exclude)server/configs",
        )
        if not ok:
            diff, ok = run(
                "git", "-C", str(path), "diff", "--cached", "--",
                ".", ":(exclude).idea", ":(exclude)server/configs",
            )
            if not ok:
                continue

        if not diff:
            continue

        remote_url, ok = run("git", "-C", str(path), "remote", "get-url", "origin")
        label = parse_owner_repo(remote_url) if ok and remote_url else path.name

        print(f"=== {label}  (local changes) ===")
        print(diff)
        print()


def fetch_pr_metadata(pr_url: str) -> dict:
    """Fetch all needed PR fields in one gh pr view call."""
    stdout, ok = run(
        "gh", "pr", "view", pr_url,
        "--json", "headRefName,headRepository,headRepositoryOwner,title,body,changedFiles,baseRefName",
    )
    if not ok or not stdout:
        print(f"Error: could not fetch PR metadata for {pr_url}", file=sys.stderr)
        sys.exit(1)
    try:
        return json.loads(stdout)
    except json.JSONDecodeError as e:
        print(f"Error: could not parse PR metadata: {e}", file=sys.stderr)
        sys.exit(1)


def fetch_pr_diff(pr_url: str) -> str:
    """Fetch the unified diff for a PR URL."""
    result = subprocess.run(
        ["gh", "pr", "diff", pr_url],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout


def _print_diff_block(
    owner_repo: str, branch: str, pr_info: PRInfo, diff: str
) -> None:
    print(f"=== {owner_repo}  branch: {branch}  base: {pr_info.base_branch} ===")
    if pr_info.title:
        print(f"PR: {pr_info.title}")
    if pr_info.body and pr_info.body.strip():
        print()
        print(pr_info.body.strip())
    print()
    if diff:
        print(diff, end="")
    print()


def run_branch_mode(
    branch: str,
    skip_repo: str,
    repo_root: Path,
    pr_url: str = "",
    primary_owner_repo: str = "",
    primary_pr_info: PRInfo | None = None,
    primary_changed_files: int | None = None,
) -> None:
    """
    Collect diffs for BRANCH across all repos in the workspace.

    When pr_url is set (--pr-url mode), the primary repo's diff is fetched
    internally and printed first; skip_repo should equal primary_owner_repo.
    All network-bound collection tasks run in parallel via ThreadPoolExecutor.
    """
    paths = [p for p in workspace_paths(repo_root) if p.exists()]

    with ThreadPoolExecutor(max_workers=10) as executor:
        # Kick off independent network tasks immediately so they overlap with
        # each other and with the local-repo git checks.
        topic_future = executor.submit(get_topic_repos)
        pr_diff_future = executor.submit(fetch_pr_diff, pr_url) if pr_url else None

        local_futures = [
            (p, executor.submit(collect_repo, p, branch, skip_repo))
            for p in paths
        ]

        # Drain local futures first (preserves workspace ordering) so we can
        # build the `seen` set before submitting remote futures.
        seen: set[str] = set()
        local_results: list[tuple[str, str, PRInfo | None]] = []
        for _, f in local_futures:
            owner_repo, status, pr_info = f.result()
            if owner_repo:
                seen.add(owner_repo)
                local_results.append((owner_repo, status, pr_info))

        # Remote checks — only repos not already covered by a local checkout.
        topic_repos = topic_future.result()
        remote_futures = [
            (r, executor.submit(collect_remote_repo, r, branch, skip_repo))
            for r in topic_repos
            if r not in seen
        ]

        remote_results: list[tuple[str, str, PRInfo | None]] = []
        for owner_repo, f in remote_futures:
            owner_repo_out, status, pr_info = f.result()
            if owner_repo_out:
                remote_results.append((owner_repo_out, status, pr_info))

        primary_diff = pr_diff_future.result() if pr_diff_future else ""

    # Build ordered summary and diff-target lists.
    summary: list[tuple[str, str]] = []
    diff_targets: list[tuple[str, PRInfo]] = []

    if primary_owner_repo and primary_pr_info is not None:
        count = str(primary_changed_files) if primary_changed_files is not None else "?"
        primary_status = f"{count} files changed  (base: {primary_pr_info.base_branch})"
        if primary_pr_info.title:
            primary_status += f"  —  {primary_pr_info.title}"
        summary.append((primary_owner_repo, primary_status))

    for owner_repo, status, pr_info in local_results:
        if owner_repo == primary_owner_repo:
            continue  # Already represented above
        summary.append((owner_repo, status))
        if pr_info is not None:
            diff_targets.append((owner_repo, pr_info))

    for owner_repo, status, pr_info in remote_results:
        summary.append((owner_repo, status))
        if pr_info is not None:
            diff_targets.append((owner_repo, pr_info))

    # Print summary table.
    print(f"=== Repos examined for branch: {branch} ===")
    for repo, status in summary:
        print(f"  {repo:<45} {status}")
    print()

    # Primary repo diff comes first when in --pr-url mode.
    if primary_owner_repo and primary_pr_info is not None:
        _print_diff_block(primary_owner_repo, branch, primary_pr_info, primary_diff)

    # Secondary repo diffs.
    for owner_repo, pr_info in diff_targets:
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
        _print_diff_block(owner_repo, branch, pr_info, result.stdout)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect diffs for a branch across the LabKey multi-repo workspace."
    )
    parser.add_argument("branch", nargs="?", help="Feature branch name (omit with --local or --pr-url)")
    parser.add_argument(
        "--local", action="store_true",
        help="Diff working-tree changes across all repos (no GitHub API calls)",
    )
    parser.add_argument(
        "--pr-url", metavar="URL",
        help="GitHub PR URL — fetch metadata and diff internally (replaces separate gh pr view/diff calls)",
    )
    parser.add_argument(
        "--skip", metavar="OWNER/REPO", default="",
        help="Omit one repo already covered by a PR diff",
    )
    args = parser.parse_args()

    if sum([bool(args.local), bool(args.pr_url), bool(args.branch)]) != 1:
        parser.error("Provide exactly one of: branch, --local, or --pr-url")

    repo_root_str, ok = run("git", "rev-parse", "--show-toplevel")
    if not ok:
        print("Error: not inside a git repository", file=sys.stderr)
        sys.exit(1)
    repo_root = Path(repo_root_str)

    if args.local:
        run_local_mode(repo_root)
        return

    if args.pr_url:
        pr_data = fetch_pr_metadata(args.pr_url)
        branch = pr_data["headRefName"]
        primary_owner_repo = (
            f"{pr_data['headRepositoryOwner']['login']}/{pr_data['headRepository']['name']}"
        )
        primary_pr_info = PRInfo(
            base_branch=pr_data["baseRefName"],
            title=pr_data.get("title", ""),
            body=pr_data.get("body", ""),
        )
        run_branch_mode(
            branch=branch,
            skip_repo=primary_owner_repo,
            repo_root=repo_root,
            pr_url=args.pr_url,
            primary_owner_repo=primary_owner_repo,
            primary_pr_info=primary_pr_info,
            primary_changed_files=pr_data.get("changedFiles"),
        )
        return

    run_branch_mode(branch=args.branch, skip_repo=args.skip, repo_root=repo_root)


if __name__ == "__main__":
    main()