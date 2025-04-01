# Collet Project Guide

## Build/Test/Lint Commands
```bash
# Run all tests
lein test

# Run specific namespace tests
lein test collet.core-test

# Run specific test
lein test :only collet.core-test/pipeline-test

# REPL-based testing
(require 'dev)
(dev/test)

# Development setup/cleanup
bb dev-setup
bb dev-cleanup

# Release commands
bb release
```

## Code Style Guidelines
- **Namespaces**: Hierarchical organization with related functionality grouped
- **Imports**: No need in alphabetically ordering, keep with consistent aliases (`io` for `clojure.java.io`, `string` for `clojure.string`)
- **Formatting**: 2-space indentation, empty lines between major sections. Don't change formatting of existing code, apply to new code only.
- **Naming**: kebab-case for functions/vars, PascalCase for records/types
- **Documentation**: Docstrings for public functions with malli schemas for parameters/return values as metadata map, under :malli/schema key
- **Error Handling**: Use `ex-info` with detailed context maps, explicit error logging with `ml/log`
- **Types**: Extensive use of malli schemas for validation, protocol implementations for type behavior
- **Functional Style**: Pure functions, immutable data, threading macros (`->`, `->>`) for transformations
- **Concurrency**: Virtual threads, explicit coordination via `Future`, `Semaphore`, atomic references (`atom`)