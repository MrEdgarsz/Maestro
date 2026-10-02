# Revento Maestro 2.11.0 release build

This checkout is based on upstream `cli-2.11.0` and builds a separate CLI archive
with `test-batch` plus the retained web driver fixes. It does not publish or
replace the upstream distribution.

Build from the repository root with JDK 17:

```sh
./gradlew --no-daemon :maestro-cli:distZip
shasum -a 256 maestro-cli/build/distributions/maestro.zip
```

The archive includes the normal `maestro-client.jar`, which in turn embeds
`maestro-web.js` from `maestro-client/src/main/resources/maestro-web.js`. Revento's
install patch must install this archive as a separate versioned distribution
and verify, without rewriting, the embedded bridge (SHA-256
`3c9d4f386363223c48771b701981e6262a0e2cc4b89ce96a5e36145e01a772c2`). The
2.7.0 install patch's bridge replacement step is superseded by this source change;
keep its post-install check but remove the duplicate patch/zip update. Do not replace
the bridge with a whole resource copied from the existing 2.7.0 installation. The 2.7.0 bridge
(SHA-256 `6b0351a77ff4fec78a52d22818ef6d99c42c363b56865e14b94ea88e721d627d`)
was shared by both arms of the earlier benchmark, but it predates safety fixes
in this 2.11.0 source bridge. This release keeps upstream Flutter semantic
identifiers and DOM-clobbering fallbacks, adds open shadow-root traversal, and
retains cycle-safe serialization. Its exact packaged bridge hash should be
recorded by the Revento install patch alongside the archive hash.

The batch protocol and headless regression are documented in
[`maestro-cli/README.md`](maestro-cli/README.md). Focused tests and the browser
gate run with:

```sh
./gradlew :maestro-cli:test --tests maestro.cli.command.TestBatchCommandTest --tests maestro.cli.session.IsolatedWebSessionTest
./gradlew :maestro-web:test --tests CdpClientTest
./gradlew :maestro-client:test --tests maestro.drivers.FlutterWebSemanticsIdentifierTest
./gradlew :maestro-test:test
MAESTRO_WEB_TESTS=true ./gradlew :maestro-client:test --tests maestro.drivers.CdpWebDriverBrowserTest
DIST_DIR=$(mktemp -d)
unzip -q maestro-cli/build/distributions/maestro.zip -d "$DIST_DIR"
python3 maestro-cli/src/test/scripts/test_batch.py "$DIST_DIR/maestro/bin/maestro" "$DIST_DIR/proof"
```
