# Business Logic

> **Prerequisite:** Review and apply the common guidelines in [`common.md`](../common.md) before using this checklist.

## Jest tests must assert on meaningful outcomes

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when the test would still pass after removing the feature logic, or when assertions only validate setup/static existence. If the test is intentionally a smoke/no-crash render test, require that intent to be explicit in the test name.

### Exceptions / False Positives

- Allow explicit smoke tests when the purpose is only to verify the component/function does not throw on render or initialization.
- Do not flag helper tests that intentionally validate test utilities/builders rather than product behavior, if the subject under test is the utility itself.
- A simple existence assertion can be acceptable when the behavior under review is conditional presence/absence itself (for example, permission-gated rendering).

### Detection heuristic

Look for tests where all assertions target mock setup data, static strings, or DOM existence rather than computed/rendered output.

## Rules

1. **Assert on component output, not existence.** After `render()`, assert on visible text, element states, or DOM changes that result from the props/state you set up — never just `expect(container).toBeDefined()` or `expect(document.querySelector('.x')).not.toBeNull()`.

2. **Assert on computed results, not test inputs.** If you pass `mockData` into a component, don't assert that `mockData` has the values you just wrote. Assert on what the component *did* with that data.

3. **Every test must fail if the feature is removed.** Apply this litmus test: if you deleted the implementation code for the feature under test, would the test still pass? If yes, the test is worthless — rewrite it.

4. **Interact before asserting (when applicable).** If testing behavior triggered by user action (click, submit, input), simulate that action with `fireEvent` or `userEvent`, then assert on the resulting DOM or state change.

## Examples

```js
// ❌ BAD — asserts on render existence
render(<MyForm valid={false} />);
expect(document.querySelector('.form-container')).not.toBeNull();

// ✅ GOOD — asserts on behavioral outcome of props
render(<MyForm valid={false} />);
expect(screen.getByRole('button', { name: /submit/i })).toBeDisabled();

// ❌ BAD — asserts on mock input
const data = [{ name: 'Alpha', value: 10 }];
render(<Table rows={data} />);
expect(data[0].name).toBe('Alpha'); // This tests your test, not your code

// ✅ GOOD — asserts on rendered output from that input
const data = [{ name: 'Alpha', value: 10 }];
render(<Table rows={data} />);
expect(screen.getByText('Alpha')).toBeInTheDocument();
expect(screen.getByText('10')).toBeInTheDocument();
```
