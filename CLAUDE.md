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

# Build a distribution
./gradlew -PmoduleSet=distributions :distributions:base:dist
```

## Running Tests

**Unit tests** are static `TestCase` inner classes within production source files. They are registered via the module's `getUnitTests()` method and run within the server JVM.

**Integration tests** require a running server and database. They are registered via `getIntegrationTests()`.

**Selenium UI tests** (in `server/testAutomation/`):
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
- **Unit tests**: Create a static `TestCase` inner class extending `Assert` in the same file as production code. Use JUnit 4 annotations (`@Test`). Register new test classes in the owning module's `getUnitTests()`.
- **Selenium tests**: Subclass `BaseWebDriverTest`. Use a `@BeforeClass` for setup and override `doCleanup()` for cleanup. See `SecurityTest` as an example.
- **Formatting**: Follow IntelliJ IDEA project settings in `.idea/codeStyles/Project.xml`.

## Key Build Properties (`gradle.properties`)

- `sourceCompatibility`/`targetCompatibility`: Java 25
- `buildFromSource`: true (build modules from source vs. pulling artifacts)
- `useLocalBuild`: use locally built artifacts
- `moduleSet`: select a predefined set of modules (e.g., `community`, `all`, `distributions`)
- `excludedModules`: comma-separated list of modules to exclude

## Search Tips

When searching for Java method usages, always include `*.jsp` and `*.jspf` files in addition to `*.java`. JSP files contain inline Java code and are significant callers of API methods (especially anything in `JspBase`).

## Pull Request Format

PRs should include sections for: **Rationale** (why the change is needed), **Related Pull Requests**, and **Changes** (notable items).

## TeamCity

The TeamCity MCP server at `https://teamcity.labkey.org/app/mcp` can be used to query build results. Add it with:
```
claude mcp add teamcity https://teamcity.labkey.org/app/mcp --transport http --scope user --header "Authorization: Bearer <token>"
```

### Branch and project naming

Feature branch names are stripped of their prefix when recorded in TeamCity:

| Git branch | TeamCity branch | TeamCity project |
|---|---|---|
| `26.3_fb_Item1045` | `Item1045` | `affectedProject:(id:LabKey_263Release)` |
| `fb_myFeature` | `myFeature` | `affectedProject:(id:LabKey_Trunk)` |

Use `affectedProject` (not `project`) to include all subprojects (Community, External, Internal, Premium, EHR, etc.).

Project ID pattern for release branches: `LabKey_<major><minor>Release` (e.g., `LabKey_263Release` for 26.3).

### Suite sharding

Letter suffixes like `[A]`, `[B]`, `[C]` on suite names indicate shards of the same larger suite split for parallelism — not distinct configurations. A failure in `Panorama [B] postgres` is a failure in the "Panorama postgres" suite.

## Tool Usage Rules

When navigating or searching this codebase, prefer IntelliJ MCP tools over shell commands:

- **Finding a class or symbol** → use `find_usages` or `search_in_project` MCP tool, NOT `grep` or `find`
- **Checking errors/warnings** → use `get_file_problems` MCP tool, NOT manual inspection
- **Project structure** → use `get_project_modules` and `list_dependencies` MCP tools
- **Running Tomcat** → use `run_configuration` MCP tool, NOT shell

Only fall back to shell commands if the MCP tool fails or is unavailable.