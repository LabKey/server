# Rule Catalog — Performance

> **Prerequisite:** Review and apply the common guidelines in [`common.md`](../common.md) before using this checklist.

## Inline object/array literals in JSX props

**Urgency:** urgent

### Category

Maintainability

### Confidence Threshold

Flag when referential identity matters in the changed code (for example, prop passed to a memoized child, dependency-sensitive hook, or effectful child) or when re-renders are already a known issue. If the child is not memoized and the object is tiny, prefer a suggestion or no comment.

### Exceptions / False Positives

- Do not flag inline literals passed to non-memoized children when there is no evidence that referential identity affects behavior or performance.
- Do not require `useMemo` for values that are truly static and can simply be hoisted to module scope instead.
- Avoid adding `useMemo` by default when it meaningfully harms readability and there is no demonstrated performance concern.

### Description

Do not pass inline object or array literals directly as JSX props. Each render creates a new reference, causing child components to re-render even when the values haven't changed. Extract the value into a `useMemo` (or a module-level constant if truly static) to preserve referential identity.

Wrong:

```tsx
<HeavyComp
    config={{
        provider: ...,
        detail: ...
    }}
/>
```

Right:

```tsx
const config = useMemo(() => ({
    provider: ...,
    detail: ...
}), [provider, detail]);

<HeavyComp
    config={config}
/>
```

---

## Expensive computations in render should use `useMemo`

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag when the computation is non-trivial (for example, sorting/filtering large collections or repeated transformations) and can run frequently with unchanged inputs. For small arrays or infrequent renders, make this a suggestion only.

### Exceptions / False Positives

- Do not force `useMemo` for cheap computations over small data where memoization adds more complexity than benefit.
- Do not flag one-time or rarely re-rendered components without evidence that render cost is material.
- If dependencies are hard to express correctly and memoization risks stale data bugs, prefer a profiling-backed follow-up suggestion.

### Description

Operations that sort, filter, reduce, or transform large collections should be wrapped in `useMemo` so the work only runs when inputs change — regardless of whether the result is passed as a prop. This rule targets **computational cost**, not referential identity.

### Anti-patterns to Flag

```tsx
// ❌ Re-sorts on every render even when `items` and `sortKey` haven't changed
const SortedList: FC<{ items: Item[]; sortKey: string }> = ({ items, sortKey }) => {
    const sorted = [...items].sort((a, b) => a[sortKey].localeCompare(b[sortKey]));
    return <ul>{sorted.map(item => <li key={item.id}>{item.name}</li>)}</ul>;
};
```

### Suggested Fix

```tsx
// ✅ Only recalculates when items or sortKey change
const SortedList: FC<{ items: Item[]; sortKey: string }> = ({ items, sortKey }) => {
    const sorted = useMemo(
        () => [...items].sort((a, b) => a[sortKey].localeCompare(b[sortKey])),
        [items, sortKey]
    );
    return <ul>{sorted.map(item => <li key={item.id}>{item.name}</li>)}</ul>;
};
```

---

## No component definitions inside other components

**Urgency:** urgent

### Category

Correctness

### Confidence Threshold

Flag as a high-severity finding when a nested function is used as a React component type (capitalized and rendered with JSX) inside a component body, causing remounts and state loss. Do not confuse this with render helpers that return JSX but are not treated as component types.

### Exceptions / False Positives

- Do not flag plain helper functions inside components that return JSX and are called like normal functions (not rendered as `<Helper />`).
- Do not flag render-prop callbacks or inline functions passed to APIs that expect functions rather than component types.
- If the nested component is intentionally recreated and stateless for a localized pattern, prefer a note unless remount behavior is harmful.

### Description

Defining a component (function or arrow) inside another component's render body creates a new component type on every render. React unmounts and remounts the inner component each time, destroying all state and DOM, which causes flickering and performance problems.

### Anti-patterns to Flag

```tsx
// ❌ Inner component re-created every render — loses state on each parent re-render
const ParentComponent: FC = () => {
    const InnerRow = ({ item }) => <tr><td>{item.name}</td></tr>;

    return <table>{items.map(item => <InnerRow key={item.id} item={item} />)}</table>;
};
```

### Suggested Fix

Move the inner component to module scope:

```tsx
// ✅ Stable component identity — React can reconcile correctly
const InnerRow: FC<{ item: Item }> = ({ item }) => <tr><td>{item.name}</td></tr>;

const ParentComponent: FC = () => {
    return <table>{items.map(item => <InnerRow key={item.id} item={item} />)}</table>;
};
```