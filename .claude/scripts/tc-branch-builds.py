#!/usr/bin/env python3
"""List queued and running TeamCity builds for a given branch."""

import argparse
import json
import subprocess
import sys


def tc_api_all(endpoint: str) -> list[dict]:
    """Fetch all pages from a REST endpoint using --paginate --slurp."""
    result = subprocess.run(
        ["teamcity", "api", endpoint, "--paginate", "--slurp"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"Error: {result.stderr.strip()}", file=sys.stderr)
        sys.exit(1)
    data = json.loads(result.stdout)
    # --slurp returns a flat list of objects
    return data if isinstance(data, list) else []


def list_queued(branch: str) -> list[dict]:
    builds = tc_api_all("/app/rest/buildQueue?fields=build(id,state,branchName,buildTypeId,webUrl,buildType(name,projectName))")
    return [b for b in builds if (b.get("branchName") or "") == branch]


def list_running(branch: str) -> list[dict]:
    builds = tc_api_all("/app/rest/builds?locator=running:true&fields=build(id,state,branchName,buildTypeId,webUrl,percentageComplete,buildType(name,projectName))")
    return [b for b in builds if (b.get("branchName") or "") == branch]


def print_builds(builds: list[dict], title: str) -> None:
    print(f"\n{'='*60}")
    print(f"  {title} ({len(builds)})")
    print(f"{'='*60}")
    if not builds:
        print("  (none)")
    else:
        for b in builds:
            bt = b.get("buildType", {})
            print(f"  ID      : {b['id']}")
            print(f"  Job     : {bt.get('name', b.get('buildTypeId', '?'))}")
            print(f"  Project : {bt.get('projectName', '?')}")
            print(f"  Branch  : {b.get('branchName', '?')}")
            if b.get("percentageComplete") is not None:
                print(f"  Progress: {b['percentageComplete']}%")
            print(f"  URL     : {b.get('webUrl', '?')}")
            print()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="List queued and running TeamCity builds for a branch."
    )
    parser.add_argument("branch", help="Exact branch name to filter by")
    state_group = parser.add_mutually_exclusive_group()
    state_group.add_argument("--queued-only", action="store_true", help="Only show queued builds")
    state_group.add_argument("--running-only", action="store_true", help="Only show running builds")
    fmt_group = parser.add_mutually_exclusive_group()
    fmt_group.add_argument("--json", action="store_true", dest="as_json", help="Output raw JSON")
    fmt_group.add_argument("--ids", action="store_true", help="Output build IDs only, one per line")
    args = parser.parse_args()

    show_queued = not args.running_only
    show_running = not args.queued_only

    queued = list_queued(args.branch) if show_queued else []
    running = list_running(args.branch) if show_running else []

    if args.ids:
        for b in queued + running:
            print(b["id"])
    elif args.as_json:
        print(json.dumps({"queued": queued, "running": running}, indent=2))
    else:
        print(f"\nBranch filter: {args.branch!r}")
        if show_queued:
            print_builds(queued, "Queued Builds")
        if show_running:
            print_builds(running, "Running Builds")
        print(f"\nTotal: {len(queued) + len(running)} build(s)")


if __name__ == "__main__":
    main()
