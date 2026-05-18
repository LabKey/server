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
    @EarliestVersion("26.3")   // only run when upgrading from 26.3+
    public void testMigration() throws Exception
    {
        // query the new columns / UI state that the upgrade script created
    }
}
```

`doSetup()` must work against the OLD server version — don't reference columns, APIs, or UI
elements that only exist after the upgrade.

`@Test` methods run against the NEW server and can reference anything the migration added.
Use `@EarliestVersion` to skip verification when the setup was done on a version that predates
the migration.

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
- Check that `@EarliestVersion` on `@Test` methods matches the version where `doSetup()` was
  first introduced, so the verification only runs when the expected data was actually created.