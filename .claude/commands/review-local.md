Review local git changes (staged and unstaged) across all related repositories, using the same systematic process as /review-pr.

Steps:
1. Find the repo root with `git rev-parse --show-toplevel` (call it REPO_ROOT). The repos to check are at these known locations — no probing needed:
   - REPO_ROOT itself
   - Every direct subdirectory of REPO_ROOT/server/modules/
   - REPO_ROOT/server/testAutomation (if it exists)
   - Every direct subdirectory of REPO_ROOT/clientAPIs/
2. For each repo, run the appropriate command:
   - With no arguments: `git -C <repo-path> diff HEAD -- . ':(exclude).idea' ':(exclude)server/configs'`
   - With $ARGUMENTS as a path filter: `git -C <repo-path> diff HEAD -- $ARGUMENTS ':(exclude).idea' ':(exclude)server/configs'`

   Skip repos with no changes.
3. If `git diff HEAD` fails for a repo (e.g., no commits exist yet), fall back to `git -C <repo-path> diff --cached -- . ':(exclude).idea' ':(exclude)server/configs'`.
4. For each file changed, if you need more context than the diff provides, read the relevant file(s).

Then read [review-phases.md](../review-phases.md) and perform a thorough review following the phases and output format defined there. In Phase 1, provide a list of the locally edited files that were analyzed, including their parent repo.