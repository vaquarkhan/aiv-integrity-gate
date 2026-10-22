# Proposal scan: flip in FLINK

Advisory, deterministic evidence about each document. It does not judge the design, and it does not determine authorship. Number checks used the space index.

Proposals scanned: 30. With at least one finding: 7.

| Rule | Proposals |
|---|---|
| `proposal.empty-section` | 1 |
| `proposal.missing-section` | 3 |
| `proposal.number-collision` | 2 |
| `proposal.placeholder` | 1 |

| Proposal | State | Version | Findings |
|---|---|---|---|
| [FLIP-617: Add a Public REST Endpoint for Operator Coordinator Requests](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451979787/FLIP-617+Add+a+Public+REST+Endpoint+for+Operator+Coordinator+Requests) | discussion | 7 | none |
| [FLIP-616: Materialized Tables support for Table API](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451979479/FLIP-616+Materialized+Tables+support+for+Table+API) | discussion | 2 | none |
| [FLIP-613 : Remote Artifact Fetch for Session-Mode Job Submission - detailed](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451979327/FLIP-613+Remote+Artifact+Fetch+for+Session-Mode+Job+Submission+-+detailed) | unknown | 2 | proposal.number-collision |
| [FLIP-615: Snapshot Splitters for Table and Database Ingestion](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451975192/FLIP-615+Snapshot+Splitters+for+Table+and+Database+Ingestion) | discussion | 10 | none |
| [FLIP-614: Add built-in DEDUPLICATE_KEEP_FIRST PTF](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451975182/FLIP-614+Add+built-in+DEDUPLICATE_KEEP_FIRST+PTF) | unknown | 6 | none |
| [FLIP-613 : Remote Artifact Fetch for Session-Mode Job Submission](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451974568/FLIP-613+Remote+Artifact+Fetch+for+Session-Mode+Job+Submission) | unknown | 10 | proposal.number-collision |
| [FLIP-612: Native XML Functions for Flink SQL](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451974259/FLIP-612+Native+XML+Functions+for+Flink+SQL) | voting | 11 | none |
| [FLIP-611: Running Flink jobs in MiniCluster using the Kubernetes Operator](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451974246/FLIP-611+Running+Flink+jobs+in+MiniCluster+using+the+Kubernetes+Operator) | unknown | 6 | none |
| [FLIP-610: Dynamic Ratelimit in Flink Source](https://cwiki.apache.org/confluence/spaces/FLINK/pages/451973744/FLIP-610+Dynamic+Ratelimit+in+Flink+Source) | unknown | 17 | none |
| [FLIP-608: Flink on Ray - Ray Resource Backend For Flink](https://cwiki.apache.org/confluence/spaces/FLINK/pages/449286348/FLIP-608+Flink+on+Ray+%E2%80%94+Ray+Resource+Backend+For+Flink) | discussion | 33 | none |
| [FLIP-609: Type Inference for Python User Defined Functions](https://cwiki.apache.org/confluence/spaces/FLINK/pages/449286339/FLIP-609+Type+Inference+for+Python+User+Defined+Functions) | unknown | 6 | none |
| [FLIP-607 Migrate Flink off end-of-life OkHttp; keep it off the core classpath](https://cwiki.apache.org/confluence/spaces/FLINK/pages/449282641/FLIP-607+Migrate+Flink+off+end-of-life+OkHttp+keep+it+off+the+core+classpath) | accepted | 3 | none |
| [FLIP-605: State catch-up of streaming jobs state using BATCH execution mode](https://cwiki.apache.org/confluence/spaces/FLINK/pages/446071315/FLIP-605+State+catch-up+of+streaming+jobs+state+using+BATCH+execution+mode) | unknown | 4 | none |
| [FLIP-604: Complete VARIANT Primitive Coverage with UUID and Timestamps](https://cwiki.apache.org/confluence/spaces/FLINK/pages/446070993/FLIP-604+Complete+VARIANT+Primitive+Coverage+with+UUID+and+Timestamps) | accepted | 8 | none |
| [FLIP-603: [FileSystems] GCS SDK-native filesystem](https://cwiki.apache.org/confluence/spaces/FLINK/pages/444334267/FLIP-603+FileSystems+GCS+SDK-native+filesystem) | unknown | 9 | none |
| [FLIP-602: Extended JDBC Scan Partitioning Options for Efficient Data Ingestion](https://cwiki.apache.org/confluence/spaces/FLINK/pages/440304839/FLIP-602+Extended+JDBC+Scan+Partitioning+Options+for+Efficient+Data+Ingestion) | unknown | 6 | none |
| [FLIP-601: [Filesystems] Client Side Encryption support for SDK Native FileSystems](https://cwiki.apache.org/confluence/spaces/FLINK/pages/440303935/FLIP-601+Filesystems+Client+Side+Encryption+support+for+SDK+Native+FileSystems) | unknown | 3 | none |
| [FLIP-600: Independent Checkpoint Based On Pipeline Region](https://cwiki.apache.org/confluence/spaces/FLINK/pages/438010016/FLIP-600+Independent+Checkpoint+Based+On+Pipeline+Region) | unknown | 22 | proposal.missing-section |
| [FLIP-599: State Catalog](https://cwiki.apache.org/confluence/spaces/FLINK/pages/438009922/FLIP-599+State+Catalog) | unknown | 13 | proposal.empty-section |
| [FLIP-598: [Filesystems] Gen2 Datalake SDK Native FileSystem for Azure](https://cwiki.apache.org/confluence/spaces/FLINK/pages/435028237/FLIP-598+Filesystems+Gen2+Datalake+SDK+Native+FileSystem+for+Azure) | discussion | 7 | none |
| [FLIP-597: [Filesystems] Hadoop-less Flink filesystems](https://cwiki.apache.org/confluence/spaces/FLINK/pages/435028214/FLIP-597+Filesystems+Hadoop-less+Flink+filesystems) | discussion | 14 | proposal.placeholder |
| [FLIP-595: Differentiate monotonic and non-monotonic counters in Flink metrics](https://cwiki.apache.org/confluence/spaces/FLINK/pages/435028151/FLIP-595+Differentiate+monotonic+and+non-monotonic+counters+in+Flink+metrics) | unknown | 12 | none |
| [FLIP-594: Support Auto-Rewrite for RpcOperator Service](https://cwiki.apache.org/confluence/spaces/FLINK/pages/435028051/FLIP-594+Support+Auto-Rewrite+for+RpcOperator+Service) | unknown | 6 | proposal.missing-section |
| [FLIP-593: Introduce Built-in Operators for Multimodal Data Processing](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430409341/FLIP-593+Introduce+Built-in+Operators+for+Multimodal+Data+Processing) | unknown | 1 | none |
| [FLIP-592: First-Class Accelerator Resource Support](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430409323/FLIP-592+First-Class+Accelerator+Resource+Support) | unknown | 6 | proposal.missing-section |
| [FLIP-591: Introducing Python DataFrame API in PyFlink](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430409184/FLIP-591+Introducing+Python+DataFrame+API+in+PyFlink) | unknown | 24 | none |
| [FLIP-590: Introduce Multimodal Data Type:  Vector, Tensor, and Image](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430408825/FLIP-590+Introduce+Multimodal+Data+Type+Vector+Tensor+and+Image) | unknown | 4 | none |
| [FLIP-589: Introduce FILE Type for Byte-Content References](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430408808/FLIP-589+Introduce+FILE+Type+for+Byte-Content+References) | unknown | 9 | none |
| [FLIP-588: Support per-job delegation tokens](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430408507/FLIP-588+Support+per-job+delegation+tokens) | accepted | 38 | none |
| [FLIP-587: Expose per downstream target numRecordsOut metric](https://cwiki.apache.org/confluence/spaces/FLINK/pages/430408366/FLIP-587+Expose+per+downstream+target+numRecordsOut+metric) | unknown | 2 | none |
