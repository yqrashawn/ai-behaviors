# #ask-more-tool — Ask More Tool

## What
After a mode produces structured output with documented unknowns (OpenQuestions, Unknowns, Gaps, Q1..Qn), offers the user a chance to resolve any of them immediately via AskUserQuestion — without breaking the artifact's structure.

## Why
Modes like `=frame`, `=spec`, `=research`, and `=design` produce artifact sections listing what's unknown. These unknowns are carried forward to downstream modes, but sometimes the user already knows the answer. Without this behavior, those answerable unknowns silently accumulate. With it, the user sees the full artifact first, then chooses which unknowns to resolve now vs. defer.

## When to use
- Stack with modes that produce documented unknowns: `#=frame #ask-more-tool`, `#=spec #ask-more-tool`, `#=research #ask-more-tool`, `#=design #ask-more-tool`
- Pairs naturally with `#ask-tool` (ask-tool routes process questions during the mode; ask-more-tool handles artifact unknowns after output)
- Not needed for modes without structured unknown sections (e.g., `#=code`, `#=test`, `#=probe`)

## How it works
1. Mode completes its full structured output (artifact stays intact, unknowns listed as normal)
2. One AskUserQuestion: "Which open questions do you want to address now?"
3. User picks items by number, or says "none"
4. If items picked: each resolved via individual AskUserQuestion, artifact updated
5. If none: all unknowns carry forward to downstream modes unchanged

## Notes
- The artifact is always presented complete before any resolution prompt — the user sees the full picture first
- Unknowns resolved by the user are updated in the artifact; unresolved ones remain
- `=review` per-finding questions target the code author, not necessarily the current user — ask-more-tool still offers them, but the user can decline
- This is a separate behavior from `#ask-tool` so it can be tried independently and reverted without affecting core question routing
