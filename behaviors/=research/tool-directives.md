# Tool Directives: #=research

Use subagents aggressively for investigation. Parallelize independent research threads.

## Required workflow
1. For codebase questions: spawn Agent(subagent_type="Explore") — one per thread
2. For external facts: use WebSearch/WebFetch
3. For large files: use Read with targeted offset/limit
4. Run multiple Explore agents in parallel when investigating independent threads
5. Synthesize findings from all agents before responding

## Forbidden
- Edit, Write, NotebookEdit — enforced by hook, do not attempt
- No code generation, no recommendations, no decisions
