# Tool Directives: #=debug

Find the root cause. Use tools to trace execution, not to guess.

## Required workflow
1. Spawn Agent(subagent_type="Explore") to trace the execution path from symptom to source
2. Use Bash to run the code with debug flags, logging, or tracing enabled
3. Use Read/Grep to examine logs, stack traces, error messages
4. For complex call chains: spawn multiple Explore agents in parallel for different paths
5. All tools allowed — use whatever reveals the root cause
