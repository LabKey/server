---
name: wcag-compliance
description: "Review frontend code for WCAG 2.2 Level AA accessibility compliance. Checks semantic HTML, ARIA usage, keyboard navigation, color contrast, focus management, and more. Supports --full flag for complete file/directory review; defaults to staged changes when a path is provided."
---

# WCAG 2.2 Accessibility Compliance Review

## Intent
Use this skill whenever the user asks to check accessibility or WCAG compliance of frontend code (`.tsx`, `.ts`, `.js`, `.jsx`, `.scss`, `.css`, `.jsp`, `.jspf` files). Support three review modes:

1. **Pending-change review** -- bare invocation with no arguments; inspects staged/working-tree
   files slated for commit across all repos.
2. **Path review (default)** -- a file or directory path is provided (no flag); reviews only the
   git staged/working-tree changes for that path.
3. **Full review** -- a file or directory path is provided with `--full`; reviews the entire file
   contents (or all matching files in a directory) regardless of git status.

Apply only the WCAG 2.2 Level AA checklist below. Focus on issues that can be detected through static code review.

## Checklist
See [wcag-22-checklist.md](wcag-22-checklist.md) for the full set of criteria organized by principle.

Each rule has an **Urgency** level:
- **urgent** -- Violations that block users from accessing content or functionality.
- **suggestion** -- Improvements that enhance the experience but don't fully block access.

## Review Process

**Argument parsing:**

Parse the invocation arguments to extract:
- An optional flag: `--full`
- An optional path: a file path or directory path

**File discovery:**

- **No path, no flag (bare invocation):** Pending-change review. Discover nested repos by
  running `find server/modules -maxdepth 2 -name ".git" -type d` from the workspace root,
  then for each discovered repo (and the top-level root) run `git diff --cached --name-only`
  and `git diff --name-only`. Aggregate all results, filtering to applicable extensions
  (`.tsx`, `.ts`, `.js`, `.jsx`, `.scss`, `.css`, `.jsp`, `.jspf`).

- **Path provided (no `--full` flag -- default):** Determine the git root via
  `git -C <path> rev-parse --show-toplevel`.
  - *File path:* Run `git diff --cached -- <file>` and `git diff -- <file>`. If there are
    no changes, report "No staged or working-tree changes found for `<file>`." and stop.
    If there are changes, proceed to review.
  - *Directory path:* Run `git diff --cached --name-only -- <dir>` and
    `git diff --name-only -- <dir>`. Filter to applicable extensions. If no changed files
    are found, report "No staged or working-tree changes found under `<dir>`." and stop.

- **Path + `--full`:** Review the entire contents regardless of git status.
  - *File path:* Use the file directly -- no git discovery needed.
  - *Directory path:* Find all files matching applicable extensions under the directory
    (using glob/find). Review every matching file.

- **`--full` with no path:** Ask the user to provide a file or directory path.

**Review scope when reviewing staged changes (default mode):**

When reviewing staged changes, read the full file for context but **focus the review on changed
lines and their immediate surroundings**. Use the diff output to identify which sections
changed, then apply the checklist rules primarily to those sections. Still flag issues in
unchanged code only if they directly interact with or are affected by the changes.

**Multi-file grouping:** When reviewing multiple files, group all findings together by urgency section (urgent issues first, then suggestions), not per-file. Include the file path in each finding's `FilePath` field.

1. Open the relevant file(s). Gather all lines.
2. For each applicable checklist rule, evaluate the code against the rule text.
3. For each confirmed violation, capture evidence (exact snippet and/or file/line), record the WCAG criterion, and note confidence.
4. Compose the review per the template below.

## Required output
When invoked, the response must exactly follow one of the two templates:

### Template A (any findings)
```
# Accessibility review (WCAG 2.2 AA)
Found <N> urgent issues that need to be fixed:

## 1 <brief description>
FilePath: <path> line <line>
WCAG Criterion: <number and name, e.g. "1.1.1 Non-text Content">
Evidence: <relevant code snippet or pointer>
Confidence: <high | medium | low> - <brief justification>


### Suggested fix
<brief description of suggested fix, with code example if helpful>

---
... (repeat for each urgent issue) ...

Found <M> suggestions for improvement:

## 1 <brief description>
FilePath: <path> line <line>
WCAG Criterion: <number and name>
Evidence: <relevant code snippet or pointer>
Confidence: <high | medium | low> - <brief justification>


### Suggested fix
<brief description of suggested fix>

---

... (repeat for each suggestion) ...
```

If there are no urgent issues, omit that section. If there are no suggestions, omit that section.

Always list every urgent issue -- never cap or truncate them. If there are more than 10 suggestions, summarize as "10+ suggestions" and output the first 10.

Don't compress the blank lines between sections; keep them as-is for readability.

If you use Template A and at least one issue requires code changes, append a brief follow-up question after the structured output asking whether the user wants you to apply the suggested fix(es).

### Template B (no issues)
```
# Accessibility review (WCAG 2.2 AA)
No issues found.
```
