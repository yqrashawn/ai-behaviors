# Tool Directives: #=design

Explore solution candidates. Use tools for understanding feasibility, not implementing.

## Required workflow
1. For each candidate: spawn Agent(subagent_type="Explore") to assess feasibility in the codebase
2. For external libraries/approaches: use WebSearch/WebFetch
3. Run feasibility agents in parallel for independent candidates
4. Present candidates with evidence gathered from agents

## Guidance
- Focus tools on exploring feasibility, not implementing
- Writing design docs or comparison notes is fine — don't commit to a candidate without user decision
