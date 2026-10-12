# Logic Engine cleanup plan

This plan separates the preserved typed Netty RPC transport from the new declarative logic engine. It precedes implementation changes.

## Behavior locks

- Preserve the 16-byte protocol header, safe JSON serialization, request correlation, loopback invocation, and explicit ZooKeeper adapter boundary.
- Keep default tests independent of ZooKeeper and external services.
- Reject unknown serialization modes and unregistered methods with structured failures.
- Keep `legacy-typed-netty` and `tiny-rpc-netty` explicitly incompatible at the wire level.

The existing protocol codec, serializer, request-holder, and loopback tests are the initial regression lock. The stale cross-repository compatibility runner is historical evidence only; the Hub exact-commit cold-source runner will replace it.

## Smell-focused passes

1. Record both Maven dependency trees and select one Java 8 compatible Netty/JSON policy from already used dependencies.
2. Add a Spring-free transport SPI and deterministic local service catalog while retaining the 16-byte transport adapter.
3. Add the real TinyRPC adapter using the Communication repository's published API.
4. Add declarative graph validation before execution: unique IDs, references, acyclicity, service and operation allowlists, type compatibility, and bounded deadlines.
5. Add deterministic topological execution and a minimal allowlisted operator set for field selection, comparison, boolean composition, and bounded arithmetic.
6. Replace broad reflection and implicit failure with node-scoped structured results. Do not add `eval`, shell invocation, arbitrary class loading, or implicit retries.

## Dependency rule

The engine reuses the existing Java 8, Netty, and JSON families. ZooKeeper and Spring remain optional adapters. No new third-party library is introduced by default.

