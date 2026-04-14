Review local git changes (staged and unstaged) across all related repositories, using the same systematic process as /review-pr.

Steps:
1. Find the current repo root with `git rev-parse --show-toplevel`, then get its parent directory. List all subdirectories of that parent and probe each with `git -C <dir> rev-parse --git-dir 2>/dev/null` to identify sibling git repositories.
2. For each git repository found (including the current one), run the appropriate command:
   - With no arguments: `git -C <repo-path> diff HEAD -- . ':(exclude).idea' ':(exclude)server/configs'`
   - With $ARGUMENTS as a path filter: `git -C <repo-path> diff HEAD -- $ARGUMENTS ':(exclude).idea' ':(exclude)server/configs'`

   Skip repos with no changes.
3. If a repo has no changes from HEAD, also check `git -C <repo-path> diff --cached -- . ':(exclude).idea' ':(exclude)server/configs'` (handles repos where HEAD hasn't been set up yet).
4. For each file changed, if you need more context than the diff provides, read the relevant file(s).

Then read [review-phases.md](../review-phases.md) and perform a thorough review following the phases and output format defined there. In Phase 1, note which repositories are involved in the changes.