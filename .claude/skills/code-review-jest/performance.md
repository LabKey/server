# Performance

> **Prerequisite:** Review and apply the shared guidelines in [`review-priority-and-format.md`](../review-priority-and-format.md) before using this checklist.

## No redundant or near-duplicate tests

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag when two tests clearly exercise the same code path with identical setup/action and only trivial literal differences. If separate tests improve failure localization or document distinct branches, prefer a suggestion over a required merge.

### Exceptions / False Positives

- Do not merge tests that exercise different branches, error paths, permission states, or feature flags even if they look similar.
- Keep separate tests when splitting assertions materially improves failure diagnostics or readability for a complex behavior.
- Do not force `test.each` if parameterization would obscure the intent or make debugging harder than explicit tests.

### Rules

1. **Merge tests that assert the same behavior with different literal values.** If two or more tests render the same component, trigger the same interaction, and assert the same outcome shape — differing only in the data passed in — combine them into a single `test.each` or a single test with multiple representative cases.
2. **Merge tests whose Arrange and Act steps are identical.** If two tests set up the same state and perform the same action but assert on different aspects of the result, combine them into one test with multiple assertions. A single test with several `expect` calls is preferable to duplicate setup/teardown overhead.
3. **Remove tests that are strict subsets of another test.** If test A asserts that a button is rendered and test B asserts that clicking the same button fires a callback, test A is redundant — the click in test B already proves the button exists. **Exception:** keep the existence test if it tests a different conditional path than the behavioral test (e.g., the existence test renders with restricted permissions while the click test renders with full permissions).
4. **Keep separate tests for genuinely distinct code paths.** Two tests that look similar but exercise different branches (e.g., an error path vs. a success path, an empty list vs. a populated list) are not duplicates and must remain separate.

### Examples

```tsx
// ❌ BAD — three nearly identical tests differing only in input label
test('renders label for first name', () => {
    render(<FormField label="First Name" />);
    expect(screen.getByText('First Name')).toBeInTheDocument();
});

test('renders label for last name', () => {
    render(<FormField label="Last Name" />);
    expect(screen.getByText('Last Name')).toBeInTheDocument();
});

test('renders label for email', () => {
    render(<FormField label="Email" />);
    expect(screen.getByText('Email')).toBeInTheDocument();
});

// ✅ GOOD — combined into a parameterized test
test.each(['First Name', 'Last Name', 'Email'])('renders label "%s"', (label) => {
    render(<FormField label={label} />);
    expect(screen.getByText(label)).toBeInTheDocument();
});


// ❌ BAD — two tests with identical Arrange/Act, different Assert
test('disables submit when form is invalid', () => {
    render(<MyForm valid={false} />);
    expect(screen.getByRole('button', { name: /submit/i })).toBeDisabled();
});

test('shows validation message when form is invalid', () => {
    render(<MyForm valid={false} />);
    expect(screen.getByText('Please fix errors above')).toBeInTheDocument();
});

// ✅ GOOD — combined into one test with both assertions
test('shows validation state when form is invalid', () => {
    render(<MyForm valid={false} />);
    expect(screen.getByRole('button', { name: /submit/i })).toBeDisabled();
    expect(screen.getByText('Please fix errors above')).toBeInTheDocument();
});


// ❌ BAD — subset test is redundant
test('renders the delete button', () => {
    render(<ItemRow item={mockItem} onDelete={mockDelete} />);
    expect(screen.getByRole('button', { name: /delete/i })).toBeInTheDocument();
});

test('calls onDelete when delete button is clicked', () => {
    render(<ItemRow item={mockItem} onDelete={mockDelete} />);
    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    expect(mockDelete).toHaveBeenCalledWith(mockItem.id);
});

// ✅ GOOD — only the behavioral test remains (it implicitly proves the button exists)
test('calls onDelete when delete button is clicked', () => {
    render(<ItemRow item={mockItem} onDelete={mockDelete} />);
    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    expect(mockDelete).toHaveBeenCalledWith(mockItem.id);
});


// ✅ OK — these look similar but test genuinely different code paths
test('renders empty state when no items', () => {
    render(<ItemList items={[]} />);
    expect(screen.getByText('No items found')).toBeInTheDocument();
});

test('renders item rows when items are provided', () => {
    render(<ItemList items={[mockItem]} />);
    expect(screen.getByText(mockItem.name)).toBeInTheDocument();
});
```
