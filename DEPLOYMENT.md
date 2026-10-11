# Deployment requirements

Before running migrations, a database administrator must install the standard `pg_trgm` extension
in each target PostgreSQL database. Install it in the application schema, or another schema on which
the migration and application roles each have `USAGE`. The administrator creates the intended schema
before installing the extension and grants access to both roles if they are different. The first
migration checks the installation and the migration role's schema access before creating business
objects; migrations do not install or move the extension or grant access to it. An installation in
one database does not prepare other databases on the same server.

Run the migration application against the intended PostgreSQL database before starting the web
application. Set `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME`, with credentials (a password,
or a client certificate configured through the datasource URL), for both applications. Set
`PRUNUS_FLYWAY_COMMAND=migrate` for the migration application; it applies the common migrations. The
web application has no default database connection, and its URL must put the application schema on the
search path. If migration is skipped, either application connects to the wrong database, or that schema
is absent from the search path, web queries fail.

Set `lilradish.model-calls.endpoint.enabled=true`, positive `connect-timeout` and `read-timeout`, and
an endpoint model list matching the deployed model catalog. Each model needs its own `base-url` and
`api-key-variable`, alongside its vendor `id`, modes and call routes. The named variable must exist
in the process environment and hold that model's bearer token; configuration files never supply the
token. Ordinary and tool calls use the selected model's address and key, including when models share
an address. The application validates every configured model at startup. A missing address or key,
invalid settings, a catalog mismatch or a missing caller prevents startup.

For an invented deployment, model `cedar_model` could name `CEDAR_MODEL_KEY` and model `birch_model`
could name `BIRCH_MODEL_KEY`. Set the tokens through the process manager's secret facility, and place
only those variable names in `endpoint.models[0].api-key-variable` and
`endpoint.models[1].api-key-variable`. Supply each address in the corresponding model's `base-url`;
no address or token has a deployment default. Addresses must use HTTP or HTTPS and carry no user
information, query or fragment. Variable names use letters, digits and underscores, beginning with
a letter or underscore. The common `read-timeout` also determines the shutdown budget below.

## Provide user identification

Supply exactly one production implementation of
`com.lilradish.lite.app.identification.UserIdentification` and register it as a Spring bean.
Include the implementation and its bean registration configuration in the application's runtime
classpath or release package, and ensure the startup context loads that configuration.

The interface's `identify(HttpServletRequest request)` returns an `Optional<UserId>`: the identified
user, or `Optional.empty()` when the request cannot be identified. `UserIdentificationRequired`
refuses startup when no implementation is registered. Development identity components are not
shipped in the production archive.

## Size file storage and workers

`lilradish.files.maximum-size` defaults to `100MB` (100 MiB, 104,857,600 bytes) per file;
`lilradish.files.maximum-per-field` defaults to 100 files in one field value; and
`lilradish.files.maximum-total-size` defaults to `1GB` (1 GiB, 1,073,741,824 bytes) for all new
files handed over in one run start, one answer, or one try of code. Declarations may set smaller file
and count limits, but their combined maximum must fit the deployment's total. These are limits on file
content, not a total database quota or a limit on multipart framing bytes. Choose lower limits when the
database, backup window, or upload concurrency cannot sustain them. The application refuses startup
when a deployment limit is absent or nonpositive, or when the total is less than the single-file limit.

Content is kept in PostgreSQL `bytea`, once per file ID, with separate metadata and run-holder rows.
A new file writes content before metadata in one transaction; a failed batch rolls all rows back.
Reusing a file in another run adds a holder and no second content row. The run transaction stays open while each stream is consumed and written. Keep upload concurrency
within the JDBC connection pool and the database's capacity to absorb these long writes; estimate
simultaneous new bytes as active uploads multiplied by their accepted sizes. This release does not purge old file
content, so include retained files in database growth, WAL retention, replication and backup capacity.
A base backup includes the table and TOAST contents; WAL archiving and replication carry the inserts as
well. Do not size backups from metadata or holder counts alone.

Multipart file requests above 1 MiB, or with no declared length, hold the single large-body turn
while the service checks its declaration and reads every file. Other large requests may wait for at
most 30 seconds before receiving a busy refusal. At the accepted floor of 256 KiB/s, a full 1 GiB of
files takes 4,096 seconds plus the fixed 30-second allowance, about 68 minutes 46 seconds. During that
time one request can hold one read-write database connection, its transaction and a worker. Starting a
run holds group and version SHARE locks during upload: another ordinary start in the same group can
share them, while a membership or version change needing a write lock waits. The run-numbering lock is
taken after upload. Answering holds the run tree lock, so work on the same tree that needs that lock
waits. A supplied Content-Length over the bounded document, framing and total file allowance is
refused before any file bytes are read; undeclared lengths are bounded as the payload arrives. A
future implementation could stage content within the one storage entry before the run transaction to
reduce lock and connection time; this release writes it in the transaction.

In embedded PostgreSQL, one locally generated incompressible file gave these observations. The
measurements queried `pg_total_relation_size('app.file_contents')` and the difference between before
and after `pg_current_wal_insert_lsn()` readings around one committed transaction. Elapsed time covers
the application's upload transaction on this test host, not an HTTP request or a network transfer.

| Input | Content relation growth | WAL inserted | Transaction elapsed |
|---:|---:|---:|---:|
| 1,048,576 bytes (1 MiB) | 1,146,880 bytes | 1,123,976 bytes | 36 ms |
| 104,857,600 bytes (100 MiB) | 108,847,104 bytes | 112,231,600 bytes | 789 ms |

For the 100 MiB run, the test worker allocated its input array before sampling. Its heap used was
155,407,688 bytes before the transaction and reached 166,942,024 bytes in 2 ms samples during it.
That sample excludes the input array's allocation from the measured change, can miss shorter peaks, and
says nothing about a concurrent production workload. TOAST page occupancy, compression, checkpoints,
full-page images, indexes, and concurrent activity change database, WAL, time and heap figures; use the
observed 100 MiB result for capacity planning with margin, not as a guaranteed upper bound.

The current pgjdbc 42.7.13 unknown-length `setBinaryStream` path initially buffers up to 51,200 bytes
and then copies larger streams into a driver-managed `postgres-pgjdbc-stream*.tmp` file before sending
them to PostgreSQL. Provide enough free space in the JVM's temporary directory for concurrent uploads,
up to roughly their total in-flight file bytes, even though the web layer does not stage uploads. The
driver registers cleanup for its temporary files, but cleanup timing and interrupted processes still
make temporary-disk headroom and monitoring necessary.
The read path obtains each `bytea` as a whole `byte[]` before returning an `InputStream`; one 100 MiB
file therefore retains at least a 100 MiB heap array until its stream is closed or collected, with
additional JDBC and response buffers possible. `FileOpenAdmission.MOST_OPEN` permits four concurrent
downloads, while `EngineExecutor.CODE_THREADS` allows two code steps to hold one open input each. Budget
at least the maximum single-file size times the sum of those limits (six at these settings), plus
ordinary application use, JDBC copies and response buffers. Downloads wait at most 30 seconds for a
permit before a busy response. Code steps do not take download permits, so slow downloads cannot hold
them back. A code step that keeps one file open indefinitely can still delay its own completion and
orderly shutdown; the engine's drain budget does not time the code out.

Uploads and downloads use a 30-second allowance plus 256 KiB/s of transferred content. A download is
written in 64 KiB chunks and disconnected when a completed chunk falls behind. Tomcat 11.0.24's HTTP/1.1
connector defaults to a 60-second connection timeout, which also interrupts a blocking write that makes
no progress. A person allowed to read a run can keep receiving a few bytes at a time and reset that
write timeout before a chunk returns. Four such downloads can occupy every download permit indefinitely,
so other downloads receive busy responses; code steps and other requests continue. A future asynchronous
download writer is needed to enforce the file download progress rule for that case. These behaviors come
from pgjdbc 42.7.13 `PgPreparedStatement.java:1628-1660`, `StreamWrapper.java:27-80`,
`PgResultSet.java:3045-3065`, and Tomcat 11.0.24 `AbstractHttp11Protocol.java:80`,
`http11/Constants.java:29`, `NioEndpoint.java:584-585,1655-1697`; the
read behavior is also explicit in `DatabaseFileContentStore`.

Successful code outputs and person-supplied values retain their JSON but may no longer identify the
original field kind. When a current code release declares such a value as a file, it is shown as a
file only if its complete canonical metadata matches a real file holder in the requested run. The
original kind cannot be recovered from that metadata. This reinterpretation only changes how the value
is shown. Model-sourced content remains subject to its
source content permission, including when the current declaration labels it as a file.

## Bound how deep runs go

`lilradish.runs.maximum-depth` is how many runs deep one tree may go, counting the run at the top as one.
The application's own setting is 32; the application refuses startup if it is missing or below one. A
step that would start a run deeper than that fails instead, offering nothing; a deployment set to 1 lets
no run start beneath another. Raising it lets a workflow that reaches itself start that many more runs in
one tree before it fails, each taking the next number of its group. A failure says how deep runs may go
as the setting stands when it is read, so changing it, either way, also changes what earlier failures say.

Each level is a transaction of its own that holds the tree's lock and, while it starts its run, the group's
numbering lock, for roughly 10 to 20 milliseconds; meanwhile other runs of the same group wait to start.
Each level also rereads the whole tree, so later levels cost more: a single run at the top that goes all
the way down takes about 1 second at 32 levels, about 4 seconds at 200, and over a minute at 2000. No
setting caps it; choose one with that in mind.

## Allow an orderly stop to finish

Configure the process manager (for example, Kubernetes `terminationGracePeriodSeconds`, Docker `stop -t`,
or systemd `TimeoutStopSec`) to send SIGTERM and allow the application to finish before its hard-kill
deadline. It first stops accepting web work gracefully for one shutdown phase; the model-call shutdown
signal then ends waits before an automatic resend while leaving sends already in flight to finish; the
engine drains model calls and code steps; finally it releases the one-process database lock. Spring Boot
4.1.1 defaults to `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s`.
`ModelCallShutdown`, `EngineExecutor`, and `OneProcess` implement the application's part of that order.

Set the hard-kill grace period **strictly greater than**:

```text
spring.lifecycle.timeout-per-shutdown-phase
  + max(lilradish.model-calls.endpoint.read-timeout,
        lilradish.code-steps.longest-run)
  + 30 seconds
```

`EngineExecutor.drain` uses that maximum plus 30 seconds to write outcomes after a call or code run
returns. The current `lilradish.code-steps.longest-run` default is one minute; the model read timeout is
mandatory and has no deployment default. Set `longest-run` to cover the longest code step in the deployed
release: it sizes the drain but does not time out the step itself. If it understates a step, orderly
shutdown may stop waiting before that step finishes.

With an explicitly configured read timeout of **at most one minute**, the current sum is **30 + 60 + 30 =
120 seconds**, so choose **more than 120 seconds**, with operational margin. If the read timeout is two
minutes, the sum is 180 seconds, so choose more than 180 seconds. Recalculate whenever either timeout or
the Spring lifecycle timeout changes. If the manager sends SIGKILL too early, an in-flight call cannot
finish and record its answer; on restart it is settled as sent without an answer, consuming a try.

## Keep one process per database

Never run two active web instances against the same database. `OneProcess` takes one session-level
PostgreSQL advisory lock on startup and holds it through shutdown. Stop the old instance before starting
its replacement. A replacement retries once per second for one web phase plus the engine drain budget;
if the old instance still owns the lock at that deadline, startup fails. [PostgreSQL 18.4 advisory lock
semantics](https://github.com/postgres/postgres/blob/REL_18_4/doc/src/sgml/mvcc.sgml#L1436-L1449)
and the [session-level try-lock function](https://github.com/postgres/postgres/blob/REL_18_4/doc/src/sgml/func.sgml#L29589-L29605)
explain why overlapping rollout can leave the replacement unable to start, even though the lock prevents
concurrent run execution.

Configure the process manager to restart the application after a nonzero exit. The lock watcher checks
the held session, attempts to reacquire on a new session within 30 seconds after loss, and exits with
status 1 if it cannot. Without a restart policy, no instance remains to process runs after that failure.

## Preserve the lock session and connection capacity

Use a direct PostgreSQL connection or a pool that preserves the application's physical session for the
entire connection lifetime. Do not put a transaction-pooling proxy between the application and
PostgreSQL: `OneProcess` uses `pg_try_advisory_lock` and later `pg_advisory_unlock` on its held connection,
while PostgreSQL ties a [session-level lock to the session](https://github.com/postgres/postgres/blob/REL_18_4/doc/src/sgml/mvcc.sgml#L1436-L1449)
rather than to one transaction. Transaction pooling can move those operations between server sessions
and break one-process exclusion.

Reserve capacity for a connection held throughout the process lifetime. Set the database role's
connection limit **greater than the configured Hikari maximum pool size plus one**; otherwise startup or
replacement can exhaust available connections before it takes the lock. With the unmodified HikariCP
7.0.2 default maximum of **10**, that means a role limit **greater than 11**; adjust it if
`spring.datasource.hikari.maximum-pool-size` changes.

The application sets `tcp_keepalives_idle=10` seconds, `tcp_keepalives_interval=5` seconds,
`tcp_keepalives_count=3`, and `tcp_user_timeout=25000` milliseconds on its lock session; no separate
deployment setting for these values is required. Their target is server-side detection and lock recovery
about **25–30 seconds** after the instance host dies; actual timing depends on the network and the
PostgreSQL host's [TCP support](https://github.com/postgres/postgres/blob/REL_18_4/doc/src/sgml/config.sgml#L883-L962).
Until the old session is reaped, the replacement cannot acquire the lock and may fail its startup
deadline. Check that the database host supports these TCP settings and measure failover in the deployed
network.

## Select one environment

`application.yaml` holds shared configuration. Activate exactly one environment with
`--spring.profiles.active=local`, `--spring.profiles.active=dev` or
`--spring.profiles.active=prod`. Each environment file contains its own environment-specific settings;
no profile group or configuration import combines them.

The development source set is `local`. Its local and dev profiles currently provide the same invented
model catalog, model-call routes and demonstration seed, alongside the development identity, ordinary
model calls, tool model calls and search way. Edit each environment's own file independently. Run
migrations with the selected profile before starting the web application with it.

Production uses `prod` and receives its real model catalog, endpoint settings, credentials and identity
implementation through deployment configuration as described above. No production addresses or tokens
are kept here. Shipped archives contain only the shared and prod configuration, excluding development
classes, local/dev profile files and seed SQL.

Run prod from a shipped archive or an application runtime classpath without the development source set.
`bootRun` adds the local source-set output for development. Selecting prod on that classpath registers
both development and production model callers, so startup refuses the ambiguous caller configuration.

The web prod file supplies two complete model templates. Provide every placeholder below through
the process environment or external configuration. Values have no repository defaults. Each model
has two custom modes, two price currencies and two tools with two price currencies each. Supply
distinct names and ISO currencies within each corresponding list. Prices are independent amounts,
not currency conversions. The endpoint is explicitly enabled in prod so startup must assemble
production callers. Each model's bearer token is supplied only in the process variable named by its
API-key-variable placeholder. Tool routes require deployment-supplied converters supporting their
read-only kind; the built-in `openai_compatible` converter supports ordinary calls only.

Each model retains both singleton tool routes and a route offering the pair. Catalog and endpoint
names and modes must agree. The table gives every placeholder and every configuration location
where it is used; repeated names denote one value, not an additional placeholder.

| Placeholder | Configuration item | Required value |
|---|---|---|
| `LILRADISH_MODEL_NAME` | `lilradish.deployed.models[0].name`, `lilradish.model-calls.endpoint.models[0].name` | Distinct valid deployment model name, paired across catalog and endpoint |
| `LILRADISH_MODEL_MODE` | `lilradish.deployed.models[0].modes[0]`, `lilradish.model-calls.endpoint.models[0].modes[0].mode` | Distinct supported custom mode name, paired across catalog and endpoint |
| `LILRADISH_MODEL_MODE_2` | `lilradish.deployed.models[0].modes[1]`, `lilradish.model-calls.endpoint.models[0].modes[1].mode` | Distinct supported custom mode name, paired across catalog and endpoint |
| `LILRADISH_MODEL_SENT_PER_CALL_LIMIT` | `lilradish.deployed.models[0].sent-per-call-limit` | Positive count |
| `LILRADISH_MODEL_CHARACTERS_PER_UNIT` | `lilradish.deployed.models[0].characters-per-unit` | Positive decimal measure |
| `LILRADISH_MODEL_CAME_BACK_PER_CALL_LIMIT` | `lilradish.deployed.models[0].came-back-per-call-limit` | Positive count |
| `LILRADISH_MODEL_PRICE_CURRENCY` | `lilradish.deployed.models[0].prices-per-million[0].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_SENT_PER_MILLION` | `lilradish.deployed.models[0].prices-per-million[0].sent-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_CAME_BACK_PER_MILLION` | `lilradish.deployed.models[0].prices-per-million[0].came-back-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_PRICE_CURRENCY_2` | `lilradish.deployed.models[0].prices-per-million[1].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_SENT_PER_MILLION_2` | `lilradish.deployed.models[0].prices-per-million[1].sent-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_CAME_BACK_PER_MILLION_2` | `lilradish.deployed.models[0].prices-per-million[1].came-back-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_TOOL_NAME` | `lilradish.deployed.models[0].tools[0].name`, `lilradish.model-calls.endpoint.models[0].calls[0].tools[0]`, `lilradish.model-calls.endpoint.models[0].calls[2].tools[0]` | Distinct valid tool name, paired with its singleton and paired endpoint routes |
| `LILRADISH_MODEL_TOOL_KIND` | `lilradish.deployed.models[0].tools[0].kind` | Supported read-only kind, currently web_search |
| `LILRADISH_MODEL_TOOL_MAX_USES` | `lilradish.deployed.models[0].tools[0].max-uses-per-call` | Positive maximum uses per call |
| `LILRADISH_MODEL_TOOL_PRICE_CURRENCY` | `lilradish.deployed.models[0].tools[0].prices-per-use[0].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_TOOL_PRICE_PER_USE` | `lilradish.deployed.models[0].tools[0].prices-per-use[0].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_TOOL_PRICE_CURRENCY_2` | `lilradish.deployed.models[0].tools[0].prices-per-use[1].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_TOOL_PRICE_PER_USE_2` | `lilradish.deployed.models[0].tools[0].prices-per-use[1].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_TOOL_2_NAME` | `lilradish.deployed.models[0].tools[1].name`, `lilradish.model-calls.endpoint.models[0].calls[1].tools[0]`, `lilradish.model-calls.endpoint.models[0].calls[2].tools[1]` | Distinct valid tool name, paired with its singleton and paired endpoint routes |
| `LILRADISH_MODEL_TOOL_2_KIND` | `lilradish.deployed.models[0].tools[1].kind` | Supported read-only kind, currently web_search |
| `LILRADISH_MODEL_TOOL_2_MAX_USES` | `lilradish.deployed.models[0].tools[1].max-uses-per-call` | Positive maximum uses per call |
| `LILRADISH_MODEL_TOOL_2_PRICE_CURRENCY` | `lilradish.deployed.models[0].tools[1].prices-per-use[0].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_TOOL_2_PRICE_PER_USE` | `lilradish.deployed.models[0].tools[1].prices-per-use[0].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_TOOL_2_PRICE_CURRENCY_2` | `lilradish.deployed.models[0].tools[1].prices-per-use[1].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_TOOL_2_PRICE_PER_USE_2` | `lilradish.deployed.models[0].tools[1].prices-per-use[1].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_NAME` | `lilradish.deployed.models[1].name`, `lilradish.model-calls.endpoint.models[1].name` | Distinct valid deployment model name, paired across catalog and endpoint |
| `LILRADISH_MODEL_2_MODE` | `lilradish.deployed.models[1].modes[0]`, `lilradish.model-calls.endpoint.models[1].modes[0].mode` | Distinct supported custom mode name, paired across catalog and endpoint |
| `LILRADISH_MODEL_2_MODE_2` | `lilradish.deployed.models[1].modes[1]`, `lilradish.model-calls.endpoint.models[1].modes[1].mode` | Distinct supported custom mode name, paired across catalog and endpoint |
| `LILRADISH_MODEL_2_SENT_PER_CALL_LIMIT` | `lilradish.deployed.models[1].sent-per-call-limit` | Positive count |
| `LILRADISH_MODEL_2_CHARACTERS_PER_UNIT` | `lilradish.deployed.models[1].characters-per-unit` | Positive decimal measure |
| `LILRADISH_MODEL_2_CAME_BACK_PER_CALL_LIMIT` | `lilradish.deployed.models[1].came-back-per-call-limit` | Positive count |
| `LILRADISH_MODEL_2_PRICE_CURRENCY` | `lilradish.deployed.models[1].prices-per-million[0].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_2_SENT_PER_MILLION` | `lilradish.deployed.models[1].prices-per-million[0].sent-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_CAME_BACK_PER_MILLION` | `lilradish.deployed.models[1].prices-per-million[0].came-back-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_PRICE_CURRENCY_2` | `lilradish.deployed.models[1].prices-per-million[1].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_2_SENT_PER_MILLION_2` | `lilradish.deployed.models[1].prices-per-million[1].sent-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_CAME_BACK_PER_MILLION_2` | `lilradish.deployed.models[1].prices-per-million[1].came-back-per-million` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_TOOL_NAME` | `lilradish.deployed.models[1].tools[0].name`, `lilradish.model-calls.endpoint.models[1].calls[0].tools[0]`, `lilradish.model-calls.endpoint.models[1].calls[2].tools[0]` | Distinct valid tool name, paired with its singleton and paired endpoint routes |
| `LILRADISH_MODEL_2_TOOL_KIND` | `lilradish.deployed.models[1].tools[0].kind` | Supported read-only kind, currently web_search |
| `LILRADISH_MODEL_2_TOOL_MAX_USES` | `lilradish.deployed.models[1].tools[0].max-uses-per-call` | Positive maximum uses per call |
| `LILRADISH_MODEL_2_TOOL_PRICE_CURRENCY` | `lilradish.deployed.models[1].tools[0].prices-per-use[0].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_2_TOOL_PRICE_PER_USE` | `lilradish.deployed.models[1].tools[0].prices-per-use[0].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_TOOL_PRICE_CURRENCY_2` | `lilradish.deployed.models[1].tools[0].prices-per-use[1].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_2_TOOL_PRICE_PER_USE_2` | `lilradish.deployed.models[1].tools[0].prices-per-use[1].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_TOOL_2_NAME` | `lilradish.deployed.models[1].tools[1].name`, `lilradish.model-calls.endpoint.models[1].calls[1].tools[0]`, `lilradish.model-calls.endpoint.models[1].calls[2].tools[1]` | Distinct valid tool name, paired with its singleton and paired endpoint routes |
| `LILRADISH_MODEL_2_TOOL_2_KIND` | `lilradish.deployed.models[1].tools[1].kind` | Supported read-only kind, currently web_search |
| `LILRADISH_MODEL_2_TOOL_2_MAX_USES` | `lilradish.deployed.models[1].tools[1].max-uses-per-call` | Positive maximum uses per call |
| `LILRADISH_MODEL_2_TOOL_2_PRICE_CURRENCY` | `lilradish.deployed.models[1].tools[1].prices-per-use[0].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_2_TOOL_2_PRICE_PER_USE` | `lilradish.deployed.models[1].tools[1].prices-per-use[0].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_MODEL_2_TOOL_2_PRICE_CURRENCY_2` | `lilradish.deployed.models[1].tools[1].prices-per-use[1].currency` | Distinct ISO currency code for each price in the same list |
| `LILRADISH_MODEL_2_TOOL_2_PRICE_PER_USE_2` | `lilradish.deployed.models[1].tools[1].prices-per-use[1].per-use` | Nonnegative decimal price, independent of the other currency |
| `LILRADISH_ENDPOINT_CONNECT_TIMEOUT` | `lilradish.model-calls.endpoint.connect-timeout` | Positive duration |
| `LILRADISH_ENDPOINT_READ_TIMEOUT` | `lilradish.model-calls.endpoint.read-timeout` | Positive duration |
| `LILRADISH_MODEL_ID` | `lilradish.model-calls.endpoint.models[0].id` | Vendor model identifier |
| `LILRADISH_MODEL_BASE_URL` | `lilradish.model-calls.endpoint.models[0].base-url` | HTTP(S) base URL without credentials, query or fragment |
| `LILRADISH_MODEL_API_KEY_VARIABLE` | `lilradish.model-calls.endpoint.models[0].api-key-variable` | Name of the process variable holding this model's bearer token |
| `LILRADISH_MODEL_REASONING_EFFORT` | `lilradish.model-calls.endpoint.models[0].modes[0].reasoning-effort` | An effort supported by the deployed endpoint |
| `LILRADISH_MODEL_REASONING_EFFORT_2` | `lilradish.model-calls.endpoint.models[0].modes[1].reasoning-effort` | An effort supported by the deployed endpoint |
| `LILRADISH_MODEL_CALL_PATH` | `lilradish.model-calls.endpoint.models[0].calls[0].path` | Absolute route path without query, fragment or parent traversal |
| `LILRADISH_MODEL_CALL_OUTBOUND` | `lilradish.model-calls.endpoint.models[0].calls[0].outbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_CALL_INBOUND` | `lilradish.model-calls.endpoint.models[0].calls[0].inbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_CALL_2_PATH` | `lilradish.model-calls.endpoint.models[0].calls[1].path` | Absolute route path without query, fragment or parent traversal |
| `LILRADISH_MODEL_CALL_2_OUTBOUND` | `lilradish.model-calls.endpoint.models[0].calls[1].outbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_CALL_2_INBOUND` | `lilradish.model-calls.endpoint.models[0].calls[1].inbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_CALL_PAIR_PATH` | `lilradish.model-calls.endpoint.models[0].calls[2].path` | Absolute route path without query, fragment or parent traversal |
| `LILRADISH_MODEL_CALL_PAIR_OUTBOUND` | `lilradish.model-calls.endpoint.models[0].calls[2].outbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_CALL_PAIR_INBOUND` | `lilradish.model-calls.endpoint.models[0].calls[2].inbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_2_ID` | `lilradish.model-calls.endpoint.models[1].id` | Vendor model identifier |
| `LILRADISH_MODEL_2_BASE_URL` | `lilradish.model-calls.endpoint.models[1].base-url` | HTTP(S) base URL without credentials, query or fragment |
| `LILRADISH_MODEL_2_API_KEY_VARIABLE` | `lilradish.model-calls.endpoint.models[1].api-key-variable` | Name of the process variable holding this model's bearer token |
| `LILRADISH_MODEL_2_REASONING_EFFORT` | `lilradish.model-calls.endpoint.models[1].modes[0].reasoning-effort` | An effort supported by the deployed endpoint |
| `LILRADISH_MODEL_2_REASONING_EFFORT_2` | `lilradish.model-calls.endpoint.models[1].modes[1].reasoning-effort` | An effort supported by the deployed endpoint |
| `LILRADISH_MODEL_2_CALL_PATH` | `lilradish.model-calls.endpoint.models[1].calls[0].path` | Absolute route path without query, fragment or parent traversal |
| `LILRADISH_MODEL_2_CALL_OUTBOUND` | `lilradish.model-calls.endpoint.models[1].calls[0].outbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_2_CALL_INBOUND` | `lilradish.model-calls.endpoint.models[1].calls[0].inbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_2_CALL_2_PATH` | `lilradish.model-calls.endpoint.models[1].calls[1].path` | Absolute route path without query, fragment or parent traversal |
| `LILRADISH_MODEL_2_CALL_2_OUTBOUND` | `lilradish.model-calls.endpoint.models[1].calls[1].outbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_2_CALL_2_INBOUND` | `lilradish.model-calls.endpoint.models[1].calls[1].inbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_2_CALL_PAIR_PATH` | `lilradish.model-calls.endpoint.models[1].calls[2].path` | Absolute route path without query, fragment or parent traversal |
| `LILRADISH_MODEL_2_CALL_PAIR_OUTBOUND` | `lilradish.model-calls.endpoint.models[1].calls[2].outbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_MODEL_2_CALL_PAIR_INBOUND` | `lilradish.model-calls.endpoint.models[1].calls[2].inbound` | Registered converter ID supporting the route's tool set |
| `LILRADISH_RESEND_TIMES` | `lilradish.model-calls.resend.times` | Nonnegative count |
| `LILRADISH_RESEND_FIRST_WAIT` | `lilradish.model-calls.resend.first-wait` | Positive duration |
| `LILRADISH_RESEND_LONGEST_WAIT` | `lilradish.model-calls.resend.longest-wait` | Duration at least as long as first wait |

External configuration may replace either complete model list. Spring configuration lists replace
the template in full; adding only another item does not extend it. Keep the catalog and endpoint
lists consistent. A model with no modes or tools supplies empty lists explicitly and still needs
an ordinary endpoint. Supply the key for each model through its own named process variable.
