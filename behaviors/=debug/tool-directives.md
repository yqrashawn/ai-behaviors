# Tool Directives: #=debug

Find the root cause. Use tools to trace execution, not to guess.

## Required workflow
1. Spawn @debugger agent to trace the execution path from symptom to source
2. Execute the code with debug flags, logging, or tracing enabled (Bash, REPL, or any available execution tool)
3. Examine logs, stack traces, error messages (Read/Grep or REPL inspection)
4. For complex call chains: spawn multiple @researcher agents in parallel for different paths
5. All tools allowed — use whatever reveals the root cause
