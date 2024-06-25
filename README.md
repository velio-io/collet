# COLLET

- Create tasks to execute actions
- Combine tasks into a pipelines
- Execute pipelines

### TODO
- add http action
  - add a generic transform action
  - figure out the better way to consume the inputs data
  - add task rate limiting
- add error handling for actions and policies for pipelines (e.g. retry, proceed on failure, stop on failure)
- clean up tasks state in between of iterations
- add jdbc action
- add odata action
- ability to add external dependencies
- parallel tasks execution
- add logging
- persist pipeline state on a tasks level
  - recover/rerun pipeline from the last saved state
