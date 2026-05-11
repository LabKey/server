# Code Quality

> **Prerequisite:** Review and apply the shared guidelines in [`review-priority-and-format.md`](../review-priority-and-format.md) before using this checklist.

## Arrange / Act / Assert (AAA) structure

**Urgency:** suggestion

### Category

Style

### Confidence Threshold

Flag only when the test is long enough or complex enough that structure affects readability (for example, multi-step setup/interactions or >10 lines). For short, self-evident tests, prefer a suggestion or no comment.

### Exceptions / False Positives

- Do not flag short tests (roughly 3-5 lines) where Arrange/Act/Assert is obvious without comments.
- Do not require AAA comments in parameterized tests when added comments make the test harder to scan than the code itself.
- Prefer suggestions over high-severity findings unless the repository explicitly enforces AAA comment structure in tests.

### Rules

1. **Each section must be preceded by a comment** — `// Arrange`, `// Act`, and `// Assert`.
2. **The Assert comment must include a brief explanation** of how the described scenario is being verified. The explanation should summarize what the assertions prove about the behavior stated in the test name.
   - Good: `// Assert - textarea shows hash subjects, participantId query param is ignored`
   - Good: `// Assert - ID Search button is active`
   - Bad: `// Assert` (missing explanation)
   - Bad: `// Assert - check results` (too vague; does not connect to the scenario)
3. **Arrange may be omitted** when there is no setup beyond what `beforeEach` already provides, but Act and Assert are always required.
4. **Combined `// Act & Assert` is acceptable** only when the assertion must be wrapped inside a `waitFor` that is inseparable from the action (e.g., awaiting an async side-effect). In that case the comment must still include an explanation:
   - Good: `// Act & Assert - empty state message is shown and error was logged to console`
   - Bad: `// Act & Assert`
5. **Multiple Act/Assert cycles in one test are allowed** for stateful interaction flows (e.g., click then verify, click again then verify). Each cycle must carry its own `// Act` and `// Assert - ...` comments.

---

## No unused variables, imports, or mocks

**Urgency:** urgent

### Category

Maintainability

### Confidence Threshold

Flag when the symbol is clearly unused in the file or when a mock setup is not consumed by behavior under test. If a mock or import may be used implicitly (module mocking side effects, global setup conventions), verify before commenting.

### Exceptions / False Positives

- Do not flag side-effect imports (for example, polyfills or test environment setup) just because no identifier is referenced.
- Do not flag module-level `jest.mock(...)` declarations that intentionally replace imports used elsewhere in the file.
- If a placeholder parameter is required for function signature/position, allow underscore-prefixed names (for example, `_arg`).

### Rules

1. **Unused imports must be removed.** If a symbol is imported but never referenced in the file, delete the import. This includes named imports, default imports, and type-only imports.
2. **Unused variables and constants must be removed.** If a `const`, `let`, or destructured binding is declared but never read, delete it. This applies to top-level declarations, inside `describe`/`beforeEach`/`test` blocks, and helper functions.
3. **Unused mock declarations must be removed.** If `jest.fn()`, `jest.mock()`, `jest.spyOn()`, or a manual mock variable is set up but never referenced in an assertion or as a dependency, delete it. Mocks that are called implicitly (e.g., module-level `jest.mock('...')` that replaces an import used elsewhere) are considered used.
4. **Unused mock return values must be removed.** If a mock is configured with `.mockReturnValue()`, `.mockResolvedValue()`, or `.mockImplementation()` but the return value is never consumed or asserted on, simplify or remove the configuration.
5. **Unused helper functions and factory functions must be removed.** If a test utility, builder, or factory function defined in the file is never called, delete it.
6. **Unused parameters in callbacks must be prefixed with `_`.** If a callback parameter (e.g., in `.mockImplementation((unusedArg) => ...)`) is required for positional reasons but not used, prefix it with `_` to signal intent (e.g., `_unusedArg`).
7. **Unused `render` results must not be destructured.** If `render(<Component />)` is called and the return value is not used, do not destructure it. Write `render(<Component />);` instead of `const { container } = render(<Component />);` when `container` is never referenced.

### Examples

```tsx
// ---- Unused imports ----

// ❌ BAD — ApiResponse is imported but never used
import { render, screen } from '@testing-library/react';
import { ApiResponse, UserProfile } from '../models';

const mockProfile: UserProfile = { name: 'Test' };

// ✅ GOOD — only referenced imports remain
import { render, screen } from '@testing-library/react';
import { UserProfile } from '../models';

const mockProfile: UserProfile = { name: 'Test' };


// ---- Unused variables ----

// ❌ BAD — mockHandler is declared but never used
const mockHandler = jest.fn();
const mockCallback = jest.fn();

test('calls callback on click', () => {
    render(<Button onClick={mockCallback} />);
    fireEvent.click(screen.getByRole('button'));
    expect(mockCallback).toHaveBeenCalledTimes(1);
});

// ✅ GOOD — only mockCallback remains
const mockCallback = jest.fn();

test('calls callback on click', () => {
    render(<Button onClick={mockCallback} />);
    fireEvent.click(screen.getByRole('button'));
    expect(mockCallback).toHaveBeenCalledTimes(1);
});


// ---- Unused mock setup ----

// ❌ BAD — jest.spyOn for console.warn is set up but never asserted or needed
jest.spyOn(console, 'warn').mockImplementation(() => {});
jest.spyOn(console, 'error').mockImplementation(() => {});

test('renders error state', () => {
    render(<ErrorBanner message="fail" />);
    expect(screen.getByText('fail')).toBeInTheDocument();
    expect(console.error).toHaveBeenCalled();
    // console.warn is never checked
});

// ✅ GOOD — only the console.error spy remains
jest.spyOn(console, 'error').mockImplementation(() => {});

test('renders error state', () => {
    render(<ErrorBanner message="fail" />);
    expect(screen.getByText('fail')).toBeInTheDocument();
    expect(console.error).toHaveBeenCalled();
});


// ---- Unused render destructuring ----

// ❌ BAD — container is destructured but never referenced
const { container } = render(<Header title="Hello" />);
expect(screen.getByText('Hello')).toBeInTheDocument();

// ✅ GOOD — render called without unused destructuring
render(<Header title="Hello" />);
expect(screen.getByText('Hello')).toBeInTheDocument();


// ---- Unused callback parameter ----

// ❌ BAD — `req` is not used but has no underscore prefix
mockFetch.mockImplementation((req, options) => {
    return Promise.resolve({ ok: true });
});

// ✅ GOOD — unused positional parameter prefixed with _
mockFetch.mockImplementation((_req, options) => {
    return Promise.resolve({ ok: true });
});
```

---

## Async React updates must be awaited (`act` warning prevention)

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when the changed test triggers async React updates and asserts before the UI settles, or when `act(...)` warnings are present in test output. If the interaction and state updates are fully synchronous, do not force async patterns.

### Exceptions / False Positives

- Do not require `await` for purely synchronous interactions that do not trigger async state updates.
- If the test uses a project helper that already waits for async UI stabilization, avoid duplicating `waitFor`/`findBy` calls.
- When fake timers are used, accept explicit `act(...)` + timer advancement patterns that correctly flush updates.

### Rules

1. **If a component triggers async state updates after render, the test must await a UI-stable condition before assertions.** Look for `useEffect` with async work, fetch-on-mount patterns, or promise-based state transitions.
2. **User interactions that trigger async updates must be awaited.** `userEvent.click`, `userEvent.type`, and similar calls should be `await`ed when the resulting handler performs async work.
3. **Tests with async UI behavior must be marked `async`.** A test function that uses `await` must be declared `async`; a test whose component performs async work almost certainly needs both.
4. **Presence of `act(...)` warnings in test output is a defect, even when tests pass.** Treat these warnings as test failures during review.

### Heuristics for detection

- `render(...)` followed by immediate assertions while the component has `useEffect(() => { fetch(...).then(...) }, [])` or similar async-on-mount logic.
- `userEvent.click/type/...` followed by immediate assertions that depend on async updates.
- Presence of `console.error` `act(...)` warnings in test output, even when tests pass.
- Test functions not marked `async` despite the component performing async work.

### Preferred fixes (in order of preference)

1. **`await screen.findBy...(...)` — preferred.** Use for elements that appear after async work. Simpler and more idiomatic than `waitFor`.
2. **`await waitFor(() => expect(...))`** — use when asserting on state-dependent conditions that `findBy` can't express (e.g., element attribute changes, disappearance).
3. **Shared helpers** like `waitForComponentToLoad()` — call after render when available.
4. **`await userEvent.click(...)`** — always await interactions that trigger async handlers.

### Examples

```tsx
// ❌ BAD — immediate assertion after render; component fetches data on mount
render(<ParticipantReports />);
expect(screen.getByLabelText(/enter animal ids/i)).toBeInTheDocument();

// ✅ GOOD — waits for async mount to settle before asserting
render(<ParticipantReports />);
await waitFor(() => {
    expect(screen.getByText('General')).toBeVisible();
});
expect(screen.getByLabelText(/enter animal ids/i)).toBeInTheDocument();
```
