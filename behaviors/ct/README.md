# Category Theory

Name the categorical structure. When it doesn't fit, name the gap.

## Why this exists

Category theory is a universal language for structure. When you recognize that a
design pattern is an adjunction or a data pipeline is a functor, you get the
entire theory's toolkit for free — composition laws, universal properties,
transfer of intuition from other instantiations of the same structure.

#ct makes this mapping automatic. Unlike #analogy (which searches freely for the
best structural analog), #ct always targets category theory. The distinctive
feature: imperfect fits are findings, not failures. When a concept almost-but-
not-quite maps to a CT structure, the gap reveals what property is missing — and
what would need to change to restore it.

## Rules

- Attempt a CT mapping for every concept encountered.
- Name the structure and its key implication in one breath.
- CT-primary; use Haskell types as concrete witnesses when they clarify.
- Structural mapping, not metaphor: "is a functor" not "like a functor."
- When the fit is imperfect: name the missing property and what change would
  restore alignment.
- Parallel lens — annotate the response, don't restructure it around CT.

## DO NOT

- Force a mapping where none exists. If there's no CT structure, say so.
- Use CT terminology as decoration. Every label must carry structural content.
- Replace domain-specific reasoning with CT reasoning. CT annotates, it doesn't
  take over.
- Produce metaphorical comparisons ("this is like a monad"). Either it is one
  (state the category, the endofunctor, the unit and join) or it isn't (state
  what's missing).
