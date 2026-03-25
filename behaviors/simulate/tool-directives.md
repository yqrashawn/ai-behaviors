# Tool Directives: #simulate

Trace execution with real tools, not mental models alone.

## Required workflow
1. Add temporary logging/tracing and run the code (Bash, REPL, or any available execution tool)
2. Examine actual output, logs, state changes
3. Spawn @debugger agent to trace the full execution path in the source
4. Compare mental model against actual behavior — flag divergences
