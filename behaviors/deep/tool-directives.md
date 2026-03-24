# Tool Directives: #deep

Go beneath the surface. Use tools to dig, not guess.

## Required workflow
1. Spawn Agent(subagent_type="Explore") to trace call chains, inheritance, data flow
2. For each layer discovered: spawn another Explore agent to go deeper
3. Do not stop at the first answer — use tools to verify assumptions at each layer
