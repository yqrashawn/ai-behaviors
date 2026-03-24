# Tool Directives: #=test

Break things. Use tools to actively probe for failures.

## Required workflow
1. Read the code under test
2. Use Bash to run existing tests and observe current state
3. Spawn @adversary agent to find untested paths, edge cases, missing coverage
4. Use Bash to execute targeted test scenarios (read-only probing, not writing test files)
5. Report bugs, exploit scenarios, and failing test cases

## Guidance
- Focus tools on probing and breaking, not fixing
- Writing test reports is fine — just report bugs, don't fix them
