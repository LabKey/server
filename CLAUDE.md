# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LabKey Server is a large Java web application platform for biomedical research data management. It uses a modular monolith architecture with 150+ Gradle modules, built on Spring Boot 4 / Spring Framework 7 with embedded Tomcat 11. It targets Java 25 and supports both PostgreSQL and MS SQL Server databases.

## Build Commands

```bash
# Configure IntelliJ IDEA project files
./gradlew ijConfigure

# Select database (populates application.properties from templates)
./gradlew pickPg          # PostgreSQL
./gradlew pickMssql       # MS SQL Server

# Build and deploy to embedded Tomcat
./gradlew deployApp

# Build a specific module
./gradlew :server:modules:platform:core:build

# Build with a predefined module set
./gradlew -PmoduleSet=community build

# Exclude test modules for faster builds
./gradlew -PexcludeTestModules build

# Build a distribution
./gradlew -PmoduleSet=distributions :distributions:base:dist
```

## Tests

**Unit tests** are static `TestCase` inner classes within production source files. They are registered via the module's `getUnitTests()` method and run within the server JVM.

**Integration tests** require a running server and database. They are registered via `getIntegrationTests()`.

**Selenium UI tests** (in `server/testAutomation/` and the `test` directory of many modules):
```bash
./gradlew :server:testAutomation:initProperties    # Generate test.properties
./gradlew :server:testAutomation:uiTests -Psuite=DRT   # Run a test suite
```
UI tests require a running LabKey server, a browser driver (ChromeDriver or Geckodriver) on PATH, and configured `test.properties`.

## Architecture

### Module System

Each module lives under `server/modules/` and contains:
- `module.properties` — metadata including `ModuleClass`, `SchemaVersion`, `SupportedDatabases`
- `build.gradle` — uses `org.labkey.build.module` plugin
- A module class extending `SpringModule` (which extends `DefaultModule` implementing `Module`)

Module lifecycle methods in order: `init()` → `versionUpdate()` → `afterUpdate()` → `startup()` → `startupAfterSpringConfig()` → `startBackgroundThreads()` → `destroy()`

In `init()`, modules register controllers via `addController("name", Controller.class)` and set up service implementations. Controllers follow the pattern `*Controller.java`.

### Core Platform Modules (`server/modules/platform/`)

The `api` module provides the core framework (Module interface, SpringModule base class, services, utilities). Other key platform modules: `core` (auth, security, admin), `query` (SQL engine), `experiment`, `study`, `assay`, `pipeline` (job processing), `search`, `audit`, `visualization`.

### Entry Point

`server/embedded/src/org/labkey/embedded/LabKeyServer.java` — a `@SpringBootApplication` that configures embedded Tomcat, SSL/TLS, Content Security Policy, and Log4J2.

### Database

Dual database support (PostgreSQL and MS SQL Server). Configuration lives in `server/configs/application.properties`, populated by `pickPg`/`pickMssql` tasks from `pg.properties`/`mssql.properties` templates. Each module declares its `SchemaVersion` in `module.properties` and manages its own schema migrations.

### Frontend

Mix of JSPs, React (via `@labkey/components`, `@labkey/premium`), ExtJS, and vanilla JS. Modules with TypeScript have their own `package.json` and use Webpack builds (via `@labkey/build`). TypeScript code is linted and formatted with `@labkey/eslint-config`. Node.js and npm versions are pinned in `gradle.properties` and downloaded during build.

We keep local copies of the `@labkey` packages in the `clientAPIs/` directory. You can find the following packages in this directory:
- `@labkey/api`: `clientAPIs/labkey-api-js/`
- `@labkey/components`: `clientAPIs/labkey-ui-components/packages/components/`
- `@labkey/build`: `clientAPIs/labkey-ui-components/packages/build/`
- `@labkey/eslint-config`: `clientAPIs/labkey-ui-components/packages/eslint-config/`
- `@labkey/premium`: `clientAPIs/labkey-ui-premium/`

The local copies of the packages may contain changes related to the current branches in any of the modules that have an NPM build. For example there may be changes to the `@labkey/components` package that affect the package in the `server/modules/platform/core` module. These packages are not required to be present to build the project, so they may not be available. If they are not present, you can assume that there are no changes in those packages relevant to the current branch. If you suspect an issue is with one of the packages, but it is not present you may prompt the user to check out a local copy of the relevant package.

### React Conventions
- We are using React 18
- **Component typing:** Use `FC<Props>` (or `FC` for no-prop components) for proper components used as `<Component />`. Use `ReactNode` as the return type for render helpers — functions that return JSX but are not used as components (typically named with a lowercase letter or a `render` prefix). Do **not** annotate with `JSX.Element` or `ReactElement`.
- **React type imports:** Always import React types directly from `react` rather than accessing them via the `React.*` namespace. For example: `import { FC, ReactNode } from 'react'`, never `React.FC` or `React.ReactNode`.
- **displayName:** Set `ComponentName.displayName = 'ComponentName'` immediately after each `React.FC` definition so it appears correctly in React DevTools and error boundaries.
- **Props interfaces:** Declare a separate named `interface ComponentNameProps` for any component with two or more props. Do not inline the type in the `FC<>` generic.
- **Event handlers:** Wrap all event handlers defined in a component body with `useCallback`, including those passed to native DOM elements. Do not use inline arrow functions in JSX (e.g., `onClick={() => doX()}`); extract and memoize them instead. Be aware that `useCallback` on a factory function (one that itself returns a new function) does not memoize the result — each invocation still produces a new reference.

### Distributions

The `distributions/` directory defines 60+ distribution configurations that select which modules to package. Distributions inherit from each other (most inherit from `:distributions:base`). Distribution directory names must not collide with module names.

### Dependency Management

All external library versions are centralized in `gradle.properties` (200+ version properties). The root `build.gradle` forces consistent versions across all modules via `resolutionStrategy`. Always consult before adding, removing, or updating a third-party dependency.

## Java Coding Conventions

- **Java Streams**: Prefer `Stream` API over traditional for-loops for collection processing.
- **Resources**: Use try-with-resources for automatic resource management.
- **Nullability**: Use `org.jetbrains.annotations.NotNull` and `org.jetbrains.annotations.Nullable` annotations. Be explicit in public API signatures.
- **Logging**: Use Log4J2. Never use System.out or System.err. Name the static logger `LOG`, initialized via `LogHelper.getLogger()`:
  ```java
  private static final Logger LOG = LogHelper.getLogger(MyClass.class, "optional description");
  ```
- **Unit tests**: Create a static `TestCase` inner class extending `Assert` in the same file as production code. Use JUnit 4 annotations (`@Test`). Register new test classes in the owning module's `getUnitTests()` (or `getIntegrationTests()` if the test requires the server to be running).
- **Selenium tests**
  - Subclass `BaseWebDriverTest`. Use a `@BeforeClass` for setup and override `doCleanup()` for cleanup. See `SecurityTest` as an example.
  - Templates for Selenium test classes and page objects are in '.idea/fileTemplates/'
  - **Page/component objects over raw locators**: When a `DataRegionTable`, `CustomizeView`, or other component method returns a typed page or component object, use that object's API rather than falling back to `setFormElement`/`Locator` calls. For example, `DataRegionTable.clickInsertNewRow()` returns an insert-row page object whose fields should be set through its typed methods.
  - **Remote API library over UI for setup**: When setting up a project for testing, use classes from `org.labkey.remoteapi` for setup rather than navigating through the UI. Create test-specific API wrappers for actions that are not yet exposed in the `labkey-api-java` library.
  - **Use API helpers over raw Commands**: Helpers such as `org.labkey.test.params.assay.AssayDesign` and `org.labkey.test.params.experiment.SampleTypeDefinition` wrap multiple API calls into a single operation or add additional functionality.
  - **Never navigate in 'finally' blocks or JUnit '@After'/'@AfterClass' methods**: It prevents the base class from collecting failure screenshots. These sorts of cleanup methods should exclusively use API calls.
  - Take screenshots of errors collected by `DeferredErrorCollector` before taking any actions that modify the page state.
- **Formatting**: Follow IntelliJ IDEA project settings in `.idea/codeStyles/Project.xml`.

## Key Build Properties (`gradle.properties`)

- `sourceCompatibility`/`targetCompatibility`: Java 25
- `buildFromSource`: true (build modules from source vs. pulling artifacts)
- `useLocalBuild`: use locally built artifacts
- `moduleSet`: select a predefined set of modules (e.g., `community`, `all`, `distributions`)
- `excludedModules`: comma-separated list of modules to exclude

## Search Tips

When searching for Java method usages, always include `*.jsp` and `*.jspf` files in addition to `*.java`. JSP files contain inline Java code and are significant callers of API methods (especially anything in `JspBase`).

## Git Branch Naming

- `develop` — primary development branch (protected; no direct commits).
- `fb_<label>_<id>` — feature/bug-fix branch off `develop`. `label` is a short snake_case description (use underscores to separate words, not dashes); `id` is the issue or GitHub issue ID. Omit `_<id>` only when no ID exists (e.g., test fixes); coordinate the label to avoid collisions.
- `XX.Y_fb_<label>_<id>` — feature/bug-fix branch targeting a specific release.
- `releaseXX.Y-SNAPSHOT` — beta release branch (protected); base release-targeted feature branches from it.
- `releaseXX.Y` — final release branch (protected); receives merges from the SNAPSHOT branch only. Patch releases are tagged `XX.Y.Z`.

Use an identical branch name across every repo involved in a story. Branches matching these patterns are built by TeamCity — pick a non-matching name to opt out.

Before creating a branch, always propose the name and confirm it with the user. Do not run `git checkout -b` (or equivalent) until the user approves.

## Commit and PR Body Formatting

Applies to every commit body and every PR body, without exception. Do not hard-wrap. Write each paragraph and each bullet as a single physical line, no matter how long. Separate paragraphs with one blank line. This applies to text passed via `-m`, `--body`, here-docs, `gh pr edit`, GitHub MCP tools — every channel that produces commit or PR body text.

**Why:** GitHub renders commit and PR bodies as GFM with hard-line-break enabled. Every mid-paragraph `\n` becomes a visible `<br>` in the rendered output, producing ragged, broken-looking text. Soft-wrap is the renderer's job, not yours.

**Self-check before invoking `git commit`, `gh pr create`, or `gh pr edit`:** look at the body text you are about to pass. If any paragraph spans more than one line in your tool call, that is a bug — collapse it to a single line first. Long lines are correct. Wrapped lines are wrong.

## Commit Messages

Subject: short imperative (≈70 chars). Body: follow the formatting rule above — one physical line per paragraph, blank lines between paragraphs.

## Pull Request Format

If the repo has a `pull_request_template.md` (typically under `.github/`), follow it. Otherwise, include sections for: **Rationale** (why the change is needed), **Related Pull Requests**, and **Changes** (notable items). Keep descriptions brief. Follow the formatting rule above — one physical line per paragraph and per bullet.

Before opening a PR, always draft the title and description and confirm them with the user. Do not run `gh pr create` until the user approves.

## Enlistment Structure
```
./                                ← root repo (https://github.com/LabKey/server)
├── distributions/                ← optional; distribution configurations
├── remoteapi/                    ← optional; Java API repos
│   ├── labkey-api-java/          ← LabKey Java Client API
│   └── labkey-api-jdbc/          ← LabKey JDBC Driver
├── server/
│   ├── modules/
│   │   ├── platform/             ← core platform modules
│   │   └── */                    ← additional module repos cloned here
│   └── testAutomation/           ← core Selenium tests
└── clientAPIs/                   ← optional; front-end packages cloned here or via env vars
    ├── labkey-api-js/
    ├── labkey-ui-components/     ← $LABKEY_UI_COMPONENTS_HOME
    └── labkey-ui-premium/        ← $LABKEY_UI_PREMIUM_HOME
```
All repositories are under the `LabKey` GitHub organization (https://github.com/LabKey), with the root repo at `LabKey/server`.
