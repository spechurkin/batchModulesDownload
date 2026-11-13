package proj.dnd

import com.google.gson.Gson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleDataTest {
    private val gson = Gson()

    @Test
    fun `parses all manifest fields and ignores unknown metadata`() {
        val module = gson.fromJson(
            """{
                "id": "sample",
                "name": "legacy-name",
                "title": "Sample module",
                "download": "https://example.com/module.zip",
                "compatibility": {"minimum": "12"},
                "authors": [{"name": "Author"}]
            }""",
            ModuleData::class.java
        )

        assertEquals(
            ModuleData("sample", "legacy-name", "Sample module", "https://example.com/module.zip"),
            module
        )
    }

    @Test
    fun `optional fields can be absent`() {
        val module = gson.fromJson("""{"title":"Minimal module"}""", ModuleData::class.java)

        assertEquals("Minimal module", module.title)
        assertNull(module.id)
        assertNull(module.name)
        assertNull(module.download)
    }

    @Test
    fun `accepts explicit null optional fields and unicode title`() {
        val module = gson.fromJson(
            """{"id":null,"name":null,"title":"Модуль 🎲","download":null}""",
            ModuleData::class.java
        )

        assertEquals(ModuleData(title = "Модуль 🎲"), module)
    }
}
