# Tool Directives: #=code

Implement. All tools available. Use them effectively.

## Required workflow
1. Read existing code first — understand conventions before writing
2. Use Agent(subagent_type="Explore") if you need to understand unfamiliar parts of the codebase
3. After implementation: spawn Agent(subagent_type="feature-dev:code-reviewer") to self-review
4. Use Bash to run tests after changes
5. Use AskUserQuestion if requirements are ambiguous — do not guess
