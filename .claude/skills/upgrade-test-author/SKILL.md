---
name: submodule-feature-branch
description: Create a LabKey upgrade test that runs setup on an old release and verification on a new release, using the correct submodule feature branch workflow for TeamCity CI. Use when the user asks to add an upgrade test, create a feature branch for a schema migration, or stage changes across release branches.
---

# Upgrade Test Feature Branch Workflow

Upgrade tests have two phases that run on different server versions:
- **Setup** (`doSetup()`): runs against the OLD version to create test data
- **Verification** (`@Test` methods): runs against the NEW version after the upgrade

The code lives in a single class committed to the OLD release's submodule feature branch and cherry-picked forward to the NEW release. Write the setup code directly in the old branch — do not write it in the new branch and port it back.

## Key conventions

- **Feature branch name**: `{release-version}_fb_{ItemNumber}` (e.g. `25.11_fb_Item1045`)
  — the prefix determines which TeamCity project picks it up.
- **Always branch in the submodule repo** at `server/modules/{module}`, never in the root enlistment.
- Both release repos typically share the same GitHub remote, so a pushed branch can be fetched
  and cherry-picked without adding a new remote.

## Upgrade test structure

Extend `BaseUpgradeTest` (not the module's own base test class, since Java has single inheritance).
Use a helper class (e.g. `TargetedMSHelper`) to access setup utilities without subclassing:

```java
// For 26.6+: class-level annotation covers all @Test methods
@Category({})
@EarliestVersion("26.6")
public class MyModuleUpgradeTest extends BaseUpgradeTest { ... }

// For 26.3 or earlier: annotate EVERY @Test method individually
// (class-level is ignored in older BaseUpgradeTest)
@Category({})
public class MyModuleUpgradeTest extends BaseUpgradeTest
{
    @Override
    protected void doSetup() throws Exception
    {
        MyModuleHelper helper = new MyModuleHelper(this);
        helper.setupFolder(getProjectName(), FolderType.Experiment);
        helper.importData(DATA_FILE);
    }

    @Test
    @EarliestVersion("26.3")   // skip when upgrading from < 26.3
    public void testNewMigration() throws Exception
    {
        // query new columns / UI state that the 26.3 upgrade script created
    }

    @Test
    @EarliestVersion("26.3")   // must repeat on every method for pre-26.6 releases
    @LatestVersion("26.3")     // additionally cap if only relevant for this exact version
    public void testLegacyBehavior() throws Exception
    {
        // verify behavior that only applies to data created on exactly 26.3
    }
}
```

`doSetup()` must work against the OLD server version — don't reference columns, APIs, or UI
elements that only exist after the upgrade.

`@Test` methods run against the NEW server and can reference anything the migration added.

### Version annotation semantics

Both `@EarliestVersion` and `@LatestVersion` (nested annotations inside `BaseUpgradeTest`) gate
on the **old/setup version** — the version the server was running when `doSetup()` ran, not the
new version being upgraded to. The version string is a LabKey release version like `"26.3"` or
`"25.11"`.

| Annotation | Meaning | When to use |
|---|---|---|
| `@EarliestVersion("X")` | Skip if old version < X | Test requires data/config only added to `doSetup()` in version X |
| `@LatestVersion("X")` | Skip if old version > X | Test only applies to setups done on X or earlier (legacy check) |
| Both together | Skip if old version outside [earliest, latest] | Narrow version window, e.g. a migration that was back-ported |

Method-level annotations are ignored during the setup phase — they only filter during the verify phase.

### Class-level vs method-level: version matters

**Class-level** `@EarliestVersion` / `@LatestVersion` is only supported in **26.6 and later**.
In older releases the `@BeforeClass setupProject()` does not check class-level annotations, so
placing them on the class has no effect and tests will run (or fail) unconditionally.

**Method-level** annotations work in all releases via the `UpgradeVersionCheck` `@Rule`.

Rule of thumb by target release:

| Writing a test for… | Use |
|---|---|
| 26.6+ | `@EarliestVersion` on the **class** (covers all methods at once) |
| 26.3 or earlier | `@EarliestVersion` on **every `@Test` method** individually |

For a release older than 26.6, annotate every `@Test` method — omitting even one means that
method runs regardless of the old version.

## Steps

### 1. Create the feature branch in the OLD release's submodule

```bash
cd /path/to/release{OLD_VERSION}/server/modules/{module}
git checkout -b {OLD_VERSION}_fb_{ItemNumber}
```

### 2. Write the upgrade test in the old release

Create the test class under `test/src/.../upgrade/`. Implement `doSetup()` using whatever
APIs and folder structures exist in the old version. Leave `@Test` methods as stubs or omit
them — they'll be filled in after the cherry-pick.

Also apply any shared infrastructure changes needed (e.g. extracting a helper class) so the
cherry-pick applies cleanly.

### 3. Commit and push from the old release

```bash
cd /path/to/release{OLD_VERSION}/server/modules/{module}
git add test/src/.../upgrade/MyModuleUpgradeTest.java \
        test/src/.../util/MyModuleHelper.java \
        ...   # be explicit, never -A
git commit -m "Add upgrade test for {migration description}"
git push -u origin {OLD_VERSION}_fb_{ItemNumber}
```

### 4. Cherry-pick into the current (new) branch

```bash
cd /path/to/release{NEW_VERSION}/server/modules/{module}
git fetch origin {OLD_VERSION}_fb_{ItemNumber}
git cherry-pick {commit-sha}
```

Confirm with `git log --oneline -3`.

### 5. Add verification to the cherry-picked test

Now, still in the new release repo, add (or complete) the `@Test` verification methods that
check the post-upgrade state. These can reference new columns, new API fields, or UI elements
that only exist after the migration. Commit this as a follow-up commit on the new branch.

## Pitfalls

- Running `git` from the root enlistment path affects the root repo, not the submodule.
  Always `cd` into `server/modules/{module}` first.
- `doSetup()` must compile and run against the old version. If it references a new API, the
  setup phase will fail on the old server.
- Don't extend the module's own base test class (e.g. `TargetedMSTest`) in the upgrade test —
  use a helper class instead so you can still extend `BaseUpgradeTest`.
- **Class-level `@EarliestVersion` only works in 26.6+.** For 26.3 and earlier, the
  `@BeforeClass` in `BaseUpgradeTest` does not check class-level annotations — you must annotate
  every `@Test` method individually or tests will run unconditionally.
- The version in `@EarliestVersion` / `@LatestVersion` refers to the **old** (setup) version,
  not the new one. `@EarliestVersion("26.3")` means "only run when upgrading from 26.3 or later",
  i.e. when `doSetup()` ran on a 26.3 server.
- Both annotations are **inner annotations** declared inside `BaseUpgradeTest`, so import them as
  `BaseUpgradeTest.EarliestVersion` or use a static import — they are not top-level JUnit annotations.