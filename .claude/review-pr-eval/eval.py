#!/usr/bin/env python3
"""
Evaluate review-pr prompt variants against a training set of PRs with known critical bugs.

Usage:
  python eval.py                              # evaluate ../commands/review-pr.md
  python eval.py prompts/variant1.md          # evaluate a specific variant
  python eval.py --compare current variant1   # compare two variants side by side

Requires:
  claude CLI (Claude Code) authenticated
  gh CLI authenticated
"""

import json
import subprocess
import sys
import threading
from contextlib import contextmanager
from pathlib import Path
from datetime import datetime

SCRIPT_DIR = Path(__file__).parent
TRAINING_SET_FILE = SCRIPT_DIR / "training_set.json"
PROMPTS_DIR = SCRIPT_DIR / "prompts"
RESULTS_DIR = SCRIPT_DIR.parent.parent / "build" / "review-pr-output"
REPOS_DIR = SCRIPT_DIR.parent.parent / "build" / "pr-eval-repos"
LIVE_PROMPT = SCRIPT_DIR.parent / "commands" / "review-pr.md"

JUDGE_MODEL = "claude-haiku-4-5"


JUDGE_PROMPT = """You are evaluating whether a code review successfully identified a known critical issue.

Known critical issue to find:
{expected_issue}

Code review output to evaluate:
{review_output}

Did the code review identify this issue or a substantially equivalent problem?

Respond with exactly one of these verdicts, followed by a colon and brief explanation:
CAUGHT: <explanation> — the review clearly identified this issue or its root cause
PARTIAL: <explanation> — the review hinted at related concerns but didn't pinpoint the specific issue
MISSED: <explanation> — the review did not identify this issue"""


def pr_label(url: str) -> str:
    """Return 'owner/repo#number' from a full GitHub PR URL."""
    parts = url.rstrip("/").split("/")
    # https://github.com/owner/repo/pull/number
    return f"{parts[-4]}/{parts[-3]}#{parts[-1]}"


def get_pr_data(url: str) -> tuple[dict, str]:
    view_json = subprocess.check_output(
        ["gh", "pr", "view", url, "--json", "title,body,author,number,url,mergeCommit"],
        text=True,
    )
    pr_view = json.loads(view_json)
    pr_diff = subprocess.check_output(
        ["gh", "pr", "diff", url],
        text=True,
    )
    return pr_view, pr_diff



@contextmanager
def get_merge_commit(pr_view: dict, url: str):
    """Context manager that checks out the PR's merge commit in <repo-root>/build/pr-eval-repos/<org>/<repo>."""
    parts = url.rstrip("/").split("/")
    org, repo_name = parts[-4], parts[-3]
    repo_path = REPOS_DIR / org / repo_name
    merge_commit = (pr_view.get("mergeCommit") or {}).get("oid")

    if not merge_commit:
        print(f"  (PR has no merge commit, skipping checkout)")
        yield None
        return

    if not repo_path.exists():
        REPOS_DIR.mkdir(parents=True, exist_ok=True)
        print(f"  cloning {org}/{repo_name}... ", end="", flush=True)
        subprocess.check_call(
            ["gh", "repo", "clone", f"{org}/{repo_name}", str(repo_path), "--", "--filter=blob:none"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        print("done")

    # Ensure commit is present (may need a fetch if repo is stale)
    commit_known = subprocess.call(
        ["git", "-C", str(repo_path), "cat-file", "-e", merge_commit],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ) == 0
    if not commit_known:
        print(f"  fetching {org}/{repo_name}... ", end="", flush=True)
        subprocess.check_call(
            ["git", "-C", str(repo_path), "fetch", "--filter=blob:none", "origin"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        print("done")

    print(f"  checking out {merge_commit[:12]}... ", end="", flush=True)
    subprocess.check_call(
        ["git", "-C", str(repo_path), "checkout", "--detach", merge_commit],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    print("done")
    try:
        yield str(repo_path)
    finally:
        pass  # Leave detached HEAD; next run will checkout the right commit


def run_claude(prompt: str, extra_args: list[str] = None, stream: bool = False, cwd: str = None, skip_permissions: bool = False) -> str:
    cmd = ["claude", "-p"]
    if skip_permissions:
        cmd.append("--dangerously-skip-permissions")
    if extra_args:
        cmd.extend(extra_args)

    if stream:
        process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd=cwd,
        )
        process.stdin.write(prompt)
        process.stdin.close()
        stderr_lines = []
        stderr_thread = threading.Thread(target=lambda: stderr_lines.extend(process.stderr), daemon=True)
        stderr_thread.start()
        lines = []
        for line in process.stdout:
            print(line, end="", flush=True)
            lines.append(line)
        stderr_thread.join()
        try:
            process.wait(timeout=1200)
        except subprocess.TimeoutExpired:
            process.kill()
            raise RuntimeError("claude -p timed out after 20 minutes")
        if process.returncode != 0:
            raise RuntimeError("".join(stderr_lines).strip() or f"claude -p exited with code {process.returncode}")
        return "".join(lines).strip()
    else:
        result = subprocess.run(
            cmd,
            input=prompt,
            capture_output=True,
            text=True,
            timeout=1200,
            cwd=cwd,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or f"claude -p exited with code {result.returncode}")
        return result.stdout.strip()


def run_review(prompt_template: str, pr_view: dict, pr_diff: str, cwd: str = None) -> str:
    pr_summary = (
        f"PR #{pr_view['number']}: {pr_view['title']}\n"
        f"URL: {pr_view.get('url', '')}\n"
        f"Author: {pr_view['author']['login']}\n\n"
        f"Description:\n{pr_view.get('body', '(no description)')}"
    )
    # Inject the PR data in place of the gh CLI calls the prompt would normally make
    full_prompt = f"""{prompt_template}

---
Note: The PR data has already been fetched. Use the following instead of running gh commands:

## PR Details
{pr_summary}

## Diff
{pr_diff}"""

    print()
    return run_claude(full_prompt, stream=True, cwd=cwd, skip_permissions=True)


def judge_review(review_output: str, expected_issue: str) -> tuple[str, str]:
    prompt = JUDGE_PROMPT.format(
        expected_issue=expected_issue,
        review_output=review_output,
    )
    text = run_claude(prompt, extra_args=["--model", JUDGE_MODEL])
    verdict = text.split(":")[0].strip().upper()
    if verdict not in ("CAUGHT", "PARTIAL", "MISSED"):
        verdict = "UNKNOWN"
    return verdict, text


def evaluate_prompt(prompt_file: Path, training_set: list) -> dict:
    prompt_template = prompt_file.read_text()
    results = []

    for entry in training_set:
        url = entry["url"]
        short = pr_label(url)
        print(f"  [{short}] fetching... ", end="", flush=True)

        try:
            pr_view, pr_diff = get_pr_data(url)
            print(f"{pr_view['title']}")
            print(f"  diff: {len(pr_diff):,} chars")
            with get_merge_commit(pr_view, url) as cwd:
                review = run_review(prompt_template, pr_view, pr_diff, cwd=cwd)
            print(f"\n--- judging ---")

            findings = []
            for issue in entry["expected_issues"]:
                print(f"  {issue[:80]}... ", end="", flush=True)
                verdict, judge_explanation = judge_review(review, issue)
                print(verdict)
                findings.append({
                    "expected_issue": issue,
                    "verdict": verdict,
                    "judge_explanation": judge_explanation,
                })

            results.append({
                "url": url,
                "title": pr_view["title"],
                "findings": findings,
                "review": review,
            })
        except Exception as e:
            print(f"ERROR: {e}")
            results.append({
                "url": url,
                "findings": [{"expected_issue": issue, "verdict": "ERROR", "error": str(e)}
                             for issue in entry["expected_issues"]],
            })

    all_findings = [f for r in results for f in r["findings"]]
    total = len(all_findings)
    caught = sum(1 for f in all_findings if f["verdict"] == "CAUGHT")
    partial = sum(1 for f in all_findings if f["verdict"] == "PARTIAL")
    missed = sum(1 for f in all_findings if f["verdict"] == "MISSED")

    return {
        "prompt_file": str(prompt_file),
        "timestamp": datetime.now().isoformat(),
        "score": {
            "caught": caught,
            "partial": partial,
            "missed": missed,
            "total": total,
            # Partial counts as 0.5 — it found something but wasn't precise
            "catch_rate": round((caught + 0.5 * partial) / total, 2) if total > 0 else 0.0,
        },
        "results": results,
    }


def print_summary(evaluation: dict):
    s = evaluation["score"]
    name = Path(evaluation["prompt_file"]).stem
    print(f"\n{name}: {s['caught']} caught, {s['partial']} partial, {s['missed']} missed / {s['total']} total  (catch rate: {s['catch_rate']:.0%})")
    for r in evaluation["results"]:
        short = pr_label(r["url"])
        title = r.get("title", "")
        for f in r["findings"]:
            if f["verdict"] in ("MISSED", "PARTIAL", "ERROR"):
                print(f"  [{f['verdict']}] {short} {title[:30]}: {f.get('judge_explanation', f.get('error', ''))}")


def main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    if not TRAINING_SET_FILE.exists():
        print(f"Error: training set not found at {TRAINING_SET_FILE}")
        sys.exit(1)

    training_set = json.loads(TRAINING_SET_FILE.read_text())
    args = sys.argv[1:]

    print(f"Warning: this evaluation runs Claude on {len(training_set)} PRs and will take 10+ minutes.")

    if "--compare" in args:
        idx = args.index("--compare")
        names = args[idx + 1:]
        if not names:
            print("Usage: eval.py --compare <name1> <name2> ...")
            sys.exit(1)

        prompt_files = {name: LIVE_PROMPT if name == "current" else PROMPTS_DIR / f"{name}.md" for name in names}
        for name, prompt_file in prompt_files.items():
            if not prompt_file.exists():
                print(f"Error: prompt file not found at {prompt_file}")
                sys.exit(1)

        all_results = []
        for name, prompt_file in prompt_files.items():
            print(f"\nEvaluating {name}...")
            result = evaluate_prompt(prompt_file, training_set)
            all_results.append(result)
            out_file = RESULTS_DIR / f"{name}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
            out_file.write_text(json.dumps(result, indent=2))
            print_summary(result)

        print("\n--- Comparison ---")
        for r in all_results:
            s = r["score"]
            prompt_name = Path(r["prompt_file"]).stem
            print(f"  {prompt_name:25s}  {s['catch_rate']:.0%}  ({s['caught']}C {s['partial']}P {s['missed']}M)")

    else:
        prompt_file = Path(args[0]) if args else LIVE_PROMPT
        if not prompt_file.exists():
            print(f"Error: prompt file not found at {prompt_file}")
            sys.exit(1)
        print(f"Evaluating {prompt_file.name} against {len(training_set)} PRs...")
        result = evaluate_prompt(prompt_file, training_set)
        print_summary(result)
        out_file = RESULTS_DIR / f"{prompt_file.stem}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        out_file.write_text(json.dumps(result, indent=2))
        print(f"\nResults saved to {out_file}")


if __name__ == "__main__":
    main()
