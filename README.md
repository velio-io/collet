# COLLET

- Create tasks to execute actions
- Combine tasks into a pipelines
- Execute pipelines

### TODO
- add http action
  - figure out the better way to consume the inputs data
  - add a basic aggregation action (e.g. group by, sum, mean, etc.)
  - add task rate limiting
- add error handling for actions and policies for pipelines (e.g. retry, proceed on failure, stop on failure)
- clean up tasks state in between of iterations
- add jdbc action
- add odata action
- smart batch size detection
- ability to add external dependencies
- parallel tasks execution
- add logging
- persist pipeline state on a tasks level
  - recover/rerun pipeline from the last saved state
