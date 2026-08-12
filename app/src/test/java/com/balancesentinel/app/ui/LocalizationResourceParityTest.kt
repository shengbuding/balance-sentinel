package com.balancesentinel.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourceParityTest {
    private val appDir: File = listOf(File("app"), File("."))
        .first { File(it, "src/main").isDirectory }

    @Test
    fun `default and english resources have identical keys and placeholder contracts`() {
        val defaults = contracts(File(appDir, "src/main/res/values/strings.xml"))
        val english = contracts(File(appDir, "src/main/res/values-en/strings.xml"))

        assertEquals("Default and English resource keys differ", defaults.keys, english.keys)
        assertEquals("Default and English placeholder contracts differ", defaults, english)
    }

    private fun contracts(file: File): Map<String, List<String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, List<String>>()
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val element = strings.item(index) as Element
            val name = element.getAttribute("name")
            result["string:$name"] = placeholders(element.textContent)
        }
        val plurals = document.getElementsByTagName("plurals")
        for (index in 0 until plurals.length) {
            val plural = plurals.item(index) as Element
            val name = plural.getAttribute("name")
            val items = plural.getElementsByTagName("item")
            val contracts = (0 until items.length).map { itemIndex ->
                val item = items.item(itemIndex) as Element
                placeholders(item.textContent)
            }
            assertEquals("Plural $name has inconsistent placeholders in ${file.path}", 1, contracts.distinct().size)
            result["plurals:$name"] = contracts.firstOrNull().orEmpty()
        }
        return result
    }

    private fun placeholders(value: String): List<String> = PLACEHOLDER
        .findAll(value)
        .mapIndexed { implicitIndex, match ->
            val index = match.groupValues[1].ifEmpty { (implicitIndex + 1).toString() }
            "$index:${match.groupValues[2]}"
        }
        .toList()

    private companion object {
        val PLACEHOLDER = Regex("%(?:(\\d+)\\$)?([a-zA-Z])")
    }
}
