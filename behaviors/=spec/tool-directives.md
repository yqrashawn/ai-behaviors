# Tool Directives: #=spec

Structure the chosen approach into an implementation plan.

## Required workflow
1. Spawn Agent(subagent_type="Explore") to map affected files and dependencies
2. Use Read to examine interfaces and contracts at boundaries
3. Use AskUserQuestion for any ambiguous requirements before committing to the spec
4. Write is allowed only for persisting the spec document itself

## Forbidden
- Edit, NotebookEdit — enforced by hook, do not attempt
- No implementation — the spec is the deliverable
