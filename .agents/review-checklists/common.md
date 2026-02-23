# Common Review Guidelines

## Reviewer Priority

Agents must prioritize findings in this order and report them in this order when multiple issues are present:

1. **Correctness** (behavioral bugs, warnings, broken contracts, stale state, invalid keys)
2. **Maintainability** (dead code, duplication, unnecessary complexity, high-review-cost risks)
3. **Style** (formatting/convention consistency that does not change behavior)

Do not prioritize Style-only findings ahead of unresolved Correctness or Maintainability findings.

## Standard Review Format

For every rule below, agents should provide:

1. **Evidence**: exact code snippet or file/line reference from the changed code
2. **Category**: the rule's listed primary category (Correctness, Maintainability, or Style)
3. **Confidence**: apply the rule's confidence threshold before escalating severity in review feedback
4. **Exceptions**: check the rule's false-positive section before flagging