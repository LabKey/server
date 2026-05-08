# Rule Catalog — Code Quality

> **Prerequisite:** Review and apply the common guidelines in [`common.md`](../common.md) before using this checklist.

## Props interface should be declared separately

**Urgency:** suggestion

### Category

Style

### Confidence Threshold

Flag only when the component has multiple props or the inline type is large enough to reduce readability. For tiny local components with one simple prop, prefer no comment unless the file is being standardized.

### Exceptions / False Positives

- Do not flag tiny local components with a single simple prop where an inline type is shorter and clearer.

### Description

Component props should be declared as a separate named interface rather than inline in the `FC<>` generic. This improves readability, enables JSDoc documentation on individual props, and maintains consistency across the codebase. Components with 2 or more props should always use a separate interface.

Wrong:

```tsx
const MyComponent: FC<{ report: ReportConfig; tab: ExtReportTab }> = ({ report, tab }) => {
    // ...
};
```

### Suggested Fix

```tsx
interface MyComponentProps {
    report: ReportConfig;
    tab: ExtReportTab;
}

const MyComponent: FC<MyComponentProps> = ({ report, tab }) => {
    // ...
};
```

## React components and hooks should have unit tests

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag new or significantly modified components/hooks lacking Jest tests when they contain logic (state, effects, event handlers, conditional rendering). Simple presentational components rendering static JSX may defer testing. Existing untested code not modified in the PR should not be flagged.

### Exceptions / False Positives

- Do not flag demo-only, internal layout wrappers, or placeholder components.
- Do not flag when tests exist in a different location (e.g., shared test suite) if verifiable.
- Do not flag existing untested code outside the PR scope.

### Description

Components with state, effects, event handlers, or conditional logic need Jest tests. Test what should always be covered: `useState`/`useReducer`, event handlers, conditional rendering, custom hooks with logic, permission-gated rendering. Layout-only components are optional.

### Anti-patterns to Flag

```tsx
// ❌ Stateful component with events but no tests
const Counter: FC = () => {
    const [count, setCount] = useState(0);
    return (
        <div>
            <p>Count: {count}</p>
            <button onClick={() => setCount(count + 1)}>Increment</button>
        </div>
    );
};

// ❌ Custom hook with async logic but no tests
const useItemData = (id: string) => {
    const [data, setData] = useState(null);
    useEffect(() => {
        // Error handling omitted for brevity
        async function load() {
            const result = await loadItemById(id);
            setData(result);
        }
        load();
    }, [id]);
    return data;
};
```

### Suggested Fix

Create `Component.test.tsx` with meaningful assertions per [`jest/business-logic.md`](../jest/business-logic.md):

```tsx
test('increments count when button clicked', async () => {
    render(<Counter />);
    await userEvent.click(screen.getByRole('button', { name: /increment/i }));
    expect(screen.getByText('Count: 1')).toBeInTheDocument();
});
```

### How to Detect

1. Check for `.test.tsx`/`.test.ts` file corresponding to modified component.
2. If missing, flag if component has: hooks, event handlers, or conditional logic.
3. If tests exist, verify they assert on behavior (not just render existence).

## Large JSX/TSX blocks should be extracted into separate components

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag JSX return statements >25 lines or those with multiple conditional branches (3+ `{condition && <...>}` or ternary operators). Simple single-element returns or small layout wrappers are exempt.

### Exceptions / False Positives

- Do not flag simple, single-element returns or small layout wrappers.
- Do not flag when extraction would require excessive prop-drilling that makes code less readable.

### Description

Large JSX blocks reduce readability, increase cognitive load, and make testing harder. Red flags: multiple conditional rendering branches, repeated patterns (like tab buttons with similar structure), deeply nested element hierarchies, and components managing multiple independent concerns.

### Anti-patterns to Flag

```tsx
// ❌ Return block with multiple conditional branches and repeated patterns
const DesignerPanel: FC<Props> = ({ data, showWarnings }) => {
    const [rightTab, setRightTab] = useState<'properties' | 'warnings'>('properties');

    return (
        <div className="designer">
            {showWarnings && (
                <div className="tabs" role="tablist">
                    <button
                        role="tab"
                        aria-selected={rightTab === 'properties'}
                        onClick={() => setRightTab('properties')}
                    >
                        Properties
                    </button>
                    <button
                        role="tab"
                        aria-selected={rightTab === 'warnings'}
                        onClick={() => setRightTab('warnings')}
                    >
                        Warnings
                    </button>
                </div>
            )}
            {(!showWarnings || rightTab === 'properties') && (
                <div role="tabpanel">
                    <PropertiesPanel data={data} />
                </div>
            )}
            {showWarnings && rightTab === 'warnings' && (
                <div role="tabpanel">
                    <WarningsPanel data={data} />
                </div>
            )}
        </div>
    );
};
```

**Red flags in this example:**
- 3 separate conditional render blocks
- Repeated tab button structure with duplicated accessibility attrs
- Multiple independent concerns (tabs, properties, warnings)
- Mixed conditional logic (both &&-chaining and ternaries)

### Suggested Fix

Extract the tab control into a reusable component:

```tsx
interface TabPanelProps {
    active: 'properties' | 'warnings';
    onChange: (tab: 'properties' | 'warnings') => void;
}

const TabPanel: FC<TabPanelProps> = ({ active, onChange }) => {
    const handlePropertiesClick = useCallback(() => onChange('properties'), [onChange]);
    const handleWarningsClick = useCallback(() => onChange('warnings'), [onChange]);

    return (
        <div className="tabs" role="tablist">
            <button
                role="tab"
                aria-selected={active === 'properties'}
                onClick={handlePropertiesClick}
            >
                Properties
            </button>
            <button
                role="tab"
                aria-selected={active === 'warnings'}
                onClick={handleWarningsClick}
            >
                Warnings
            </button>
        </div>
    );
};
TabPanel.displayName = 'TabPanel';

const DesignerPanel: FC<Props> = ({ data, showWarnings }) => {
    const [rightTab, setRightTab] = useState<'properties' | 'warnings'>('properties');

    return (
        <div className="designer">
            {showWarnings && <TabPanel active={rightTab} onChange={setRightTab} />}
            {(!showWarnings || rightTab === 'properties') && <PropertiesPanel data={data} />}
            {showWarnings && rightTab === 'warnings' && <WarningsPanel data={data} />}
        </div>
    );
};
DesignerPanel.displayName = 'DesignerPanel';
```

### How to Detect

1. **Multiple conditional branches:** Count `{condition && <...>}` and ternary operators. 3+ suggests extraction.
2. **Repeated patterns:** Similar button/input groups with duplicated props (e.g., `role="tab"`, `aria-selected`, `onClick`) → extract into component.
3. **Multiple state sections:** Component with separate state for tabs/panels (e.g., `const [tab, setTab]` and `const [panel, setPanel]`) often signals multiple concerns.
4. **Line count + nesting:** Return >25 lines OR deeply nested (3+ levels) conditional JSX → consider extraction.

## React components and hooks should have unit tests

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag new or significantly modified components/hooks lacking Jest tests when they contain logic (state, effects, event handlers, conditional rendering). Simple presentational components rendering static JSX may defer testing. Existing untested code not modified in the PR should not be flagged.

### Exceptions / False Positives

- Do not flag demo-only, internal layout wrappers, or placeholder components.
- Do not flag when tests exist in a different location (e.g., shared test suite) if verifiable.
- Do not flag existing untested code outside the PR scope.

### Description

Components with state, effects, event handlers, or conditional logic need Jest tests. Test what should always be covered: `useState`/`useReducer`, event handlers, conditional rendering, custom hooks with logic, permission-gated rendering. Layout-only components are optional.

### Anti-patterns to Flag

```tsx
// ❌ Stateful component with events but no tests
const Counter: FC = () => {
    const [count, setCount] = useState(0);
    return (
        <div>
            <p>Count: {count}</p>
            <button onClick={() => setCount(count + 1)}>Increment</button>
        </div>
    );
};

// ❌ Custom hook with async logic but no tests
const useItemData = (id: string) => {
    const [data, setData] = useState(null);
    useEffect(() => {
        // Error handling omitted for brevity
        async function load() {
            const result = await loadItemById(id);
            setData(result);
        }
        load();
    }, [id]);
    return data;
};
```

### Suggested Fix

Create `Component.test.tsx` with meaningful assertions per [`jest/business-logic.md`](../jest/business-logic.md):

```tsx
test('increments count when button clicked', async () => {
    render(<Counter />);
    await userEvent.click(screen.getByRole('button', { name: /increment/i }));
    expect(screen.getByText('Count: 1')).toBeInTheDocument();
});
```

### How to Detect

1. Check for `.test.tsx`/`.test.ts` file corresponding to modified component.
2. If missing, flag if component has: hooks, event handlers, or conditional logic.
3. If tests exist, verify they assert on behavior (not just render existence).

## Optional props that are always passed should be required

**Urgency:** suggestion

### Category

Correctness

### Confidence Threshold

Flag when the changed PR includes the component and all visible call sites (or a small, searchable set of call sites) and every usage passes the prop. If external consumers or broad usage make this uncertain, leave a note.

### Exceptions / False Positives

- Do not require making a prop required when the component is exported for external/unknown consumers that are not visible in the current repo or PR.
- Do not flag when the optional prop supports backward compatibility or gradual migration and that intent is documented.
- If call sites are numerous or spread across the codebase, treat this as an audit suggestion rather than a high-severity review comment.

### Description

When reviewing a component, check if any optional props (marked with `?`) are actually passed in every single usage of the component. If a prop is always provided at every call site, it should be declared as required rather than optional. If the value can legitimately be `undefined`, use `T | undefined` as the type instead of `prop?: T`.

This makes the component's contract explicit: callers know they must provide the prop, and the component implementation can rely on it being present (even if the value is `undefined`).

Wrong (if `onClose` is passed at every call site):

```tsx
interface ModalProps {
    title: string;
    onClose?: () => void;  // Optional, but always passed in practice
}

// Every usage passes onClose:
<Modal title="Confirm" onClose={handleClose} />
<Modal title="Settings" onClose={() => setOpen(false)} />
```

### Suggested Fix

```tsx
interface ModalProps {
    title: string;
    onClose: (() => void) | undefined;  // Required prop, value may be undefined
}

// Or if it truly should never be undefined:
interface ModalProps {
    title: string;
    onClose: () => void;  // Required prop, must have a value
}
```

### How to Check

1. Search for all usages of the component across the codebase
2. For each optional prop, verify whether any call site omits it
3. If all call sites provide the prop, flag it for conversion to required

**Scope note:** For widely-used shared components (5+ call sites), this check can be deferred to a separate audit. Focus on components with 1–4 usages where the call sites are visible in the current PR.

## No unused imports, variables, props, or exports

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag file-local unused imports/variables/props when unused status is clear in the changed file. For exported symbols, require evidence from a targeted search (preferably limited to changed/new exports in the PR) before flagging.

### Exceptions / False Positives

- Do not flag side-effect imports (for example, stylesheet imports, polyfills, module registration) that intentionally have no local references.
- Do not flag exports that are consumed indirectly through framework conventions, dynamic imports, or generated wiring without checking project patterns first.
- If verifying export usage requires a broad codebase audit outside the PR scope, lower confidence and leave a note.

### Description

Every import, variable, constant, destructured prop, and export in a file should be referenced or consumed. Unused symbols add noise, increase bundle size (for runtime imports), and signal incomplete refactors.

**Important:** Exported types, interfaces, and constants must be verified as actually imported somewhere in the codebase—don't assume an export is used just because it exists.

### Anti-patterns to Flag

```tsx
// ❌ Unused import
import { useState, useEffect } from 'react';
// only useState is used in the file — useEffect is dead

// ❌ Unused variable / constant
const label = 'hello';
// label is never read

// ❌ Unused destructured prop
const MyComponent: FC<Props> = ({ name, age, email }) => {
    return <span>{name}</span>;
    // age and email are destructured but never used
};

// ❌ Unused `declare` statement
declare const Ext4: any;
// Ext4 is never referenced in the file

// ❌ Unused exported type/interface/constant
export type ReportType = 'js' | 'query' | 'report';
// ReportType is exported but never imported anywhere in the codebase
```

### Suggested Fix

Remove the unused symbol. If it is an import, remove just the unused specifier (keep others on the same line). If an entire import statement becomes empty, delete the line.

```tsx
// ✅ Only import what is used
import { useState } from 'react';

// ✅ Only destructure props that are used
const MyComponent: FC<Props> = ({ name }) => {
    return <span>{name}</span>;
};

// ✅ Only export what is consumed elsewhere
// (remove exports that have no importers)
```

### How to Detect

1. For each `import { A, B, C }` statement, search the rest of the file for references to `A`, `B`, and `C` (excluding the import line itself and type-only re-exports). Flag any specifier with zero references.
2. For each top-level `const`, `let`, `var`, or `declare` statement, verify the declared name appears elsewhere in the file.
3. For each destructured prop in a component signature, verify the name appears in the component body.
4. **For each `export` (type, interface, const, function), grep the codebase to verify it is imported somewhere.** Do not assume exports are used—verify with a search.

**Scope note:** Full codebase verification of exports is expensive during review. Focus on exports introduced or modified in the current PR. 

## No redundant or historical comments

**Urgency:** suggestion

### Category

Style

### Confidence Threshold

Flag comments only when they add no information beyond the code or document obsolete history without current relevance. If a comment explains a constraint, workaround, or non-obvious intent, do not flag it.

### Exceptions / False Positives

- Do not flag comments required by tooling or documentation generation (for example, public API docs or lint-enforced JSDoc).
- Allow brief migration/history comments when they explain a current compatibility constraint and include actionable context for maintainers.
- Do not flag comments that clarify business rules, framework quirks, or sequencing constraints not obvious from the code.

### Description

Comments should explain **why** something is done, not **what** is done. Remove comments that restate what the code already communicates through its naming, and comments that document the history or evolution of the code rather than its current purpose. These add noise without value.

### Anti-patterns to Flag

```tsx
// ❌ Comment restates the interface/variable name
/** Props for JSReportWrapper component */
interface JSReportWrapperProps {

// ❌ Comment restates what the variable name already says
// The active tab ID
const activeTabId = userSelectedTabId ?? defaultActive.tabId;

// ❌ Comment describes code history or refactoring origin
/**
 * Extracted from the former ReportTab render-prop component.
 */
const ReportPanel: FC<ReportPanelProps> = (props) => {

// ❌ Comment narrates what the next line of code does
// Set the user selected category
setUserSelectedCategory(category);

// ❌ Comment explains an obvious return
// Return null if no data
if (!data) return null;
```

### When Comments Are Appropriate

```tsx
// ✅ Explains WHY — a non-obvious business rule or constraint
// LABKEY.WebPart expects a string ID, not a DOM element
const targetId = `report-target-${report.id}-${uniqueId}`;

// ✅ Explains WHY — clarifies a workaround or unexpected pattern
// partConfig requires an index signature because filter.getURLParameterName()
// generates dynamic keys (e.g., "query.Id~eq") that can't be statically typed.
const partConfig: Record<string, unknown> = { ... };

// ✅ Documents a subtle gotcha that isn't obvious from the code
// This effect must run after reports have loaded, not on mount
useEffect(() => { ... }, [reports]);
```

### How to Detect

1. Read each comment and the code it annotates. If deleting the comment loses no information that isn't already conveyed by the identifier names, types, or structure, flag it.
2. Look for comments containing phrases like "extracted from", "formerly", "used to be", "was previously", "refactored from" — these describe history, not current behavior.
3. Look for comments that mirror a pattern like `/** <Type> for <Name> */` directly above a type/interface/class declaration with a self-descriptive name.

## Conditional class names use utility function

**Urgency:** suggestion

### Category

Style

### Confidence Threshold

Flag only when the file/area already uses a class-name utility convention (for example, `classnames`, `clsx`, or local `cn`) and the inline conditional harms consistency/readability. For one-off simple cases in mixed-style files, prefer a suggestion.

### Exceptions / False Positives

- Do not flag simple static class strings or library APIs that require a computed string expression without a utility helper.
- If introducing a utility import for a single trivial ternary would add more noise than value in a local file, prefer a suggestion.

### Description

Ensure conditional CSS is handled via the `classNames` utility (imported from `classnames`) instead of custom ternaries, string concatenation, or template strings directly in the className prop.

### Anti-patterns to Flag

```tsx
// ❌ Direct ternary in className
className={isActive ? 'active' : 'inactive'}
className={condition ? 'class-a' : 'class-b'}

// ❌ Template string with ternary
className={`base ${isActive ? 'active' : ''}`}

// ❌ String concatenation
className={'btn ' + (isActive ? 'btn-active' : 'btn-inactive')}
```

### Correct Pattern

```tsx
import classNames from 'classnames';

// ✅ Using classNames utility with object syntax
className={classNames('btn', {
    'btn-active': isActive,
    'btn-inactive': !isActive,
})}

// ✅ Or with conditional classes
className={classNames({
    active: isActive,
    inactive: !isActive,
})}
```

### Exceptions

- **Single static class swap:** A plain ternary choosing between two static string classes (`className={isX ? 'a' : 'b'}`) is acceptable when there is no combination logic. The `classNames` utility adds value primarily when combining multiple classes, not when selecting one of two.
- **Third-party / framework classes:** Bootstrap or other framework classes used as-is do not need `classNames` wrapping (e.g., `className={isOpen ? 'panel-open' : 'panel-closed'}`).
- **CSS Modules:** Ternaries over CSS Module references are idiomatic and do not require `classNames` (e.g., `className={isActive ? styles.active : styles.inactive}`).

### How to Detect

Search for patterns matching `className={...?...:...}` where the ternary is not wrapped in a `classNames()` or `cn()` call.

## CSS class names should follow BEM naming convention

**Urgency:** suggestion

### Category

Style

### Confidence Threshold

Flag only when the component/stylesheet area is clearly using BEM or the PR is introducing new classes into a BEM-styled file. If the file uses CSS Modules or another established naming scheme, do not apply this rule.

### Exceptions / False Positives

- Do not flag CSS Modules patterns or hashed class references where BEM is not the project convention.
- Do not flag third-party library class names or attributes that must match external CSS/JS behavior.
- In legacy non-BEM files, prefer localized consistency over forcing a partial BEM conversion in an unrelated PR.

### Description

CSS class names should follow the BEM (Block, Element, Modifier) convention for consistency and maintainability.

- **Block:** A standalone component name in kebab-case (e.g., `search-form`)
- **Element:** A part of a block, separated by double underscores `__` (e.g., `search-form__input`)
- **Modifier:** A variant or state, separated by double hyphens `--` (e.g., `search-form--disabled`, `search-form__input--large`)

### Anti-patterns to Flag

```tsx
// ❌ Generic or meaningless class names
className="container1"
className="big-wrapper"

// ❌ camelCase class names
className="searchForm"
className="navBarItem"

// ❌ Deeply nested elements (more than one __ level)
className="search-form__input__icon__svg"

// ❌ Modifier used without the base class
className="--disabled"
className="__input"

// ❌ Mixing conventions (BEM + camelCase or BEM + arbitrary nesting)
className="searchForm__input--active"
```

### Correct Pattern

```tsx
// ✅ Block
className="search-form"

// ✅ Element
className="search-form__input"
className="search-form__submit-button"

// ✅ Modifier on block
className="search-form--disabled"
className="search-form--compact"

// ✅ Modifier on element
className="search-form__input--large"
className="search-form__input--error"

// ✅ Combining base class with modifier using classNames utility
className={classNames('search-form__input', {
    'search-form__input--large': isLarge,
    'search-form__input--error': hasError,
})}
```

### How to Detect

1. Look for `className` values that use camelCase instead of kebab-case — these should be converted to BEM blocks.
2. Flag class names containing more than one `__` separator (e.g., `block__el1__el2`) — flatten to a single element level.
3. Flag class names that start with `--` or `__` without a preceding block name — modifiers and elements must always include their block.
4. Look for generic class names like `container`, `wrapper`, `item`, `box` without a block prefix — these should be scoped to a BEM block.

### Exceptions

- **Third-party / framework classes:** Bootstrap (`btn`, `btn-primary`, `col-xs-*`, `panel-body`, `form-control`, etc.), LabKey API classes (`labkey-*`), or other library-provided class names follow their own conventions and must not be flagged or renamed.
- **CSS Modules:** When using CSS Modules (`.module.css` / `.module.scss`), camelCase class names are standard since they become JS property names (e.g., `styles.searchForm`, `styles.activeItem`). Do not flag these as BEM violations.
- **Existing non-BEM code:** Class names in unchanged code that predate BEM adoption should not be flagged. Only apply BEM conventions to new or actively modified class names in the PR.

## No orphaned or unused CSS/SCSS rules

**Urgency:** suggestion

### Category

Maintainability

### Confidence Threshold

Flag when a changed selector has no usage in the changed component(s) and a targeted search finds no references. If selectors may be referenced dynamically or outside TSX/JSX, lower confidence and avoid a high-severity review comment without evidence.

### Exceptions / False Positives

- Do not flag selectors that are referenced dynamically (for example, string composition, server-rendered markup, or JS interop) without checking usage patterns.
- Do not assume TSX/JSX-only usage; classes may be used in legacy templates, tests, docs, or external scripts tied to the component.
- If full verification requires codebase-wide searching beyond the PR scope, mark as a follow-up suggestion rather than a high-severity finding.

### Description

Every CSS/SCSS selector in a stylesheet should correspond to a `className` actually used in the associated TSX/JSX component(s). Orphaned rules add dead code, increase bundle size, and mislead future developers into thinking a class is in use.

### Anti-patterns to Flag

```scss
// ❌ Selector exists in SCSS but no component references it
.filter-panel {
    &__header { ... }
    &__body { ... }
    &__toggle-buttons { ... }  // No TSX file uses "filter-panel__toggle-buttons"
}

// ❌ Entire rule block with no matching className in any component
.legacy-banner {
    display: flex;
    padding: 8px;
}
// No component renders className="legacy-banner"
```

### Correct Pattern

```scss
// ✅ Every selector maps to a className used in a component
.filter-panel {
    &__header { ... }   // <div className="filter-panel__header">
    &__body { ... }      // <div className="filter-panel__body">
}
// Removed &__toggle-buttons because no component uses it
```

### How to Detect

1. For each selector in a changed or reviewed SCSS/CSS file, search the codebase for a corresponding `className` usage (e.g., grep for the resolved class name in TSX/JSX files).
2. Flag any selector with zero references — it is likely orphaned from a previous refactor.
3. Pay special attention to nested BEM selectors (`&__element`, `&--modifier`) whose parent block exists but the specific element or modifier is no longer rendered.

**Scope note:** Full codebase verification is expensive during manual review. Focus on CSS rules within files changed in the current PR.