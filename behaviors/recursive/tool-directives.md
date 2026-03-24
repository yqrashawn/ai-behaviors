# Tool Directives: #recursive

Apply process to its own output. Iterate until stable.

## Required workflow
1. After producing output: spawn @deep-reviewer agent to review it
2. Feed review findings back into the next iteration
3. Repeat until the review agent reports no significant issues (fixpoint)
4. Maximum 3 iterations — report remaining issues if not converged
