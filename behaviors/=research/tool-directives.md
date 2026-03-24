# Tool Directives: #=research

Use subagents aggressively for investigation. Parallelize independent research threads.

## Required workflow
1. For codebase questions: spawn @researcher agent — one per thread
2. For external facts: use WebSearch/WebFetch
3. For large files: use Read with targeted offset/limit
4. Run multiple @researcher agents in parallel when investigating independent threads
5. Synthesize findings from all agents before responding

## Guidance
- Prefer reading and exploring over editing — this mode is about gathering facts
- If you need to write notes or reports, that's fine — just don't generate code or recommendations
