package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TamaQuestStringFormatTest {
    @Test
    fun `quest completion strings use the expected format argument counts in both locales`() {
        val english = readQuestString("values/strings.xml")
        val spanish = readQuestString("values-es/strings.xml")

        assertEquals(1, countFormatArgs(requiredValue(english, "tama_quest_accept_success")))
        assertEquals(2, countFormatArgs(requiredValue(english, "tama_quest_finish_success")))
        assertEquals(4, countFormatArgs(requiredValue(english, "tama_quest_event_accepted")))
        assertEquals(4, countFormatArgs(requiredValue(english, "tama_quest_event_completed")))
        assertEquals(4, countFormatArgs(requiredValue(english, "tama_quest_reward_details")))

        assertEquals(1, countFormatArgs(requiredValue(spanish, "tama_quest_accept_success")))
        assertEquals(2, countFormatArgs(requiredValue(spanish, "tama_quest_finish_success")))
        assertEquals(4, countFormatArgs(requiredValue(spanish, "tama_quest_event_accepted")))
        assertEquals(4, countFormatArgs(requiredValue(spanish, "tama_quest_event_completed")))
        assertEquals(4, countFormatArgs(requiredValue(spanish, "tama_quest_reward_details")))
    }

    private fun readQuestString(relativePath: String): Map<String, String> {
        val candidates = listOf(
            File("app/src/main/res/$relativePath"),
            File("src/main/res/$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not locate $relativePath in any known res root.")
        val text = file.readText()
        val names = listOf(
            "tama_quest_accept_success",
            "tama_quest_finish_success",
            "tama_quest_event_accepted",
            "tama_quest_event_completed",
            "tama_quest_reward_details"
        )
        return names.associateWith { name ->
            Regex("""<string name="$name">(.*?)</string>""", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(text)
                ?.groupValues
                ?.get(1)
                ?: throw AssertionError("Missing string resource: $name in $relativePath")
        }
    }

    private fun countFormatArgs(value: String): Int {
        return Regex("""%\d+\$[ds]""").findAll(value).count()
    }

    private fun requiredValue(strings: Map<String, String>, key: String): String {
        return strings[key] ?: throw AssertionError("Missing expected key: $key")
    }
}
