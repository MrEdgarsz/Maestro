# Building the CLI

Use JDK 17 and build from the repository root:

```sh
./gradlew :maestro-cli:installDist
./maestro-cli/build/install/maestro/bin/maestro --version
```

This creates a separate CLI distribution and does not replace an installed
`maestro`. Building and copying the full distribution requires disk space for
native mobile dependencies as well as the web runtime.

# Sequential web batch execution

`maestro test-batch` keeps one JVM running while each request receives a fresh
headless ChromeDriver session and temporary browser profile. Cookies, storage,
permissions, and browser state are isolated. Nested `runFlow` commands stay in the
same scenario. The browser closes before the response is written.

Send one JSON object per line on stdin:

```json
{"id":"scenario-1","args":["--platform=web","--screen-size","1440x900","-e","KEY=value","/absolute/path/flow.yaml"]}
```

Stdout contains only responses:

```json
{"id":"scenario-1","exitCode":0}
```

Normal test logs go to stderr. Each request uses normal `test` options and one
absolute web flow file. Continuous mode, sharding, and analysis are unsupported.
Options and `-e` values start fresh on every request. Normal non-flattened report
folders are unique per request; when using `--flatten-debug-output`, provide
unique output paths yourself.

An ordinary failing test returns its exit code and the batch can continue. Invalid
JSON returns exit code 2. Cancellation, failed initialization, or failed browser
cleanup terminates the batch without a completion response for that request; the
caller must treat process exit or a missing response as an infrastructure failure.
EOF ends the batch normally.

Check support before sending requests:

```sh
maestro test-batch --capabilities
```

```json
{"protocolVersion":1,"platform":"web","headless":true,"isolation":"fresh-browser-per-request"}
```

The batch protocol is independent of the CLI version. A custom build should report
its actual version; callers can use the capability contract to select this mode.

# Focused regression checks

```sh
./gradlew :maestro-cli:test --tests maestro.cli.command.TestBatchCommandTest --tests maestro.cli.session.IsolatedWebSessionTest
./gradlew :maestro-web:test --tests CdpClientTest
MAESTRO_WEB_TESTS=true ./gradlew :maestro-client:test --tests maestro.drivers.CdpWebDriverBrowserTest
python3 maestro-cli/src/test/scripts/test_batch.py /absolute/path/to/maestro /tmp/maestro-batch-proof
```

The browser tests always use headless mode. Configure Selenium's `SE_BROWSER_PATH`
and `SE_CACHE_PATH` when using a specific isolated Chrome/ChromeDriver installation.
The Python regression starts only its own loopback fixture server, checks actual
success/failure/success requests and artifact isolation, and retains its evidence
in the supplied output directory.
