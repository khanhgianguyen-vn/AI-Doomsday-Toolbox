package com.example.llamadroid.service

import com.example.llamadroid.data.db.NoteDao
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatToolsTest {
    @Test
    fun `config defaults keep tools disabled`() {
        val config = NativeChatToolConfig.fromApiParams(null)

        assertFalse(config.toolsEnabled)
        assertFalse(config.imageIterationEnabled)
        assertFalse(config.hasEnabledTools())
        assertEquals(12, config.maxToolRounds)
    }

    @Test
    fun `config round trip preserves enabled tools and coerces limits`() {
        val original = NativeChatToolConfig(
            toolsEnabled = true,
            webSearchEnabled = true,
            webSearchMaxPages = 99,
            webSearchMaxChars = 99,
            kiwixSearchEnabled = true,
            kiwixServerUrl = "http://127.0.0.1:8888/",
            fetchUrlEnabled = true,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            calendarToolsEnabled = true,
            alarmToolsEnabled = true,
            imageGenerationEnabled = true,
            imageIterationEnabled = true,
            imageParams = NativeChatImageToolParams(
                model = "sd15.onnx",
                width = 768,
                height = 512,
                steps = 28,
                cfgScale = 7.25f,
                seed = "42",
                negativePrompt = "blur",
                backend = OnnxRuntimeBackend.NNAPI,
                runtimeThreads = 6,
                graphOptimizationLevel = OnnxGraphOptimizationLevel.BASIC,
                unetBackendOverride = OnnxBackendOverride.CPU,
                vaeDecoderBackendOverride = OnnxBackendOverride.NNAPI,
                vaeEncoderBackendOverride = OnnxBackendOverride.CPU,
                intraOpThreads = 3,
                interOpThreads = 2,
                executionMode = OnnxExecutionMode.PARALLEL,
                memoryPatternOptimization = false,
                cpuArenaAllocator = false,
                nnapiCpuDisabled = false,
                nnapiUseFp16 = true
            ),
            maxToolRounds = 99
        )

        val restored = NativeChatToolConfig.fromApiParams(JSONObject(original.toParamMap()).toString())

        assertTrue(restored.toolsEnabled)
        assertTrue(restored.webSearchEnabled)
        assertTrue(restored.kiwixSearchEnabled)
        assertTrue(restored.fetchUrlEnabled)
        assertTrue(restored.noteToolsEnabled)
        assertTrue(restored.todoToolsEnabled)
        assertTrue(restored.calendarToolsEnabled)
        assertTrue(restored.alarmToolsEnabled)
        assertTrue(restored.imageGenerationEnabled)
        assertTrue(restored.imageIterationEnabled)
        assertEquals(NativeChatToolConfig.MAX_SEARCH_PAGES, restored.webSearchMaxPages)
        assertEquals(NativeChatToolConfig.MIN_PAGE_CHARS, restored.webSearchMaxChars)
        assertEquals(NativeChatToolConfig.MAX_TOOL_ROUNDS, restored.maxToolRounds)
        assertEquals("http://127.0.0.1:8888", restored.kiwixServerUrl)
        assertEquals("sd15.onnx", restored.imageParams.model)
        assertEquals(768, restored.imageParams.width)
        assertEquals(512, restored.imageParams.height)
        assertEquals(28, restored.imageParams.steps)
        assertEquals(7.25f, restored.imageParams.cfgScale, 0.0001f)
        assertEquals("42", restored.imageParams.seed)
        assertEquals("blur", restored.imageParams.negativePrompt)
        assertEquals(OnnxRuntimeBackend.NNAPI, restored.imageParams.backend)
        assertEquals(6, restored.imageParams.runtimeThreads)
        assertEquals(OnnxGraphOptimizationLevel.BASIC, restored.imageParams.graphOptimizationLevel)
        assertEquals(OnnxBackendOverride.CPU, restored.imageParams.unetBackendOverride)
        assertEquals(OnnxBackendOverride.NNAPI, restored.imageParams.vaeDecoderBackendOverride)
        assertEquals(OnnxBackendOverride.CPU, restored.imageParams.vaeEncoderBackendOverride)
        assertEquals(3, restored.imageParams.intraOpThreads)
        assertEquals(2, restored.imageParams.interOpThreads)
        assertEquals(OnnxExecutionMode.PARALLEL, restored.imageParams.executionMode)
        assertFalse(restored.imageParams.memoryPatternOptimization)
        assertFalse(restored.imageParams.cpuArenaAllocator)
        assertFalse(restored.imageParams.nnapiCpuDisabled)
        assertTrue(restored.imageParams.nnapiUseFp16)
    }

    @Test
    fun `available tools respect enabled config`() {
        val runtime = NativeChatToolRuntime()
        val tools = runtime.availableTools(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = true,
                calculatorEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = true,
                imageGenerationEnabled = true
            )
        ).map { it.name }.toSet()

        assertTrue(NativeChatToolRuntime.TOOL_WEB_SEARCH in tools)
        assertTrue(NativeChatToolRuntime.TOOL_SEARCH_PAGE in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CALCULATOR in tools)
        assertTrue(NativeChatToolRuntime.TOOL_LIST_NOTES in tools)
        assertTrue(NativeChatToolRuntime.TOOL_READ_NOTE in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_NOTE in tools)
        assertTrue(NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_TODO_LIST in tools)
        assertTrue(NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED in tools)
        assertTrue(NativeChatToolRuntime.TOOL_LIST_CALENDAR_EVENTS in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT in tools)
        assertTrue(NativeChatToolRuntime.TOOL_LIST_ALARMS in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_ALARM in tools)
        assertTrue(NativeChatToolRuntime.TOOL_GENERATE_IMAGE in tools)
        assertFalse(NativeChatToolRuntime.TOOL_KIWIX_SEARCH in tools)
    }

    @Test
    fun `fetch url policy allows localhost while blocking other private addresses`() {
        assertEquals(null, blockedNativeFetchUrlReason("http://127.0.0.1:8888/wiki/Main_Page"))
        assertEquals(null, blockedNativeFetchUrlReason("http://localhost:8888/wiki/Main_Page"))
        assertEquals(null, blockedNativeFetchUrlReason("http://[::1]:8888/wiki/Main_Page"))
        assertNotNull(blockedNativeFetchUrlReason("http://192.168.1.20/wiki/Main_Page"))
        assertNotNull(blockedNativeFetchUrlReason("file:///tmp/wiki.zim"))
        assertEquals(null, blockedKiwixBaseUrlReason("http://127.0.0.1:8888"))
        assertEquals(null, blockedKiwixBaseUrlReason("http://localhost:8888"))
        assertNotNull(blockedKiwixBaseUrlReason("file:///tmp/wiki.zim"))
    }

    @Test
    fun `calculator evaluates arithmetic without code eval`() {
        assertEquals(14.0, evaluateNativeCalculatorExpression("2 + 3 * 4"), 0.000001)
        assertEquals(512.0, evaluateNativeCalculatorExpression("2^3^2"), 0.000001)
    }

    @Test
    fun `todo parser accepts markdown lines and formats normalized task list`() {
        val items = parseNativeTodoItemsFromToolInput(
            """
            - [ ] Buy rice
            - [x] Start Kiwix
            """.trimIndent()
        )

        assertEquals(listOf(false, true), items.map { it.checked })
        assertEquals(listOf("Buy rice", "Start Kiwix"), items.map { it.text })
        assertEquals("- [ ] Buy rice\n- [x] Start Kiwix", formatNativeTodoItems(items))
    }

    @Test
    fun `todo parser accepts json arrays`() {
        val items = parseNativeTodoItemsFromToolInput(
            """[{"text":"One","checked":true},"Two"]"""
        )

        assertEquals(2, items.size)
        assertEquals(NativeTodoItem("One", true), items[0])
        assertEquals(NativeTodoItem("Two", false), items[1])
    }

    @Test
    fun `tool note preview strips todo checkbox markdown`() {
        val preview = markdownPreviewForTool("- [x] Send documents\n- [ ] Call team", 120)

        assertEquals("Send documents Call team", preview)
    }

    @Test
    fun `search summary skips non text content and stays compact`() {
        assertTrue(isNativeToolReadableContentType("application/pdf"))
        assertTrue(isNativeToolPdfContentType("application/pdf; charset=binary"))
        assertTrue(isNativeToolReadableContentType("text/html; charset=utf-8"))
        assertTrue(looksLikeNativeNonTextContent("%PDF-1.6\n1 0 obj"))

        val skipped = summarizeNativeSearchTextForTool(nativeToolTextContentSkippedMessage("image/png"))
        assertTrue(skipped.contains("non_text_content_skipped"))
        assertFalse(skipped.contains("obj"))

        val summary = summarizeNativeSearchTextForTool(
            """
            Cookie settings
            Gemma models are lightweight open models for text generation. They can run with llama.cpp or Ollama on local devices.
            Tool calling quality depends on the server template and the model instruction tuning. Compact summaries reduce context pressure.
            Fetching a full page is still useful when the assistant needs exact details from one source.
            """.trimIndent(),
            maxChars = 220
        )
        assertTrue(summary.contains("Gemma models"))
        assertTrue(summary.length <= 232)
    }

    @Test
    fun `fetch url skips oversized pdf before parsing`() = runBlocking {
        val oversizedPdf = ByteArray(4 * 1024 * 1024) { '%'.code.toByte() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(oversizedPdf.toResponseBody("application/pdf".toMediaType()))
                    .build()
            }
            .build()
        val runtime = NativeChatToolRuntime(
            clientFactory = { client },
            pdfTextExtractor = { _, _ -> error("Oversized PDFs should be skipped before parsing.") }
        )

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_FETCH_URL,
                mapOf("url" to "https://example.com/large.pdf")
            ),
            config = NativeChatToolConfig(toolsEnabled = true, fetchUrlEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("pdf_skipped"))
    }

    @Test
    fun `search page finds matching page links and snippets`() = runBlocking {
        val html = """
            <html>
              <body>
                <nav>
                  <a href="/ggerganov/llama.cpp">Code</a>
                  <a href="/ggerganov/llama.cpp/commits/master" aria-label="Commits history">Commits</a>
                  <a href="/ggerganov/llama.cpp/releases">Releases</a>
                </nav>
                <main>
                  The latest commits page contains changes to llama.cpp server handling, tool call parsing, and docs.
                </main>
              </body>
            </html>
        """.trimIndent()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val runtime = NativeChatToolRuntime(clientFactory = { client })

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_SEARCH_PAGE,
                mapOf(
                    "url" to "https://github.com/ggerganov/llama.cpp",
                    "query" to "commits",
                    "max_links" to "5"
                )
            ),
            config = NativeChatToolConfig(toolsEnabled = true, webSearchEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("tool: search_page"))
        assertTrue(result.contains("https://github.com/ggerganov/llama.cpp/commits/master"))
        assertTrue(result.contains("latest commits page"))
        assertFalse(result.contains("https://github.com/ggerganov/llama.cpp/releases\n"))
    }

    @Test
    fun `replace note text supports first all case sensitivity and no match`() {
        assertEquals(
            NativeNoteTextReplacement("one TWO two", 1),
            replaceNativeNoteText("one two two", "two", "TWO", replaceAll = false, caseSensitive = true)
        )
        assertEquals(
            NativeNoteTextReplacement("one TWO TWO", 2),
            replaceNativeNoteText("one two two", "two", "TWO", replaceAll = true, caseSensitive = true)
        )
        assertEquals(
            NativeNoteTextReplacement("X X", 2),
            replaceNativeNoteText("Todo todo", "todo", "X", replaceAll = true, caseSensitive = false)
        )
        assertEquals(
            NativeNoteTextReplacement("Todo todo", 0),
            replaceNativeNoteText("Todo todo", "missing", "X", replaceAll = true, caseSensitive = true)
        )
    }

    @Test
    fun `note tools only expose and edit whitelisted notes`() = runBlocking {
        val noteDao = FakeNoteDao(
            listOf(
                NoteEntity(
                    id = 1,
                    title = "Allowed",
                    content = "Call Alice",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = true
                ),
                NoteEntity(
                    id = 2,
                    title = "Private",
                    content = "hidden",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = false
                )
            )
        )
        val runtime = NativeChatToolRuntime(noteDao = noteDao)
        val config = NativeChatToolConfig(toolsEnabled = true, noteToolsEnabled = true, todoToolsEnabled = true)

        val listResult = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(NativeChatToolRuntime.TOOL_LIST_NOTES, emptyMap()),
            config = config
        ).getOrThrow().content
        assertTrue(listResult.contains("Allowed"))
        assertFalse(listResult.contains("Private"))

        val blockedRead = runtime.executeToolCall(
            OllamaService.ToolCall(NativeChatToolRuntime.TOOL_READ_NOTE, mapOf("note_id" to "2")),
            config
        )
        assertTrue(blockedRead.isFailure)

        val replaceResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT,
                mapOf("note_id" to "1", "find_text" to "Alice", "replacement_text" to "Bob")
            ),
            config
        ).getOrThrow().content

        assertTrue(replaceResult.contains("replacements: 1"))
        assertEquals("Call Bob", noteDao.notes.getValue(1).content)
    }

    @Test
    fun `create note tool creates whitelisted organizer note`() = runBlocking {
        val noteDao = FakeNoteDao(emptyList())
        val runtime = NativeChatToolRuntime(noteDao = noteDao)

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_CREATE_NOTE,
                mapOf("title" to "Tech news", "content" to "Summary with sources")
            ),
            config = NativeChatToolConfig(toolsEnabled = true, noteToolsEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("note_id: 1"))
        val note = noteDao.notes.getValue(1)
        assertEquals("Tech news", note.title)
        assertEquals("Summary with sources", note.content)
        assertEquals(NoteType.MANUAL, note.type)
        assertTrue(note.isLlmWhitelisted)
    }

    @Test
    fun `note tools accept common argument aliases and return updated state`() = runBlocking {
        val noteDao = FakeNoteDao(
            listOf(
                NoteEntity(
                    id = 10,
                    title = "Research",
                    content = "Alpha\nBeta",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = true
                ),
                NoteEntity(
                    id = 11,
                    title = "Tasks",
                    content = "- [ ] send docs\n- [ ] call Ana",
                    type = NoteType.TODO_LIST,
                    isLlmWhitelisted = true
                )
            )
        )
        val runtime = NativeChatToolRuntime(noteDao = noteDao)
        val config = NativeChatToolConfig(toolsEnabled = true, noteToolsEnabled = true, todoToolsEnabled = true)

        val updateResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_UPDATE_NOTE,
                mapOf("id" to "10.0", "appendText" to "Gamma")
            ),
            config
        ).getOrThrow().content
        assertTrue(updateResult.contains("preview:"))
        assertEquals("Alpha\nBeta\n\nGamma", noteDao.notes.getValue(10).content)

        val replaceResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT,
                mapOf(
                    "noteId" to "10",
                    "oldText" to "beta",
                    "newText" to "Delta",
                    "replaceAll" to "yes",
                    "matchCase" to "false"
                )
            ),
            config
        ).getOrThrow().content
        assertTrue(replaceResult.contains("replacements: 1"))
        assertEquals("Alpha\nDelta\n\nGamma", noteDao.notes.getValue(10).content)

        val checkedResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED,
                mapOf("id" to "11", "index" to "1.0", "done" to "completed")
            ),
            config
        ).getOrThrow().content
        assertTrue(checkedResult.contains("1. [x] send docs"))
        assertTrue(noteDao.notes.getValue(11).content.startsWith("- [x] send docs"))

        val removeResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_REMOVE_TODO_ITEM,
                mapOf("noteId" to "11", "position" to "2")
            ),
            config
        ).getOrThrow().content
        assertTrue(removeResult.contains("removed: call Ana"))
        assertEquals("- [x] send docs", noteDao.notes.getValue(11).content)
    }
}

private class FakeNoteDao(initialNotes: List<NoteEntity>) : NoteDao {
    val notes: MutableMap<Int, NoteEntity> = initialNotes.associateBy { it.id }.toMutableMap()
    private var nextId: Int = (notes.keys.maxOrNull() ?: 0) + 1

    override fun getAllNotes(): Flow<List<NoteEntity>> = flowOf(notes.values.sortedByDescending { it.updatedAt })

    override suspend fun getAllNotesOnce(): List<NoteEntity> = notes.values.sortedByDescending { it.updatedAt }

    override fun getNotesByType(type: NoteType): Flow<List<NoteEntity>> =
        flowOf(notes.values.filter { it.type == type }.sortedByDescending { it.updatedAt })

    override fun searchNotes(query: String): Flow<List<NoteEntity>> =
        flowOf(
            notes.values.filter {
                it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
            }.sortedByDescending { it.updatedAt }
        )

    override suspend fun getNoteById(id: Int): NoteEntity? = notes[id]

    override suspend fun insert(note: NoteEntity): Long {
        val id = if (note.id == 0) nextId++ else note.id
        notes[id] = note.copy(id = id)
        return id.toLong()
    }

    override suspend fun update(note: NoteEntity) {
        notes[note.id] = note
    }

    override suspend fun delete(note: NoteEntity) {
        notes.remove(note.id)
    }

    override suspend fun deleteById(id: Int) {
        notes.remove(id)
    }

    override suspend fun deleteByIds(ids: List<Int>) {
        ids.forEach { notes.remove(it) }
    }

    override suspend fun setLlmWhitelisted(ids: List<Int>, allowed: Boolean) {
        ids.forEach { id ->
            notes[id]?.let { notes[id] = it.copy(isLlmWhitelisted = allowed) }
        }
    }

    override fun getNoteCount(): Flow<Int> = flowOf(notes.size)

    override fun getNoteCountByType(type: NoteType): Flow<Int> = flowOf(notes.values.count { it.type == type })
}
