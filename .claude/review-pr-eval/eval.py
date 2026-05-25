#!/usr/bin/env python3
"""
Evaluate review-lk prompt variants against a training set of PRs with known critical bugs.

Usage:
  python eval.py                              # evaluate ../commands/review-lk.md
  python eval.py prompts/variant1.md          # evaluate a specific variant
  python eval.py --compare current variant1   # compare two variants side by side

Requires:
  claude CLI (Claude Code) authenticated
  gh CLI authenticated
"""

import hashlib
import json
import subprocess
import sys
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from datetime import datetime

SCRIPT_DIR = Path(__file__).parent
TRAINING_SET_FILE = SCRIPT_DIR / "training_set.json"
PROMPTS_DIR = SCRIPT_DIR / "prompts"
RESULTS_DIR = SCRIPT_DIR.parent.parent / "build" / "review-lk-output"
CACHE_DIR = RESULTS_DIR / "cache"
REPOS_DIR = SCRIPT_DIR.parent.parent / "build" / "pr-eval-repos"
LIVE_PROMPT = SCRIPT_DIR.parent / "commands" / "review-lk.md"
GATHER_SCRIPT = SCRIPT_DIR.parent / "scripts" / "gather-review-diff.py"

JUDGE_MODEL = "claude-haiku-4-5"


JUDGE_PROMPT = """You are evaluating whether a code review identified known critical issues.

Known critical issues to find:
{expected_issues}

Code review output to evaluate:
{review_output}

For each numbered issue, respond with exactly one line using one of these verdicts:
CAUGHT: <explanation> — the review clearly identified this issue or its root cause
PARTIAL: <explanation> — the review hinted at related concerns but didn't pinpoint the specific issue
MISSED: <explanation> — the review did not identify this issue

Respond with exactly {n} lines, one per issue, in order."""


def pr_label(url: str) -> str:
    """Return 'owner/repo#number' from a full GitHub PR URL."""
    parts = url.rstrip("/").split("/")
    # https://github.com/owner/repo/pull/number
    return f"{parts[-4]}/{parts[-3]}#{parts[-1]}"


def get_pr_metadata(url: str) -> dict:
    """Fetch the PR fields needed for display and merge-commit checkout."""
    view_json = subprocess.check_output(
        ["gh", "pr", "view", url, "--json", "title,number,url,mergeCommit"],
        text=True,
    )
    return json.loads(view_json)


def run_gather_script(url: str) -> str:
    """Run gather-review-diff.py for a PR URL and return its stdout."""
    return subprocess.check_output(
        ["python3", str(GATHER_SCRIPT), "--pr-url", url],
        text=True,
    )


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


def _format_usage(usage: dict) -> str:
    if not usage:
        return ""
    parts = []
    if "input_tokens" in usage:
        parts.append(f"in: {usage['input_tokens']:,}")
    if "cache_read_input_tokens" in usage and usage["cache_read_input_tokens"]:
        parts.append(f"cache_read: {usage['cache_read_input_tokens']:,}")
    if "output_tokens" in usage:
        parts.append(f"out: {usage['output_tokens']:,}")
    return f"  [{', '.join(parts)}]" if parts else ""


def run_claude(prompt: str, extra_args: list[str] = None, cwd: str = None, skip_permissions: bool = False) -> tuple[str, dict]:
    cmd = ["claude", "-p", "--output-format", "json"]
    if skip_permissions:
        cmd.append("--dangerously-skip-permissions")
    if extra_args:
        cmd.extend(extra_args)

    process = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        cwd=cwd,
    )
    timer = threading.Timer(1800, lambda: process.kill())
    try:
        timer.start()
        stdout, stderr = process.communicate(input=prompt)
    finally:
        timer.cancel()
    if process.returncode != 0:
        raise RuntimeError(stderr.strip() or f"claude -p exited with code {process.returncode}")
    try:
        data = json.loads(stdout)
        return data.get("result", "").strip(), data.get("usage", {})
    except (json.JSONDecodeError, KeyError):
        return stdout.strip(), {}


def run_review(prompt_template: str, script_output: str, cwd: str = None, model: str = None, label: str = "") -> str:
    # Inject the gather-review-diff.py output in place of the script call the prompt would normally make
    full_prompt = (
        f"{prompt_template}\n\n---\n"
        "Note: The gather-review-diff.py script has already been run for this PR. "
        "Use the following output instead of running the script:\n\n"
        f"{script_output}"
    )

    prefix = f"  {label}" if label else "  "
    model_label = f" [{model}]" if model else ""
    print(f"{prefix}[{datetime.now().strftime('%H:%M:%S')}] reviewing{model_label}...")
    t0 = time.time()
    extra_args = ["--model", model] if model else None
    result, usage = run_claude(full_prompt, extra_args=extra_args, cwd=cwd, skip_permissions=True)
    elapsed = int(time.time() - t0)
    print(f"{prefix}done in {elapsed}s ({len(result.splitlines())} lines){_format_usage(usage)}")
    return result


def judge_all_issues(review_output: str, issues: list[str], label: str = "") -> list[dict]:
    """Judge all expected issues for one review in a single batched Haiku call."""
    prefix = f"  {label}" if label else "  "
    numbered = "\n".join(f"{i + 1}. {issue}" for i, issue in enumerate(issues))
    prompt = JUDGE_PROMPT.format(expected_issues=numbered, review_output=review_output, n=len(issues))
    print(f"{prefix}[{datetime.now().strftime('%H:%M:%S')}] judging {len(issues)} issues...")
    t0 = time.time()
    text, usage = run_claude(prompt, extra_args=["--model", JUDGE_MODEL])
    elapsed = int(time.time() - t0)
    print(f"{prefix}done in {elapsed}s{_format_usage(usage)}")

    verdict_lines = [
        line.strip() for line in text.splitlines()
        if any(line.strip().upper().startswith(v) for v in ("CAUGHT", "PARTIAL", "MISSED"))
    ]
    findings = []
    for i, issue in enumerate(issues):
        if i < len(verdict_lines):
            line = verdict_lines[i]
            verdict = next((v for v in ("CAUGHT", "PARTIAL", "MISSED") if line.upper().startswith(v)), "UNKNOWN")
        else:
            verdict, line = "UNKNOWN", ""
        findings.append({"expected_issue": issue, "verdict": verdict, "judge_explanation": line})
    return findings


def _cache_key(prompt_template: str, url: str, model: str = "") -> str:
    return hashlib.sha256((prompt_template + "\n" + url + "\n" + model + "\n" + JUDGE_PROMPT + "\n" + JUDGE_MODEL).encode()).hexdigest()[:32]


def load_cached_pr_result(prompt_template: str, url: str, model: str = "") -> dict | None:
    cache_file = CACHE_DIR / f"{_cache_key(prompt_template, url, model)}.json"
    return json.loads(cache_file.read_text()) if cache_file.exists() else None


def save_cached_pr_result(prompt_template: str, url: str, result: dict, model: str = ""):
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file = CACHE_DIR / f"{_cache_key(prompt_template, url, model)}.json"
    cache_file.write_text(json.dumps(result, indent=2))


def _aggregate_findings(all_run_findings: list[list[dict]]) -> list[dict]:
    """Merge per-run findings into one finding per issue with verdict breakdown."""
    aggregated = []
    for i, base in enumerate(all_run_findings[0]):
        verdicts = [run[i]["verdict"] for run in all_run_findings]
        catch_rate = round(
            (verdicts.count("CAUGHT") + 0.5 * verdicts.count("PARTIAL")) / len(verdicts), 2
        )
        majority = Counter(verdicts).most_common(1)[0][0]
        aggregated.append({
            "expected_issue": base["expected_issue"],
            "verdict": majority,
            "verdicts": verdicts,
            "multi_run_catch_rate": catch_rate,
            "judge_explanation": all_run_findings[-1][i]["judge_explanation"],
        })
    return aggregated


def evaluate_prompt(prompt_file: Path, training_set: list, num_runs: int = 1, model: str = None, use_cache: bool = True) -> dict:
    prompt_template = prompt_file.read_text()
    results = []

    for entry in training_set:
        url = entry["url"]
        short = pr_label(url)

        if use_cache and num_runs == 1:
            cached = load_cached_pr_result(prompt_template, url, model or "")
            if cached:
                print(f"  [{short}] using cached result")
                results.append(cached)
                continue

        print(f"  [{short}] fetching... ", end="", flush=True)

        try:
            pr_view = get_pr_metadata(url)
            print(f"{pr_view['title']}")
            script_output = run_gather_script(url)
            print(f"  script output: {len(script_output):,} chars")

            run_label = (lambda i: f"run {i + 1}/{num_runs}: ") if num_runs > 1 else (lambda i: "")

            with get_merge_commit(pr_view, url) as cwd:
                # Run all reviews in parallel — same cwd (same commit, read-only), safe to share
                with ThreadPoolExecutor(max_workers=num_runs) as executor:
                    review_futures = [
                        executor.submit(run_review, prompt_template, script_output,
                                        cwd=cwd, model=model, label=run_label(i))
                        for i in range(num_runs)
                    ]
                    reviews = [f.result() for f in review_futures]

            # Judge all reviews in parallel (one batched call per run)
            with ThreadPoolExecutor(max_workers=num_runs) as executor:
                judge_futures = [
                    executor.submit(judge_all_issues, review, entry["expected_issues"], run_label(i))
                    for i, review in enumerate(reviews)
                ]
                all_run_findings = [f.result() for f in judge_futures]

            for run_findings, review in zip(all_run_findings, reviews):
                save_cached_pr_result(prompt_template, url, {
                    "url": url,
                    "title": pr_view["title"],
                    "findings": run_findings,
                    "review": review,
                }, model or "")

            findings = _aggregate_findings(all_run_findings) if num_runs > 1 else all_run_findings[0]
            results.append({
                "url": url,
                "title": pr_view["title"],
                "findings": findings,
                "review": reviews[-1],
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
    if num_runs > 1:
        # Average the per-issue catch rates across runs
        catch_rate = round(sum(f.get("multi_run_catch_rate", 0) for f in all_findings) / total, 2) if total > 0 else 0.0
        caught = round(sum(f.get("multi_run_catch_rate", 0) for f in all_findings))
        partial = 0
        missed = total - caught
    else:
        caught = sum(1 for f in all_findings if f["verdict"] == "CAUGHT")
        partial = sum(1 for f in all_findings if f["verdict"] == "PARTIAL")
        missed = sum(1 for f in all_findings if f["verdict"] == "MISSED")
        catch_rate = round((caught + 0.5 * partial) / total, 2) if total > 0 else 0.0

    stem = prompt_file.stem
    model_label = f"{stem}@{model}" if model else stem
    return {
        "prompt_file": str(prompt_file),
        "model": model,
        "model_label": model_label,
        "timestamp": datetime.now().isoformat(),
        "num_runs": num_runs,
        "score": {
            "caught": caught,
            "partial": partial,
            "missed": missed,
            "total": total,
            "catch_rate": catch_rate,
        },
        "results": results,
    }


def print_summary(evaluation: dict):
    s = evaluation["score"]
    name = evaluation.get("model_label") or Path(evaluation["prompt_file"]).stem
    num_runs = evaluation.get("num_runs", 1)
    run_label = f" over {num_runs} runs" if num_runs > 1 else ""
    print(f"\n{name}{run_label}: {s['caught']} caught, {s['partial']} partial, {s['missed']} missed / {s['total']} total  (catch rate: {s['catch_rate']:.0%})")
    for r in evaluation["results"]:
        short = pr_label(r["url"])
        title = r.get("title", "")
        for f in r["findings"]:
            if "verdicts" in f:
                breakdown = ", ".join(f"{v}×{f['verdicts'].count(v)}" for v in ("CAUGHT", "PARTIAL", "MISSED") if f["verdicts"].count(v))
                print(f"  [{f['verdict']}] {short} {title[:30]}: {breakdown} — {f.get('judge_explanation', '')}")
            elif f["verdict"] in ("MISSED", "PARTIAL", "ERROR"):
                print(f"  [{f['verdict']}] {short} {title[:30]}: {f.get('judge_explanation', f.get('error', ''))}")


def main():
    t_start = time.time()
    try:
        _main()
    finally:
        elapsed = int(time.time() - t_start)
        print(f"\nTotal runtime: {elapsed // 60}m {elapsed % 60}s")


def _main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    if not TRAINING_SET_FILE.exists():
        print(f"Error: training set not found at {TRAINING_SET_FILE}")
        sys.exit(1)

    training_set = json.loads(TRAINING_SET_FILE.read_text())
    args = sys.argv[1:]

    num_runs = 1
    runs_specified = "--runs" in args
    if runs_specified:
        idx = args.index("--runs")
        if idx + 1 >= len(args):
            print("Usage: eval.py --runs <N>")
            sys.exit(1)
        num_runs = int(args[idx + 1])
        args = args[:idx] + args[idx + 2:]

    run_label = f" ({num_runs} runs each)" if num_runs > 1 else ""
    print(f"Warning: this evaluation runs Claude on {len(training_set)} PRs{run_label} and typically takes ~5 minutes per PR.")

    single_model = None
    if "--model" in args:
        idx = args.index("--model")
        if idx + 1 >= len(args):
            print("Usage: eval.py --model <model>")
            sys.exit(1)
        single_model = args[idx + 1]
        args = args[:idx] + args[idx + 2:]

    if "--compare" in args:
        idx = args.index("--compare")
        names = args[idx + 1:]
        if not names:
            print("Usage: eval.py --compare <name1> <name2> ...")
            sys.exit(1)

        entries = []
        for name in names:
            prompt_name, has_at, inline_model = name.partition("@")
            if has_at and single_model:
                print(f"Warning: --model {single_model!r} and @model syntax both specified for {name!r}; @model takes precedence")
            effective_model = (inline_model if has_at else None) or single_model
            prompt_file = LIVE_PROMPT if prompt_name == "current" else PROMPTS_DIR / f"{prompt_name}.md"
            entries.append((name, prompt_file, effective_model))

        for name, prompt_file, _ in entries:
            if not prompt_file.exists():
                print(f"Error: prompt file not found at {prompt_file}")
                sys.exit(1)

        all_results = []
        for name, prompt_file, model in entries:
            print(f"\nEvaluating {name}...")
            result = evaluate_prompt(prompt_file, training_set, num_runs=num_runs, model=model, use_cache=not runs_specified)
            all_results.append(result)
            safe_name = name.replace("@", "_at_")
            out_file = RESULTS_DIR / f"{safe_name}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
            out_file.write_text(json.dumps(result, indent=2))
            print_summary(result)

        print("\n--- Comparison ---")
        for r in all_results:
            s = r["score"]
            label = r.get("model_label") or Path(r["prompt_file"]).stem
            print(f"  {label:35s}  {s['catch_rate']:.0%}  ({s['caught']}C {s['partial']}P {s['missed']}M)")

    else:
        prompt_file = Path(args[0]) if args else LIVE_PROMPT
        if not prompt_file.exists():
            print(f"Error: prompt file not found at {prompt_file}")
            sys.exit(1)
        print(f"Evaluating {prompt_file.name} against {len(training_set)} PRs...")
        result = evaluate_prompt(prompt_file, training_set, num_runs=num_runs, model=single_model, use_cache=not runs_specified)
        print_summary(result)
        out_file = RESULTS_DIR / f"{prompt_file.stem}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        out_file.write_text(json.dumps(result, indent=2))
        print(f"\nResults saved to {out_file}")


if __name__ == "__main__":
    main()
