# COLLET

- Create tasks to execute actions
- Combine tasks into a pipelines
- Execute pipelines

### TODO
- add jdbc action
  - ability to add external dependencies (like jdbc driver)
  - passing options to plan! call
  - data types mapping (get columns metadata from result set to transform json values)
  - more extensive testing with complex queries and pipelines
  - tmd for reading data?

- add logging, metrics, tracing
- persist pipeline state on a tasks level
  - clean up memory
  - recover/rerun pipeline from the last saved state

- documentation
- add odata action
- sink action to send data further (file, S3, queue)
  - smart batch size detection
- parallel tasks execution
