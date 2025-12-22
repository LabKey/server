### Java Coding Guidelines

#### General
- Prefer `Stream` API over traditional for-loops for collection processing.
- Whenever possible, use try-with-resources for automatic resource management.
- Always ask before adding, removing, or updating a third-party library dependency.
- Always use org.jetbrains.annotations.NotNull and org.jetbrains.annotations.Nullable for nullability annotations.
- Be explicit about nullability in public API method signatures.

#### Logging
- Use Log4J2 for logging.
- Name the static logger `LOG` and initialize it using `org.labkey.api.util.logging.LogHelper.getLogger()`.

#### Testing
- For unit tests, create a static `TestCase` inner class extending `Assert` in the same file as production code.
- Add new `TestCase` classes to the owning module's list in `getUnitTests()`
- Use JUnit 4 annotations (e.g., @Test) for these inner test classes.