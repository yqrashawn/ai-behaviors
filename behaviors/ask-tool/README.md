# #ask-tool — Ask Tool

## What
Forces the LLM to use the AskUserQuestion tool for all user-directed questions instead of writing them as plain text output.

## Why
Plain-text questions create a worse UX — the user has to wait for the full response, then type a reply. The AskUserQuestion tool (when available) provides a structured prompt that surfaces immediately, blocks until answered, and returns the answer in-context.

## When to use
- Stack with any interactive mode: `#=frame #ask-tool`, `#=spec #ask-tool`, `#=probe #ask-tool`
- Especially useful with modes that alternate between Claude asking and user answering
- Not needed if the mode doesn't ask questions (e.g., `#=test`)

## Notes
- The tool name is generic ("AskUserQuestion") — the LLM resolves to whichever tool is available in the environment (e.g., `mcp__cchp__ask_user_question`, `mcp__emacs__ask_user_question`, or Claude Code's built-in `AskUserQuestion`)
- The hook also injects a tool-routing instruction automatically when any mode is active (option 4), so this behavior is for explicit emphasis or when using behaviors without a mode
