package maestro.drivers

import CdpClient
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import maestro.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.chrome.ChromeDriver
import java.net.InetSocketAddress

/** Opt-in because the normal JVM gate must never launch a desktop browser. */
@EnabledIfEnvironmentVariable(named = "MAESTRO_WEB_TESTS", matches = "true")
class CdpWebDriverBrowserTest {
    private lateinit var server: HttpServer
    private lateinit var driver: CdpWebDriver
    private lateinit var origin: String

    @BeforeEach
    fun open() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "/delayed")
                exchange.sendResponseHeaders(302, -1)
            } else {
                if (exchange.requestURI.path == "/delayed") Thread.sleep(250)
                val html = """
                    <!doctype html><meta charset="utf-8">
                    <style>input,button{display:block;width:300px;height:40px;margin:20px}html,body{touch-action:none;min-height:100vh}</style>
                    <input id="text"><input id="password" type="password"><input id="date" type="date"><button id="button">Ready</button>
                    <div id="editable" contenteditable="true">prefix</div>
                    <script>
                      window.events = [];
                      for (const type of ['keydown','beforeinput','input','keyup','change',
                        'compositionstart','compositionupdate','compositionend',
                        'pointerover','mouseover','pointermove','pointerdown','mousedown',
                        'pointerup','mouseup','click']) {
                        document.addEventListener(type, e => events.push({type:e.type,
                          id:e.target.id, value:e.target.value, trusted:e.isTrusted, buttons:e.buttons}));
                      }
                    </script>
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            }
            exchange.close()
        }
        server.start()
        origin = "http://127.0.0.1:${server.address.port}"
        driver = CdpWebDriver(isStudio = false, isHeadless = true, screenSize = "800x600")
        driver.open()
        driver.launchApp(origin, emptyMap())
    }

    @AfterEach
    fun close() {
        try { if (::driver.isInitialized) driver.close() }
        finally { if (::server.isInitialized) server.stop(0) }
    }

    private fun browser(): ChromeDriver = CdpWebDriver::class.java
        .getDeclaredField("seleniumDriver").apply { isAccessible = true }.get(driver) as ChromeDriver

    private fun js(script: String): Any? = (browser() as JavascriptExecutor).executeScript(script)

    private fun center(id: String): Point {
        val coordinates = js("const r=document.getElementById('$id').getBoundingClientRect(); return [r.x+r.width/2,r.y+r.height/2]") as List<Number>
        return Point(coordinates[0].toInt(), coordinates[1].toInt())
    }

    @Test
    fun `typing preserves native keyboard input and blur change events including Unicode`() {
        js("document.getElementById('text').focus(); events=[]")
        driver.inputText("Aaé日本")
        assertThat(js("return document.getElementById('text').value")).isEqualTo("Aaé日本")
        assertThat(js("return events.filter(e=>e.type==='input').map(e=>e.value)"))
            .isEqualTo(listOf("A", "Aa", "Aaé", "Aaé日", "Aaé日本"))
        assertThat(js("return events.filter(e=>e.type==='input'||e.type==='keydown').every(e=>e.trusted)"))
            .isEqualTo(true)
        assertThat(js("return events.filter(e=>e.type==='change').length")).isEqualTo(0L)
        driver.tap(center("button"))
        assertThat(js("return events.filter(e=>e.type==='change').map(e=>e.value)"))
            .isEqualTo(listOf("Aaé日本"))
    }

    @Test
    fun `password punctuation and supplementary Unicode are not dropped`() {
        js("document.getElementById('password').focus(); events=[]")
        val text = "Az!@#_+é日本😀"
        driver.inputText(text)
        assertThat(js("return document.getElementById('password').value")).isEqualTo(text)
        assertThat(js("return events.filter(e=>e.type==='input').length")).isEqualTo(text.codePointCount(0, text.length).toLong())
    }

    @Test
    fun `date input retains input and change notifications`() {
        js("document.getElementById('date').focus(); events=[]")
        driver.inputText("2026-10-01")
        assertThat(js("return document.getElementById('date').value")).isEqualTo("2026-10-01")
        assertThat(js("return events.filter(e=>e.id==='date').map(e=>e.type)"))
            .isEqualTo(listOf("input", "change"))
    }

    @Test
    fun `selected target remains available after navigation`() {
        driver.contentDescriptor(false)
        driver.openLink("$origin/delayed", null, false, true)
        driver.contentDescriptor(false)
    }

    @Test
    fun `selected window switch resolves the active Selenium window`() {
        driver.contentDescriptor(false)
        val firstHandle = browser().windowHandle
        browser().switchTo().newWindow(org.openqa.selenium.WindowType.TAB)
        browser().get(origin)
        driver.contentDescriptor(false)

        browser().close()
        browser().switchTo().window(firstHandle)
        driver.contentDescriptor(false)
    }

    @Test
    fun `composition can commit before subsequent native typing`() {
        js("document.getElementById('text').focus(); events=[]")
        browser().executeCdpCommand("Input.imeSetComposition", mapOf("text" to "に", "selectionStart" to 1, "selectionEnd" to 1))
        browser().executeCdpCommand("Input.insertText", mapOf("text" to "日本"))
        driver.inputText("語")
        assertThat(js("return document.getElementById('text').value")).isEqualTo("日本語")
        assertThat(js("return events.some(e=>e.type==='compositionend')")).isEqualTo(true)
    }

    @Test
    fun `tap preserves hover before down up click and swipe preserves pressed movement`() {
        js("events=[]")
        driver.tap(center("button"))
        val types = js("return events.filter(e=>e.id==='button').map(e=>e.type)") as List<String>
        for ((before, after) in listOf("pointerover" to "pointerdown", "mouseover" to "mousedown",
            "pointerdown" to "pointerup", "mousedown" to "mouseup", "mouseup" to "click")) {
            assertThat(types.indexOf(before)).isAtLeast(0)
            assertThat(types.indexOf(before)).isLessThan(types.indexOf(after))
        }
        js("events=[]")
        driver.swipe(Point(400, 350), Point(550, 350), 150)
        assertThat(js("return events.some(e=>e.type==='pointermove' && e.buttons===1)")).isEqualTo(true)
        assertThat(js("return events.filter(e=>e.type==='pointerdown'||e.type==='pointerup').map(e=>e.type)"))
            .isEqualTo(listOf("pointerdown", "pointerup"))
    }

    @Test
    fun `tap uses actual scroll distance when the page clamps near its end`() {
        val point = js("""
            const viewportHeight = window.innerHeight;
            document.body.style.height = (viewportHeight + 300) + 'px';
            const maxScrollY = document.scrollingElement.scrollHeight - viewportHeight;
            window.scrollTo(0, maxScrollY - 200);
            const button = document.createElement('button');
            button.id = 'offscreen';
            button.textContent = 'Offscreen';
            button.style.position = 'absolute';
            button.style.left = '100px';
            button.style.top = (window.scrollY + viewportHeight + 30) + 'px';
            button.addEventListener('click', () => button.dataset.clicked = 'true');
            document.body.appendChild(button);
            const rect = button.getBoundingClientRect();
            return [Math.round(rect.x + rect.width / 2), Math.round(rect.y + rect.height / 2)];
        """.trimIndent()) as List<Number>

        assertThat(point[1].toInt()).isGreaterThan((js("return window.innerHeight") as Number).toInt())
        driver.tap(Point(point[0].toInt(), point[1].toInt()))

        assertThat((js("return window.scrollY") as Number).toDouble()).isGreaterThan(0.0)
        assertThat(js("return document.getElementById('offscreen').dataset.clicked")).isEqualTo("true")
    }

    @Test
    fun `tap scrolls to an offscreen target in quirks mode`() {
        val target = js("""
            document.open();
            document.write('<html><body style="margin:0;height:2000px"><button id="quirks-target" style="position:absolute;left:80px;top:900px;width:100px;height:40px">Target</button></body></html>');
            document.close();
            Object.defineProperty(document, 'scrollingElement', { configurable: true, get: () => null });
            const button = document.getElementById('quirks-target');
            button.addEventListener('click', () => button.dataset.clicked = 'true');
            const rect = button.getBoundingClientRect();
            return [Math.round(rect.x + rect.width / 2), Math.round(rect.y + rect.height / 2), document.compatMode, document.scrollingElement === null];
        """.trimIndent()) as List<*>

        assertThat(target[2]).isEqualTo("BackCompat")
        assertThat(target[3]).isEqualTo(true)
        val point = Point((target[0] as Number).toInt(), (target[1] as Number).toInt())
        driver.tap(point)

        assertThat(js("return document.getElementById('quirks-target').dataset.clicked")).isEqualTo("true")
    }

    @Test
    fun `tap skips completion wait when bottom clamp leaves no scroll`() {
        val viewportHeight = (js("document.body.style.height = '100vh'; window.scrollTo(0, document.scrollingElement.scrollHeight); return window.innerHeight") as Number).toInt()
        val startedAt = System.nanoTime()
        val failure = runCatching { driver.tap(Point(100, viewportHeight + 20)) }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(failure).isInstanceOf(org.openqa.selenium.interactions.MoveTargetOutOfBoundsException::class.java)
        assertThat(elapsedMs).isLessThan(2500L)
    }

    @Test
    fun `tap returns promptly when the browser cannot scroll`() {
        val viewportHeight = js("""
            document.body.style.height = '2000px';
            window.scrollTo(0, 0);
            window.originalScrollBy = window.scrollBy;
            window.scrollBy = () => {};
            return window.innerHeight;
        """.trimIndent()) as Number
        val startedAt = System.nanoTime()
        val failure = try {
            runCatching { driver.tap(Point(100, viewportHeight.toInt() + 20)) }.exceptionOrNull()
        } finally {
            js("window.scrollBy = window.originalScrollBy; delete window.originalScrollBy")
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(failure).isInstanceOf(org.openqa.selenium.interactions.MoveTargetOutOfBoundsException::class.java)
        assertThat(elapsedMs).isLessThan(2500L)
    }

    @Test
    fun `navigation waits for redirect document and bridge follows selected popup`() {
        driver.launchApp("$origin/redirect", emptyMap())
        assertThat(browser().currentUrl).isEqualTo("$origin/delayed")
        assertThat(driver.contentDescriptor().attributes["url"]).isEqualTo("$origin/delayed")
        js("window.open('$origin/popup', '_blank')")
        assertThat(driver.contentDescriptor().attributes["url"]).isEqualTo("$origin/popup")
        assertThat(driver.deviceInfo().widthPixels).isGreaterThan(0)
    }

    @Test
    fun `CDP evaluation awaits asynchronous operations`() = runBlocking {
        val options = browser().capabilities.getCapability("goog:chromeOptions") as Map<*, *>
        val address = (options["debuggerAddress"] as String).split(":")
        CdpClient(address[0], address[1].toInt()).use { client ->
            val target = client.listTargets().first { it.id == browser().windowHandle.removePrefix("CDwindow-") }
            assertThat(client.evaluate("window.innerHeight", target).toInt()).isGreaterThan(0)
            assertThat(client.evaluate("new Promise(resolve => setTimeout(() => resolve(42), 50))", target)).isEqualTo("42")
            assertThat(client.evaluate("undefined", target)).isEqualTo("null")
        }
    }

    @Test
    fun `new session has fresh profile cookies storage permissions and viewport`() {
        val firstProfile = (browser().capabilities.getCapability("chrome") as Map<*, *>)["userDataDir"]
        js("document.cookie='leak=yes'; localStorage.leak='yes'; sessionStorage.leak='yes'")
        browser().executeCdpCommand("Browser.grantPermissions", mapOf("origin" to origin, "permissions" to listOf("notifications")))
        browser().executeAsyncScript("""
            const done=arguments[0];
            const db=indexedDB.open('leak',1); db.onupgradeneeded=()=>db.result.createObjectStore('x');
            db.onsuccess=()=>{db.result.close(); caches.open('leak').then(()=>done(true));};
        """.trimIndent())
        driver.close()
        driver = CdpWebDriver(isStudio = false, isHeadless = true, screenSize = "1000x700")
        driver.open()
        driver.launchApp(origin, emptyMap())
        val secondProfile = (browser().capabilities.getCapability("chrome") as Map<*, *>)["userDataDir"]
        assertThat(firstProfile).isNotNull()
        assertThat(secondProfile).isNotEqualTo(firstProfile)
        assertThat(js("return [document.cookie,localStorage.getItem('leak'),sessionStorage.getItem('leak'),Notification.permission]"))
            .isEqualTo(listOf("", null, null, "default"))
        assertThat(browser().executeAsyncScript("const done=arguments[0]; Promise.all([indexedDB.databases(),caches.keys()]).then(done)"))
            .isEqualTo(listOf(emptyList<Any>(), emptyList<Any>()))
        assertThat(driver.deviceInfo().widthPixels).isEqualTo(1000)
    }
}
