# WCAG 2.2 Level AA -- Static Code Review Checklist

This checklist covers WCAG 2.2 Level AA success criteria that can be detected through code review. Criteria that require runtime testing (e.g., timing, audio descriptions) are excluded.

---

## Principle 1: Perceivable

### 1.1.1 Non-text Content (Level A)
**Urgency: urgent**
- `<img>` elements must have an `alt` attribute. Decorative images should use `alt=""` or `role="presentation"`.
- Icon-only buttons/links must have an accessible name (`aria-label`, `aria-labelledby`, or visually hidden text).
- `<svg>` elements used as content must have `role="img"` and an accessible name (`aria-label` or `<title>`).
- Font icon elements (e.g., `<i className="fa fa-..."`) used as interactive controls must have an accessible name.
- **Exception:** Purely decorative elements that don't convey information.

### 1.3.1 Info and Relationships (Level A)
**Urgency: urgent**
- Use semantic HTML elements (`<nav>`, `<main>`, `<header>`, `<footer>`, `<section>`, `<article>`) instead of generic `<div>` for landmarks.
- Headings (`<h1>`-`<h6>`) must follow a logical hierarchy -- no skipped levels.
- Form inputs must have associated `<label>` elements (via `htmlFor`/`id`) or `aria-label`/`aria-labelledby`.
- Data tables must use `<th>` with `scope` attributes. Do not use tables for layout.
- Related form controls should be grouped with `<fieldset>` and `<legend>`.
- Lists of items should use `<ul>`, `<ol>`, or `<dl>` -- not styled `<div>` sequences.

### 1.3.2 Meaningful Sequence (Level A)
**Urgency: suggestion**
- DOM order should match the visual reading order. Avoid CSS that reorders content (`order`, `flex-direction: row-reverse`) in ways that break reading sequence.

### 1.3.4 Orientation (Level AA)
**Urgency: suggestion**
- Do not restrict display to a single orientation via CSS or JS unless essential.

### 1.3.5 Identify Input Purpose (Level AA)
**Urgency: suggestion**
- Form fields for personal data (name, email, phone, address, etc.) should have appropriate `autoComplete` attributes.

### 1.4.1 Use of Color (Level A)
**Urgency: urgent**
- Color must not be the only visual means of conveying information (e.g., error states, required fields, status). Provide text, icons, or patterns as well.

### 1.4.3 Contrast (Minimum) (Level AA)
**Urgency: urgent**
- Text color against its background must meet 4.5:1 ratio for normal text, 3:1 for large text (18pt+ or 14pt+ bold).
- Check hardcoded color values in CSS/SCSS and inline styles. Flag combinations that are visually likely to fail (e.g., light gray on white).
- **Note:** Exact contrast can only be verified with a tool; flag suspicious values for manual check.

### 1.4.4 Resize Text (Level AA)
**Urgency: suggestion**
- Text sizes should use relative units (`rem`, `em`, `%`) rather than fixed `px` for body text.
- Layouts should not break at 200% zoom. Avoid fixed-height containers with `overflow: hidden` on text content.

### 1.4.11 Non-text Contrast (Level AA)
**Urgency: suggestion**
- UI components (form controls, buttons) and meaningful graphics must have at least 3:1 contrast against adjacent colors.

### 1.4.13 Content on Hover or Focus (Level AA)
**Urgency: suggestion**
- Tooltips/popovers shown on hover must also be dismissible (Escape key), hoverable (user can move pointer to the tooltip), and persistent (don't disappear while the user is interacting).

---

## Principle 2: Operable

### 2.1.1 Keyboard (Level A)
**Urgency: urgent**
- All interactive elements must be keyboard accessible. `onClick` on non-interactive elements (`<div>`, `<span>`) requires `onKeyDown`/`onKeyUp`, `tabIndex="0"`, and `role`.
- Prefer semantic elements (`<button>`, `<a>`, `<input>`) over ARIA-enhanced `<div>`s.
- Drag-and-drop must have a keyboard alternative.
- Custom widgets must handle expected key interactions (e.g., arrow keys for menus/tabs, Space/Enter for buttons).

### 2.1.2 No Keyboard Trap (Level A)
**Urgency: urgent**
- Modal dialogs must trap focus *within* the modal but allow dismissal via Escape.
- Focus must not get stuck in any component. Verify that custom focus management doesn't prevent tabbing away.

### 2.4.1 Bypass Blocks (Level A)
**Urgency: suggestion**
- Pages with repeated navigation should provide a "skip to main content" link or use landmark regions.

### 2.4.2 Page Titled (Level A)
**Urgency: suggestion**
- Each page/view should set a descriptive `<title>` or use `document.title`.

### 2.4.3 Focus Order (Level A)
**Urgency: urgent**
- `tabIndex` values greater than 0 disrupt natural focus order -- flag any `tabIndex` > 0.
- Tab order must follow a logical sequence through the page.

### 2.4.4 Link Purpose (Level A)
**Urgency: suggestion**
- Link text must describe the destination. Flag "click here", "here", "read more" without context.
- If link text is generic, `aria-label` or `aria-describedby` should provide context.

### 2.4.6 Headings and Labels (Level AA)
**Urgency: suggestion**
- Headings and labels must be descriptive. Flag empty headings or labels.

### 2.4.7 Focus Visible (Level AA)
**Urgency: urgent**
- Do not remove focus indicators. Flag `outline: none`, `outline: 0`, or `:focus { outline: none }` without a replacement focus style.
- Custom focus styles must be clearly visible.

### 2.4.11 Focus Not Obscured (Minimum) (Level AA) -- NEW in 2.2
**Urgency: urgent**
- When a component receives focus, it must not be entirely hidden by other content (e.g., sticky headers, footers, overlays).
- Check for `position: fixed`/`sticky` elements that could cover focused items. Ensure `scroll-padding` or `scroll-margin` accounts for sticky elements.

### 2.4.13 Focus Appearance (Level AAA, but recommended)
**Urgency: suggestion**
- Custom focus indicators should have at least a 2px solid outline with 3:1 contrast against adjacent colors.

### 2.5.7 Dragging Movements (Level AA) -- NEW in 2.2
**Urgency: urgent**
- Any functionality that uses dragging must provide a single-pointer alternative (e.g., up/down buttons to reorder, click-to-select then click-to-place).
- **Exception:** Dragging is essential to the function (rare).

### 2.5.8 Target Size (Minimum) (Level AA) -- NEW in 2.2
**Urgency: suggestion**
- Interactive targets should be at least 24x24 CSS pixels, or have sufficient spacing so the target + spacing meets 24px.
- **Exceptions:** Inline text links, targets where the size is determined by the user agent (e.g., default checkboxes), essential presentation.

---

## Principle 3: Understandable

### 3.1.1 Language of Page (Level A)
**Urgency: suggestion**
- HTML element should have a `lang` attribute (`<html lang="en">`).

### 3.1.2 Language of Parts (Level AA)
**Urgency: suggestion**
- Content in a different language than the page should use `lang` attribute on its container.

### 3.2.1 On Focus (Level A)
**Urgency: urgent**
- Focus must not trigger a change of context (e.g., page navigation, form submission, opening a new window).

### 3.2.2 On Input (Level A)
**Urgency: urgent**
- Changing a form control value must not automatically trigger a change of context unless the user is informed beforehand.
- Flag `onChange` handlers that submit forms or navigate without user confirmation.

### 3.3.1 Error Identification (Level A)
**Urgency: urgent**
- Form errors must be described in text, not just color. Error messages must identify which field has the error.
- Error messages must be programmatically associated with their inputs (`aria-describedby`, `aria-errormessage`, or `aria-invalid`).

### 3.3.2 Labels or Instructions (Level A)
**Urgency: urgent**
- Form inputs must have visible labels. Placeholder text alone is not sufficient as a label.
- Required fields must be indicated in a way that doesn't rely solely on color.

### 3.3.3 Error Suggestion (Level AA)
**Urgency: suggestion**
- When an input error is detected, provide a suggestion for correction if possible.

### 3.3.7 Redundant Entry (Level A) -- NEW in 2.2
**Urgency: suggestion**
- Do not require users to re-enter information they have already provided in the same process/session.
- **Exceptions:** Re-entering for security purposes, or when previously entered info is no longer valid.

### 3.3.8 Accessible Authentication (Minimum) (Level AA) -- NEW in 2.2
**Urgency: urgent**
- Authentication must not require cognitive function tests (e.g., CAPTCHA, puzzle) unless an alternative is provided (e.g., object recognition, personal content).
- Allow pasting into password fields. Do not block password managers.

---

## Principle 4: Robust

### 4.1.2 Name, Role, Value (Level A)
**Urgency: urgent**
- Custom components must expose correct ARIA roles, states, and properties.
- `aria-expanded`, `aria-selected`, `aria-checked`, `aria-pressed` must accurately reflect component state.
- `aria-hidden="true"` must not be set on focusable or interactive elements.
- `role` values must be valid WAI-ARIA roles.
- IDs referenced by `aria-labelledby`, `aria-describedby`, `aria-controls`, etc. must exist in the DOM.

### 4.1.3 Status Messages (Level AA)
**Urgency: suggestion**
- Status messages (success, error, loading, progress) that don't receive focus must use `role="status"`, `role="alert"`, or `aria-live` regions so screen readers announce them.
- Use `aria-live="polite"` for non-urgent updates, `aria-live="assertive"` for critical alerts.

---

## LabKey-Specific Patterns

These patterns are common in the LabKey codebase and deserve extra attention:

### React Components (`@labkey/components`, `@labkey/premium`)
- Verify `Alert` components use appropriate ARIA roles.
- Check `Modal`/`ModalDialog` components trap focus and are dismissible via Escape.
- Ensure `Grid`/`QueryGrid` table components use proper `<table>` semantics with headers.
- Check custom dropdown/select components for keyboard navigation (arrow keys, Escape, Enter).
- Ensure tags other than buttons that have an `onClick` include an `onKeyDown` handler

### JSP Pages
- Verify `<labkey:form>` and `<labkey:input>` render with proper label associations.
- Check `<labkey:link>` and `<labkey:button>` produce accessible HTML.

### ExtJS Components
- ExtJS components often lack accessibility. Flag custom ExtJS widgets that have no ARIA markup.
- Verify ExtJS modals/windows are keyboard dismissible.
