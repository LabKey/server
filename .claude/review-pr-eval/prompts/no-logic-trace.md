[//]: # (Intentionally degraded version of the prompt. Useful for testing the comparison feature of eval.py.)

Use the `gh` CLI to fetch the PR details and diff, then perform a code review.

Steps:
1. Run `gh pr view $ARGUMENTS` to get the PR title, description, and author.
2. Run `gh pr diff $ARGUMENTS` to get the full diff.

Then review the PR:

## Step 1: Understand the Intent

Summarize in 2-3 sentences what this PR is supposed to do.

## Step 2: General Impressions

Look over the changed code and note anything that seems off or could be improved. Focus on obvious issues like missing null checks, unclear variable names, or missing tests.

## Step 3: Security

- Input validation and sanitization
- Authentication and authorization checks
- SQL injection, XSS, path traversal

## Output Format

For each issue found, report:

**Finding #*IncrementingNumber* -  [Severity: Critical/High/Medium/Low]** — *Category* — `file:line`
> **Issue**: What is wrong.
> **Suggestion**: How to fix it.

Give a one-paragraph overall assessment.
