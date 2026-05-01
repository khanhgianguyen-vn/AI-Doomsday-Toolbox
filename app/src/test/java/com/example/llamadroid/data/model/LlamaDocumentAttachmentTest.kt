package com.example.llamadroid.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaDocumentAttachmentTest {

    @Test
    fun `document text is embedded with user text and can be extracted`() {
        val merged = mergeUserTextWithDocumentText(
            userText = "Summarize the attachment",
            documentName = "paper.pdf",
            documentText = "Alpha beta gamma.\nDelta epsilon."
        )

        assertTrue(merged.contains("Here is the document the user is refering to: paper.pdf"))
        assertTrue(merged.contains("[End of document]"))

        val document = extractEmbeddedDocumentText(merged)
        assertNotNull(document)
        assertEquals("paper.pdf", document!!.name)
        assertEquals("Alpha beta gamma.\nDelta epsilon.", document.text)
        assertEquals("Summarize the attachment", stripEmbeddedDocumentText(merged))
    }

    @Test
    fun `token estimate includes embedded document text`() {
        val typedOnly = "Summarize this"
        val withDocument = mergeUserTextWithDocumentText(
            userText = typedOnly,
            documentName = "dataset.txt",
            documentText = "one two three four five six seven eight nine ten"
        )

        assertTrue(estimateNativeChatTextTokens(withDocument) > estimateNativeChatTextTokens(typedOnly))
    }

    @Test
    fun `document and audio metadata can both be stripped for visible user text`() {
        val withDocument = mergeUserTextWithDocumentText(
            userText = "Use this source",
            documentName = "notes.md",
            documentText = "Important details"
        )
        val withAudio = mergeUserTextWithAudioTranscript(
            userText = withDocument,
            transcript = "Extra spoken instruction"
        )

        val visibleText = stripEmbeddedDocumentText(stripEmbeddedAudioTranscript(withAudio))

        assertEquals("Use this source", visibleText)
        assertEquals("Extra spoken instruction", extractEmbeddedAudioTranscript(withAudio))
        assertEquals("notes.md", extractEmbeddedDocumentText(withAudio)?.name)
    }
}
