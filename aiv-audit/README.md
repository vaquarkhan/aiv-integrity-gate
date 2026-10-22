# Module: `aiv-audit`

`aiv-audit` provides a dependency-free, append-only, tamper-evident SHA-256 hash chain for AIV verdict and governance records.

## Architecture role

```text
payload -> append(prev hash + base64 payload) -> new head
log + optional external head -> verify -> intact / broken / head mismatch
```

The module does not decide payload schema; `aiv-report` and `aiv-cli` create typed records.

## Configuration and storage

There is no separate runtime configuration object in this module. Callers choose the log path and payload, while the CLI supplies `--audit-log`, `AIV_AUDIT_LOG`, or the protected `audit_log` config key. Store the external head anchor outside the log.

## Log format

One UTF-8 line per record:

```text
<previous-hash> TAB <record-hash> TAB <base64-payload>
```

The first record uses 64 zero hex characters (`GENESIS`). The record hash is:

```text
SHA256(previousHash + base64Payload)
```

Base64 keeps arbitrary JSON/text payloads on one line.

## API

```java
String head = HashChainAudit.append(logPath, payload);
VerifyResult result = HashChainAudit.verify(logPath);
VerifyResult anchored = HashChainAudit.verify(logPath, expectedHead);
List<String> payloads = HashChainAudit.payloads(logPath);
String current = HashChainAudit.headHash(logPath);
```

`sha256Hex` is public so evidence modules use one hashing implementation.

## Concurrent writers

`append` is synchronized within the JVM and takes an OS file lock on `<log>.lock` across processes. The lock covers reading the current head and appending the new record, preventing sibling records from concurrent CI workers. Tests launch multiple threads and eight separate JVMs and verify one intact chain.

## Verification outcomes

| Outcome | Meaning |
|---------|---------|
| intact | every previous/hash/payload link recomputes |
| broken index | malformed, edited, removed, inserted, or reordered record |
| head mismatch | internal chain recomputes but does not match the external anchor |

## External anchor

A hash chain detects ordinary edits, but a writer with full log access can rewrite the entire chain. Store the returned head outside the log: CI output, signed release metadata, transparency service, or another protected system. `verify(log, expectedHead)` then detects complete-chain rewrites.

## CLI usage

```bash
java -jar aiv-cli.jar --audit-log .aiv/audit.log --diff origin/main
java -jar aiv-cli.jar audit verify .aiv/audit.log
java -jar aiv-cli.jar audit verify .aiv/audit.log --expect-head <sha256>
```

`aiv verify --audit-log` returns `audit.record_hash` in its machine response.

## Security and storage

The log is tamper-evident, not encrypted, authenticated, access-controlled, or immutable storage. Payloads may include repository metadata/findings; protect the file according to your data classification. The sidecar lock file is coordination state and can remain beside the log.

## Extension points

Build signing/transparency/storage adapters above this module. Do not change the existing line/hash format silently; readers and external anchors depend on it. A future signed format should version its envelope.

## Tests and build

`HashChainAuditTest` covers genesis/head, append/payload decode, tampering/reordering/deletion/malformed/base64, external anchors, thread concurrency, process concurrency, hashing, and I/O paths.

```bash
mvn -pl aiv-audit test
mvn -pl aiv-audit verify
```

## Interactions

`aiv-cli` records runs and prints anchors. `aiv-report` verifies and summarizes chains. `aiv-governance` can record routing decisions. The module remains generic and has no GitHub/model dependency.
