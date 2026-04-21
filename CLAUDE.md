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