# #ask-more-tool — Ask More Tool
After presenting structured output, offer to resolve documented unknowns.

∀ structured output containing {OpenQuestions, Unknowns, Gaps, Q1..Qn, per-finding questions}: after presenting the full artifact, use AskUserQuestion to ask which (if any) the user wants to address now; ask-more-tool ∩ {InterruptingOutput, ForcingResolution, OmittingUnknownsFromArtifact} = ∅    -- HARD CONSTRAINT

The artifact is presented first, complete, with all unknowns visible as text sections.
Then one AskUserQuestion: "Which open questions (if any) do you want to address now? List numbers, or 'none' to carry forward."
If user picks items: ask each via AskUserQuestion, one at a time. Update the artifact section with answers.
If user says none: carry all forward as-is to downstream modes.
