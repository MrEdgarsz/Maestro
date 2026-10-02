package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.session.SessionCleanupException
import picocli.CommandLine
import java.io.BufferedReader
import java.io.PrintStream
import java.io.PrintWriter
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException

private var batchProtocolOut: PrintStream = System.out

// Claim stdout before analytics/dependencies initialize, like the MCP entry point.
internal fun claimTestBatchStdout() {
    batchProtocolOut = System.out
    System.setOut(System.err)
}

@CommandLine.Command(
    name = "test-batch",
    description = [
        "Run sequential, isolated headless web tests over stdin JSON lines.",
        "Input: {\"id\":\"scenario\",\"args\":[\"-e\",\"KEY=value\",\"/absolute/flow.yaml\"]}",
        "Output: {\"id\":\"scenario\",\"exitCode\":0}. Normal test output goes to stderr.",
        "Each request uses a fresh browser profile and closes it before replying. EOF ends the batch.",
        "Only one web flow per request; continuous mode, sharding and analysis are unsupported.",
        "Pass unique --debug-output/--test-output-dir paths when using --flatten-debug-output.",
    ],
)
class TestBatchCommand : Callable<Int> {
    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.Option(names = ["--capabilities"], description = ["Print the batch protocol and isolation contract, then exit."])
    private var capabilities: Boolean = false

    override fun call(): Int {
        if (capabilities) {
            batchProtocolOut.println(jacksonObjectMapper().writeValueAsString(mapOf(
                "protocolVersion" to 1,
                "platform" to "web",
                "headless" to true,
                "isolation" to "fresh-browser-per-request",
            )))
            return 0
        }
        return runTestBatch(System.`in`.bufferedReader(), PrintWriter(batchProtocolOut, true), ::executeTest)
    }

    private fun executeTest(args: List<String>): Int {
        val commandLine = CommandLine(App())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setExecutionStrategy(DisableAnsiMixin::executionStrategy)
        val test = commandLine.subcommands.getValue("test").getCommand<TestCommand>()
        test.batchMode = true
        var fatal: Exception? = null
        commandLine.setExecutionExceptionHandler { error, command, _ ->
            if (error is SessionCleanupException || error is CancellationException || error is InterruptedException) {
                fatal = error
            }
            command.err.println(error.message)
            1
        }
        val result = commandLine.execute("--platform=web", "test", *args.toTypedArray())
        fatal?.let { throw it }
        return result
    }
}

/** One request completes (including browser teardown) before the next is read. */
internal fun runTestBatch(
    input: BufferedReader,
    output: PrintWriter,
    execute: (List<String>) -> Int,
): Int {
    val mapper = jacksonObjectMapper()
    while (true) {
        val line = input.readLine() ?: return 0
        var id: String? = null
        val args = try {
            val request = mapper.readTree(line)
            require(request.isObject && request["id"]?.isTextual == true)
            id = request["id"].asText()
            require(id.isNotBlank())
            val arguments = request["args"]
            require(arguments?.isArray == true && arguments.all { it.isTextual })
            arguments.map { it.asText() }
        } catch (_: Exception) {
            output.println(mapper.writeValueAsString(mapOf("id" to id, "exitCode" to 2)))
            if (output.checkError()) return 1
            continue
        }
        // Fatal errors deliberately escape: no success reply, and no next request
        // after cancellation or an uncertain browser teardown.
        val exitCode = execute(args)
        output.println(mapper.writeValueAsString(mapOf("id" to id, "exitCode" to exitCode)))
        if (output.checkError()) return 1
    }
}
