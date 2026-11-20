# Pipeline completion and production readiness checklist

Use this after building the pipeline. Through MCP, call
`collet_read_documentation` with `{"id":"production-checklist","full":true}`.
Record the exact draft revision or saved version beside the evidence. Mark each
item passed, failed, pending, or not applicable. An offline task can be complete
while production verification remains pending.

## Definition

- [ ] The task graph has clear input and output handoffs. Tasks that can run
  independently use separate branches. Joins use stable keys and check missing
  or inconsistent records where the workflow requires it.
- [ ] Catalogue actions and selectors handle routine I/O and data shaping.
  Custom functions hold domain logic. Core is bundled and optional integrations
  use pinned dependency recipes.
- [ ] Passive validation passes for the exact source. Required runtime
  configuration has a `:config-spec` and documented example values. Credentials
  stay in runtime configuration and samples, logs, and outputs omit secrets.

## Behavior with fixtures

- [ ] Saved cases cover the useful path and the failures that matter for this
  pipeline. Check empty inputs, pagination or partial batches, malformed
  responses, failed writes, and duplicate or mismatched keys where applicable.
- [ ] Exercise actions and tasks with the representation they receive in a
  run, including dataset batches. Inspect actual output values and verify
  expected counts, identities, writes, and error behavior. Record execution IDs,
  fixture revisions, and which calls were mocked.
- [ ] Review timeouts, retry boundaries, idempotency, and destination ownership.
  Confirm a retry cannot unintentionally duplicate or overwrite important data.

## Deployment and handoff

- [ ] Save the tested source as an immutable version. In a clean process matching
  the deployment, verify that the pinned action packages resolve and execution
  succeeds with representative configuration and isolated fixture endpoints.
- [ ] With authorization and real configuration, verify the exact saved version
  against the intended services and inspect the resulting run, outputs, and
  errors. Confirm the operator knows how to identify a failed run and recover.
  If credentials, authorization, or an environment are unavailable, leave this
  item pending and state the prerequisite.
- [ ] Report the saved version, evidence for passive, fixture, clean-process,
  and live checks as applicable, and any unresolved issue that affects readiness.
