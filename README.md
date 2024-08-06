# COLLET

With collet project you can:
- Create tasks to execute actions
- Combine tasks into a pipelines
- Execute pipelines to move data around

### Development

- start REPL as usual
- spin up containers for monitoring pipelines execution with command
  `docker-compose rm -f && docker-compose up` (includes elasticsearch, kibana, jaeger, prometheus, grafana)
- navigate to the `dev/src/dev.clj` namespace
- execute `(start-publishers)` to enable logging and tracing
- you can run all tests in the project with `(test)` command

Graphana is available at `http://localhost:3000` with default credentials `admin/grafana`
Jaeger is available at `http://localhost:16686`
Kibana is available at `http://localhost:9000`


### TODO
- sink action to send data further 
  ~~- file~~
  - S3
  - chronicle queue

- clean up tasks unused data (not required for other tasks)
- direct dependencies between tasks
- add action parameters to logs
  - obfuscate PII data

- documentation
- add odata action

- tmd for reading datasets
- parallel tasks execution
- evaluate the use of nippy/arrow data formats for intermittent data storage
- evaluate the use of DuckDB for querying databases and files
