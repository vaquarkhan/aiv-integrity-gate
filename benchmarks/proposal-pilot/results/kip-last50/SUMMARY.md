# Proposal scan: kip in KAFKA

Advisory, deterministic evidence about each document. It does not judge the design, and it does not determine authorship. Number checks used the space index.

Proposals scanned: 50. With at least one finding: 9.

| Rule | Proposals |
|---|---|
| `proposal.assistant-artifact` | 1 |
| `proposal.empty-section` | 2 |
| `proposal.missing-section` | 3 |
| `proposal.placeholder` | 1 |
| `proposal.template-text` | 2 |
| `proposal.unknown-state` | 1 |

| Proposal | State | Version | Findings |
|---|---|---|---|
| [KIP-1381: Allow Brokers to Disable Compression Types for Client Writes](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451979375/KIP-1381+Allow+Brokers+to+Disable+Compression+Types+for+Client+Writes) | under discussion | 4 | none |
| [KIP-1382: Expose Explicit Configuration Deletions to AlterConfigPolicy](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451979367/KIP-1382+Expose+Explicit+Configuration+Deletions+to+AlterConfigPolicy) | under discussion | 3 | none |
| [KIP-1380: Measure local.retention.ms from remote upload time instead of record timestamps](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451979130/KIP-1380+Measure+local.retention.ms+from+remote+upload+time+instead+of+record+timestamps) | under discussion | 9 | none |
| [KIP-1379: Make server-side rack-aware assignment opt-in](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451979127/KIP-1379+Make+server-side+rack-aware+assignment+opt-in) | under discussion | 1 | none |
| [KIP-1378: Allow Consumer Group Renaming in MirrorMaker 2 Checkpoint Connector](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451974696/KIP-1378+Allow+Consumer+Group+Renaming+in+MirrorMaker+2+Checkpoint+Connector) | under discussion | 4 | proposal.template-text |
| [KIP-1377: ByteBuffer serialize method for Serializer](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451974658/KIP-1377+ByteBuffer+serialize+method+for+Serializer) | under discussion | 5 | none |
| [KIP-1376: Support setting TLS named groups](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451974516/KIP-1376+Support+setting+TLS+named+groups) | draft | 8 | none |
| [KIP-1375: Add SuspendReason to StateRestoreListener.onRestoreSuspended](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451972701/KIP-1375+Add+SuspendReason+to+StateRestoreListener.onRestoreSuspended) | under discussion | 2 | proposal.template-text |
| [KIP-1374: Allow StreamsBuilder.globalTable to Rekey](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451972636/KIP-1374+Allow+StreamsBuilder.globalTable+to+Rekey) | under discussion | 7 | none |
| [KIP-1373: Client Quota Utilization Metrics](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/451971254/KIP-1373+Client+Quota+Utilization+Metrics) | under discussion | 9 | none |
| [KIP-1372: Reject topic configurations where min.insync.replicas exceeds the replication factor](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/449286526/KIP-1372+Reject+topic+configurations+where+min.insync.replicas+exceeds+the+replication+factor) | under discussion | 2 | none |
| [KIP-1371: Explicit Wait Conditions for Consumer Request Managers](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/449282795/KIP-1371+Explicit+Wait+Conditions+for+Consumer+Request+Managers) | under discussion | 22 | none |
| [KIP-1370: Include Instance and Rack IDs in kafka-streams-groups.sh Member Output](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/446071011/KIP-1370+Include+Instance+and+Rack+IDs+in+kafka-streams-groups.sh+Member+Output) | under discussion | 10 | none |
| [KIP-1369: API-Contract Annotations for Nullity/Thread Safety](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440304999/KIP-1369+API-Contract+Annotations+for+Nullity+Thread+Safety) | draft | 5 | none |
| [KIP-1368: Client framework name and version](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440304679/KIP-1368+Client+framework+name+and+version) | under discussion | 18 | none |
| [KIP-1367: Reserve apiKey values for private use](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440304597/KIP-1367+Reserve+apiKey+values+for+private+use) | under discussion | 3 | none |
| [KIP-1366: Configurable Segment Interval for Window and Session Stores](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440304328/KIP-1366+Configurable+Segment+Interval+for+Window+and+Session+Stores) | under discussion | 6 | none |
| [KIP-1365: Transform Observability and Skipped Record Handling for Kafka Connect](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440304156/KIP-1365+Transform+Observability+and+Skipped+Record+Handling+for+Kafka+Connect) | under discussion | 2 | none |
| [KIP-1364: Expose Rebalance Cause to RebalanceConsumer and Metrics](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440304013/KIP-1364+Expose+Rebalance+Cause+to+RebalanceConsumer+and+Metrics) | in discussion | 10 | none |
| [KIP-1363: Add --checkpoint option to kafka-metadata-shell and deprecate --snapshot](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440303769/KIP-1363+Add+--checkpoint+option+to+kafka-metadata-shell+and+deprecate+--snapshot) | under discussion | 7 | proposal.empty-section |
| [KIP-1362: Add a rollback-compatible storage format option for KRaft migration](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/440303727/KIP-1362+Add+a+rollback-compatible+storage+format+option+for+KRaft+migration) | under discussion | 4 | none |
| [KIP-1361 Change the default `auto.offset.reset` to `by_duration:PT5S`](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/438009996/KIP-1361+Change+the+default+auto.offset.reset+to+by_duration+PT5S) | under discussion | 9 | none |
| [KIP-1360: Cluster Synchronous Mirroring](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430409277/KIP-1360+Cluster+Synchronous+Mirroring) | draft | 33 | proposal.placeholder |
| [KIP-1358: Gradual and gated preferred-leader election in the KRaft controller](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430409245/KIP-1358+Gradual+and+gated+preferred-leader+election+in+the+KRaft+controller) | under discussion | 4 | none |
| [KIP-1359: Improve usability of resetting group offsets](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430409229/KIP-1359+Improve+usability+of+resetting+group+offsets) | under discussion | 8 | none |
| [KIP-1357: Add broker side custom assignors for "streams" groups](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430408758/KIP-1357+Add+broker+side+custom+assignors+for+streams+groups) | accepted | 63 | none |
| [KIP-1356: Introduce IQv2 for headers-aware state stores](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430408653/KIP-1356+Introduce+IQv2+for+headers-aware+state+stores) | accepted | 19 | proposal.missing-section |
| [KIP-1355: In-rack traffic observability for clients and brokers](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430408177/KIP-1355+In-rack+traffic+observability+for+clients+and+brokers) | under discussion | 5 | none |
| [KIP-1354: Single purpose TLS extended key usages (EKU) support](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430408110/KIP-1354+Single+purpose+TLS+extended+key+usages+EKU+support) | under discussion | 5 | proposal.missing-section |
| [KIP-1352: Ranges and Range Aggregations in the Kafka Streams DSL](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430407894/KIP-1352+Ranges+and+Range+Aggregations+in+the+Kafka+Streams+DSL) | under discussion | 44 | none |
| [KIP-1351: Add --cancel-all to kafka-reassign-partitions](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/430407863/KIP-1351+Add+--cancel-all+to+kafka-reassign-partitions) | under discussion | 3 | none |
| [KIP-1350: Expose Streams Group Heartbeat Statuses as Kafka Streams Metrics](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/429064565/KIP-1350+Expose+Streams+Group+Heartbeat+Statuses+as+Kafka+Streams+Metrics) | under discussion | 6 | none |
| [KIP-1348: Complete Kafka Streams Built-in Exception Handler Symmetry with LogAndContinueProductionExceptionHandler](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/429064384/KIP-1348+Complete+Kafka+Streams+Built-in+Exception+Handler+Symmetry+with+LogAndContinueProductionExceptionHandler) | under discussion | 32 | none |
| [KIP-1346 Parallelize loadSegmentFiles](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958901/KIP-1346+Parallelize+loadSegmentFiles) | under discussion | 3 | none |
| [KIP-1347: Overriding voter set on storage formatting](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958889/KIP-1347+Overriding+voter+set+on+storage+formatting) | under discussion | 7 | none |
| [KIP-1349: Bytes-based configurable snapshot frequency for share groups](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958844/KIP-1349+Bytes-based+configurable+snapshot+frequency+for+share+groups) | under discussion | 87 | none |
| [KIP-1345: Cross-Cluster Atomic Transactions via Global Transaction Coordinator](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958837/KIP-1345+Cross-Cluster+Atomic+Transactions+via+Global+Transaction+Coordinator) | draft | 4 | proposal.empty-section, proposal.assistant-artifact |
| [KIP-1344: Add Transaction Coordinator Metrics for Transaction State Log Appends and Marker Completion](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958823/KIP-1344+Add+Transaction+Coordinator+Metrics+for+Transaction+State+Log+Appends+and+Marker+Completion) | under discussion. | 4 | none |
| [KIP-1343: Allow opt-in leader-only acknowledgement in DeleteRecords](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958800/KIP-1343+Allow+opt-in+leader-only+acknowledgement+in+DeleteRecords) | one of under discussion, accepted, rejected | 9 | proposal.unknown-state |
| [KIP-1342: Deprecate Authorizer#aclCount](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958795/KIP-1342+Deprecate+Authorizer+aclCount) | under discussion | 7 | none |
| [KIP-1341: Replace `<default>` placeholder in `ConfigEntity#name()` with `Optional<String> entityName()`](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958794/KIP-1341+Replace+default+placeholder+in+ConfigEntity+name+with+Optional+String+entityName) | under discussion | 4 | none |
| [KIP-1340: Expose Both Mapped Key and Stream Key in Streams-GlobalKTable Joins](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958552/KIP-1340+Expose+Both+Mapped+Key+and+Stream+Key+in+Streams-GlobalKTable+Joins) | accepted | 24 | none |
| [KIP-1339: Make KafkaConfigBackingStore read_write_total_timeout configurable](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958400/KIP-1339+Make+KafkaConfigBackingStore+read_write_total_timeout+configurable) | under discussion | 6 | none |
| [KIP-1338: Structured MDC Context for Kafka Logging](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958281/KIP-1338+Structured+MDC+Context+for+Kafka+Logging) | under discussion | 7 | none |
| [KIP-1337: Add Protobuf MessageFormatter for Kafka Console Consumer](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958161/KIP-1337+Add+Protobuf+MessageFormatter+for+Kafka+Console+Consumer) | under discussion | 3 | none |
| [KIP-1336: Progress Signaling for Share-Group In-Flight Records (markInProgress + IN_PROGRESS State)](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421958105/KIP-1336+Progress+Signaling+for+Share-Group+In-Flight+Records+markInProgress+IN_PROGRESS+State) | draft | 7 | proposal.missing-section |
| [KIP-1335 Bounded concurrency for partition reassignment via kafka-reassign-partitions.sh](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421957873/KIP-1335+Bounded+concurrency+for+partition+reassignment+via+kafka-reassign-partitions.sh) | under discussion | 12 | none |
| [KIP-1333: Support log segments larger than 2 GB](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421957815/KIP-1333+Support+log+segments+larger+than+2+GB) | under discussion | 9 | none |
| [KIP-1332: Dynamic memory allocation for the Kafka producer](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421957499/KIP-1332+Dynamic+memory+allocation+for+the+Kafka+producer) | accepted | 99 | none |
| [KIP-1329: ListClientConnections Admin API](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/421957264/KIP-1329+ListClientConnections+Admin+API) | under discussion | 6 | none |
