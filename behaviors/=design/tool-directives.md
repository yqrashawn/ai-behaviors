# Tool Directives: #=design

Explore solution candidates. Use tools for understanding feasibility, not implementing.

## Required workflow
1. For each candidate: spawn Agent(subagent_type="Explore") to assess feasibility in the codebase
2. For external libraries/approaches: use WebSearch/WebFetch
3. Run feasibility agents in parallel for independent candidates
4. Present candidates with evidence gathered from agents

## Forbidden
- Edit, Write, NotebookEdit — enforced by hook, do not attempt
- No commitment to a candidate — present options for user decision
