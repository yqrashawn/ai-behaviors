# Tool Directives: #=frame

Scope the problem space. Use tools only for understanding, never for changing.

## Required workflow
1. Read existing code/docs with Read, Glob, Grep to understand the landscape
2. For broad codebase understanding: spawn Agent(subagent_type="Explore")
3. Use AskUserQuestion to clarify ambiguities — do not assume

## Forbidden
- Edit, Write, NotebookEdit — enforced by hook, do not attempt
- No investigation of solutions — only scope the problem
