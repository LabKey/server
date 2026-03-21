# review-pr eval

Evaluates variants of the `review-pr` prompt against a training set of GitHub PRs that contain known bugs, measuring how often the prompt catches them.

## Prerequisites

- Python 3.10+
- `claude` CLI authenticated (`claude --version` should work)
- `gh` CLI authenticated (`gh auth status` should confirm)

## Running

```bash
# Evaluate the live prompt (../commands/review-pr.md)
python eval.py

# Evaluate a specific variant
python eval.py prompts/my-variant.md

# Compare the live prompt against a variant side by side
python eval.py --compare current my-variant
```

**Warning:** Each run invokes Claude on every PR in the training set. With the current 3-PR training set, expect **10+ minutes** per evaluation. A `--compare` with two names runs both sequentially, so plan for 20+ minutes.

## Training set

`training_set.json` lists GitHub PR URLs and the specific bugs that are expected to be caught. The judge (Claude Haiku) scores each review as `CAUGHT`, `PARTIAL`, or `MISSED` for each expected issue.

To add a PR to the training set, append an entry:

```json
{
  "url": "https://github.com/org/repo/pull/123",
  "expected_issues": [
    "Description of the specific bug that should be caught"
  ]
}
```

## Prompt variants

The live prompt is always `../commands/review-pr.md`. Named variants live in `prompts/`. To create a variant:

```bash
cp ../commands/review-pr.md prompts/my-variant.md
# edit prompts/my-variant.md
python eval.py --compare current my-variant
```

## Repo cache

When evaluating, the script checks out each PR's merge commit so Claude has access to the full repository context. Clones are stored at `~/pr-eval-repos/<repo-name>` and reused across runs. Fetches are only performed if the required commit is not already present locally. These clones use `--filter=blob:none` (blobless) so they are relatively lightweight.

## Results

Results are saved as JSON files in the repo root `build/` directory, named `<prompt-stem>_<timestamp>.json`. Each file contains the full review text, per-issue verdicts, and a summary score.

The catch rate counts `CAUGHT` as 1 and `PARTIAL` as 0.5.
