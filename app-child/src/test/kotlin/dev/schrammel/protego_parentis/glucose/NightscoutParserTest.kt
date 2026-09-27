package dev.schrammel.protego_parentis.glucose

import dev.schrammel.protego_parentis.model.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Locale

class NightscoutParserTest {

    @Test
    fun `the newest sgv entry is the reading`() {
        val body = """
            [
              {"type":"sgv","sgv":110,"date":1790000000000,"direction":"Flat"},
              {"type":"sgv","sgv":98,"date":1790000300000,"direction":"FortyFiveDown"}
            ]
        """.trimIndent()

        assertEquals(GlucoseReading(98, Instant.ofEpochMilli(1790000300000)), NightscoutParser.latestReading(body))
    }

    @Test
    fun `meter readings, implausible values and garbage are not readings`() {
        assertNull(NightscoutParser.latestReading("""[{"type":"mbg","mbg":90,"date":1790000000000}]"""))
        assertNull(NightscoutParser.latestReading("""[{"type":"sgv","sgv":5,"date":1790000000000}]"""))
        assertNull(NightscoutParser.latestReading("""[{"type":"sgv","sgv":120}]"""))
        assertNull(NightscoutParser.latestReading("[]"))
        assertNull(NightscoutParser.latestReading("<html>Unauthorized</html>"))
    }

    @Test
    fun `units follow nightscout's own mmol test`() {
        assertEquals(GlucoseUnit.MMOL_L, NightscoutParser.units("""{"status":"ok","settings":{"units":"mmol"}}"""))
        assertEquals(GlucoseUnit.MMOL_L, NightscoutParser.units("""{"settings":{"units":"mmol/L"}}"""))
        assertEquals(GlucoseUnit.MG_DL, NightscoutParser.units("""{"settings":{"units":"mg/dl"}}"""))
        assertNull(NightscoutParser.units("""{"status":"ok"}"""))
        assertNull(NightscoutParser.units("not json"))
    }

    @Test
    fun `mmol values format and parse the way a swede types them`() {
        assertEquals("4,0", GlucoseUnit.MMOL_L.format(72, Locale.forLanguageTag("sv-SE")))
        assertEquals("4.0", GlucoseUnit.MMOL_L.format(72, Locale.US))
        assertEquals(72, GlucoseUnit.MMOL_L.parse("4,0"))
        assertEquals(72, GlucoseUnit.MMOL_L.parse(" 4.0 "))
        assertEquals(180, GlucoseUnit.MG_DL.parse("180"))
    }

    @Test
    fun `nonsense or impossible levels do not parse`() {
        assertNull(GlucoseUnit.MMOL_L.parse("low"))
        assertNull(GlucoseUnit.MMOL_L.parse("0,5"))
        assertNull(GlucoseUnit.MG_DL.parse("900"))
    }
}
