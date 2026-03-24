# Tool Directives: #=spec

Structure the chosen approach into an implementation plan.

## Required workflow
1. Spawn Agent(subagent_type="Explore") to map affected files and dependencies
2. Use Read to examine interfaces and contracts at boundaries
3. Use AskUserQuestion for any ambiguous requirements before committing to the spec
4. Use Write for persisting the spec document

## Guidance
- Use Write for persisting the spec document — the spec is the deliverable
- Don't implement the spec in this mode — just structure the plan
