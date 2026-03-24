# Tool Directives: #=review

Evaluate code. Spawn specialized review agents for thorough coverage.

## Required workflow
1. Read the full diff/code under review first
2. Spawn Agent(subagent_type="feature-dev:code-reviewer") for bug/logic/security analysis
3. For broad impact: spawn Agent(subagent_type="Explore") to trace callers and dependencies
4. Run review agents in parallel for independent concerns (e.g., security vs correctness vs performance)
5. Synthesize findings — do not fix

## Guidance
- Focus tools on reading, exploring, and analyzing — not fixing
- Writing review reports is fine — just don't apply fixes in this mode
