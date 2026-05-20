# Rule Catalog — Business Logic

> **Prerequisite:** Review and apply the shared guidelines in [`review-priority-and-format.md`](../review-priority-and-format.md) before using this checklist.

## Avoid using array index as React key

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when the list can reorder, insert, delete, filter, or preserve item-local state. If the list is demonstrably static and append-only with no stateful children, leave at most a low-priority note.

### Exceptions / False Positives

- Allow index keys for truly static lists whose order and membership never change during the component lifecycle.
- Do not flag when the rendered items are simple, stateless presentation nodes and the list is effectively constant data.
- If a stable unique key is unavailable in the data model, note the risk and suggest data-model follow-up rather than inventing unstable keys in review.

### Description

Using array index as a `key` prop in React lists (e.g., `key={index}`, `key={`item-${index}`}`) can cause incorrect component behavior when items are reordered, inserted, or deleted. React uses keys to identify which items changed—index-based keys cause React to associate the wrong component instance with the wrong data, leading to:

- State attached to the wrong list item
- Input values appearing in the wrong row
- Stale data displayed after list mutations

### Suggested Fix

Use a stable, unique identifier from the data itself:

- **Before:** `items.map((item, index) => <Row key={index} ... />)`
- **After:** `items.map(item => <Row key={item.id} ... />)`

---

## Falsy value rendered by `&&` short-circuit

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag when the left side can be a number or string in the changed code path and React would render `0` or `""`. If the condition is already guaranteed boolean, do not flag.

### Exceptions / False Positives

- Do not flag when the left operand is explicitly boolean (for example, `isReady`, `Boolean(value)`, `!!value`).
- Do not flag when rendering `0` is intentional behavior and is part of the UI requirement.
- If TypeScript types or local guards prove the value cannot be a renderable falsy primitive, avoid speculative comments.

### Description

Using `&&` for conditional rendering with a numeric or string value can render `0` or `""` instead of nothing. JavaScript short-circuits to the left operand when it is falsy but not `null`/`undefined`/`false` — React renders `0` and `""` as text nodes.

### Anti-patterns to Flag

```tsx
// ❌ Renders "0" when count is 0
{count && <Badge count={count} />}

// ❌ Renders "" when name is empty string
{name && <Greeting name={name} />}
```

### Suggested Fix

Convert the condition to a boolean explicitly:

```tsx
// ✅ Renders nothing when count is 0
{count > 0 && <Badge count={count} />}

// ✅ Double negation for truthy check
{!!name && <Greeting name={name} />}
```

---

## Effect dependency array correctness

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when a missing/unstable dependency can be shown to cause stale closures, skipped updates, or excessive reruns in the changed code. If the effect is intentionally one-time or dependency omission is documented with a valid reason, verify context before commenting.

### Exceptions / False Positives

- Do not flag values read through stable refs (`ref.current`) when the effect intentionally avoids rerunning on ref changes.
- Allow justified `eslint-disable react-hooks/exhaustive-deps` comments when the code explains the invariant and the behavior is correct.
- Do not require memoization solely to satisfy the rule if it adds complexity and the effect cost is trivial; prefer a contextual suggestion.

### Description

`useEffect`, `useMemo`, and `useCallback` dependency arrays must include every reactive value referenced inside the callback. Missing dependencies cause stale closures; extra dependencies cause unnecessary re-runs.

### Anti-patterns to Flag

```tsx
// ❌ Missing dependency — handler captures stale `count`
useEffect(() => {
    document.addEventListener('click', () => console.log(count));
}, []);

// ❌ Object/array reference in deps without memoization — runs every render
useEffect(() => {
    fetchData(filters);
}, [filters]); // if `filters` is created inline each render
```

### Suggested Fix

```tsx
// ✅ Include all referenced reactive values
useEffect(() => {
    const handler = () => console.log(count);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
}, [count]);

// ✅ Derive a stable object from primitive values
const stableFilters = useMemo(
    () => ({ id: filters.id, status: filters.status }),
    [filters.id, filters.status]
);
useEffect(() => {
    fetchData(stableFilters);
}, [stableFilters]);
```

### How to Detect

1. For each `useEffect`/`useMemo`/`useCallback`, list variables from the enclosing scope referenced inside the callback.
2. Compare against the dependency array. Flag any variable that is referenced but missing from deps.
3. Flag objects or arrays in the deps array that are created inline each render (not memoized).

---

## Stale closure in async state updates

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when async callbacks use captured state to compute next state and can overwrite newer updates. If the callback intentionally uses a snapshot value for comparison/logging (not state derivation), do not flag.

### Exceptions / False Positives

- Do not flag async callbacks that only read captured state for analytics, logging, or conditional branching without writing derived state back.
- Do not flag when the update intentionally restores a specific captured value (snapshot semantics are the requirement).
- If the setter does not derive from prior state (for example, sets a constant), the functional updater is not always necessary.

### Description

Using the current state value (rather than the updater function) inside `setTimeout`, `setInterval`, promises, or event listeners captures a stale snapshot. The update will overwrite intermediate changes.

### Anti-patterns to Flag

```tsx
// ❌ Captures stale `count` — concurrent clicks lose updates
const handleClick = () => {
    setTimeout(() => {
        setCount(count + 1);
    }, 1000);
};
```

### Suggested Fix

Use the functional updater form:

```tsx
// ✅ Always reads the latest state
const handleClick = () => {
    setTimeout(() => {
        setCount(prev => prev + 1);
    }, 1000);
};
```

---

## Uncontrolled-to-controlled component switch

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when a controlled form element `value`/`checked` can be `undefined` on initial render and later becomes defined in the same component path. If the element is intentionally uncontrolled (`defaultValue`/`defaultChecked`), do not apply this rule.

### Exceptions / False Positives

- Do not flag intentionally uncontrolled inputs that use `defaultValue` or `defaultChecked` rather than `value`/`checked`.
- Do not flag when the component never renders the input until a defined value is available (for example, guarded render paths).
- If a wrapper component normalizes `undefined` before passing props to the DOM input, verify that normalization before commenting.

### Description

Initializing a controlled input's value as `undefined` and later setting it to a string causes React to warn about switching from uncontrolled to controlled. This indicates a state initialization bug.

### Anti-patterns to Flag

```tsx
// ❌ value starts as undefined, then becomes a string
const [input, setInput] = useState();
// ...
<input value={input} onChange={e => setInput(e.target.value)} />

// ❌ Value from optional prop without fallback
const [text, setText] = useState(props.initialText);
// If props.initialText is undefined on first render, input is uncontrolled
```

### Suggested Fix

Always initialize state with a value matching the controlled type:

```tsx
// ✅ Explicit empty string for text inputs
const [input, setInput] = useState('');

// ✅ Fallback for optional props
const [text, setText] = useState(props.initialText ?? '');
```
