---
name: code-review-react
description: "Trigger when the user requests a review of frontend files (e.g., `.tsx`, `.ts`, `.js`). Excludes test files. Supports --full flag for complete file/directory review; defaults to staged changes when a path is provided."
---

# Frontend Code Review

## Intent
Use this skill whenever the user asks to review frontend code (especially `.tsx`, `.ts`, `.scss`, `.css` or `.js` files). **Exclude test files** with extensions `.test.tsx`, `.test.ts`, `.spec.tsx`, or `.spec.ts` — those should be reviewed using the `code-review-jest` skill instead. Support three review modes:

1. **Pending-change review** – bare invocation with no arguments; inspects staged/working-tree
   files slated for commit across all repos.
2. **Path review (default)** – a file or directory path is provided (no flag); reviews only the
   git staged/working-tree changes for that path.
3. **Full review** – a file or directory path is provided with `--full`; reviews the entire file
   contents (or all matching files in a directory) regardless of git status.

Stick to the checklist below for every applicable file and mode. Apply only the React/frontend checklist rules — do not apply Jest test rules to test code that may be co-located or visible in the same file.

## Checklist
See [review-priority-and-format.md](../review-priority-and-format.md) for reviewer priority and standard review format, and [code-quality.md](code-quality.md), [performance.md](performance.md), [business-logic.md](business-logic.md) for the living checklist split by category—treat it as the canonical set of rules to follow.

Additionally, check for WCAG 2.2 Level AA accessibility violations using [wcag-22-checklist.md](../wcag-compliance/wcag-22-checklist.md). Use category **Accessibility** for these findings. Prioritize urgent WCAG criteria (missing alt text, keyboard traps, no focus indicators, missing form labels, broken ARIA) alongside Correctness-level issues.

Use the rule's `Urgency` to place findings in the "urgent issues" vs "suggestions" sections.

## Review Process

**Argument parsing:**

Parse the invocation arguments to extract:
- An optional flag: `--full`
- An optional path: a file path or directory path

**File discovery:**

- **No path, no flag (bare invocation):** Pending-change review. Discover nested repos by
  running `find server/modules -maxdepth 2 -name ".git" -type d` from the workspace root,
  then for each discovered repo (and the top-level root) run `git diff --cached --name-only`
  and `git diff --name-only`. Aggregate all results, filtering to applicable frontend
  extensions (`.tsx`, `.ts`, `.js`, `.scss`, `.css`) and excluding test files.

- **Path provided (no `--full` flag — default):** Determine the git root via
  `git -C <path> rev-parse --show-toplevel`.
  - *File path:* Run `git diff --cached -- <file>` and `git diff -- <file>`. If there are
    no changes, report "No staged or working-tree changes found for `<file>`." and stop.
    If there are changes, proceed to review.
  - *Directory path:* Run `git diff --cached --name-only -- <dir>` and
    `git diff --name-only -- <dir>`. Filter to applicable extensions. If no changed files
    are found, report "No staged or working-tree changes found under `<dir>`." and stop.

- **Path + `--full`:** Review the entire contents regardless of git status.
  - *File path:* Use the file directly — no git discovery needed.
  - *Directory path:* Find all files matching applicable extensions under the directory
    (using glob/find). Review every matching file.

- **`--full` with no path:** Ask the user to provide a file or directory path.

**Review scope when reviewing staged changes (default mode):**

When reviewing staged changes, read the full file for context but **focus the review on changed
lines and their immediate surroundings**. Use the diff output to identify which sections
changed, then apply the checklist rules primarily to those sections. Still flag issues in
unchanged code only if they directly interact with or are affected by the changes.

**Multi-file grouping:** When reviewing multiple files, group all findings together by urgency section (urgent issues first, then suggestions), not per-file. Include the file path in each finding's `FilePath` field.

1. Open the relevant component/module. Gather all lines.
2. For each applicable checklist rule, evaluate the code against the rule text, confidence threshold, and exceptions/false positives before deciding to flag it.
3. For each confirmed violation, capture evidence (exact snippet and/or file/line), record the rule's primary category, and note confidence briefly.
4. Compose the review section per the template below. Group findings by **Urgency** section first (urgent issues, then suggestions). Within each section, order findings by the checklist primary category priority: **Correctness**, then **Accessibility**, then **Maintainability**, then **Style**.

## Required output
When invoked, the response must exactly follow one of the two templates:

### Template A (any findings)
```
# Code review
Found <N> urgent issues that need to be fixed:

## 1 <brief description of bug>
FilePath: <path> line <line>
Evidence: <relevant code snippet or pointer>
Category: <Correctness | Accessibility | Maintainability | Style>
Confidence: <high | medium | low> - <brief justification>
Exceptions checked: <none apply | brief exception note>


### Suggested fix
<brief description of suggested fix>

---
... (repeat for each urgent issue) ...

Found <M> suggestions for improvement:

## 1 <brief description of suggestion>
FilePath: <path> line <line>
Evidence: <relevant code snippet or pointer>
Category: <Correctness | Accessibility | Maintainability | Style>
Confidence: <high | medium | low> - <brief justification>
Exceptions checked: <none apply | brief exception note>


### Suggested fix
<brief description of suggested fix>

---

... (repeat for each suggestion) ...
```

If there are no urgent issues, omit that section. If there are no suggestions, omit that section.

Always list every urgent issue — never cap or truncate them. If there are more than 10 suggestions, summarize as "10+ suggestions" and output the first 10.

Don't compress the blank lines between sections; keep them as-is for readability.

If you use Template A (i.e., there are issues to fix) and at least one issue requires code changes, append a brief follow-up question after the structured output asking whether the user wants you to apply the suggested fix(es). For example: "Would you like me to apply the suggested fixes?"

### Template B (no issues)
```
# Code review
No issues found.
```
