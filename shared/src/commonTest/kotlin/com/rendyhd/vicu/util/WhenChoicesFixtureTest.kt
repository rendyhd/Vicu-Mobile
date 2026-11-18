package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every `whenChoices` vector of cross-app-semantics-v1.json (section 22), in the contract's zones.
 * The desktop runs the same (`when-choices.test.ts`).
 */
class WhenChoicesFixtureTest {
    private val vectors = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("whenChoices").jsonObject

    private val zones = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam").map(TimeZone::of)

    private fun list(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun timesOf(element: JsonElement): List<TimeOfDay> = element.jsonArray.map {
        val obj = it.jsonObject
        TimeOfDay(obj.getValue("name").jsonPrimitive.content, WhenChoices.parseTime(obj.getValue("time").jsonPrimitive.content)!!)
    }

    private fun keys(element: JsonElement): List<String> = element.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the defaults are the contract's`() {
        assertEquals(timesOf(vectors.getValue("defaultTimes")), WhenChoices.DEFAULT_TIMES)
        assertEquals(keys(vectors.getValue("defaultDays")), WhenChoices.DEFAULT_DAYS.map { it.key })
        assertEquals(keys(vectors.getValue("defaultMoveBy")), WhenChoices.DEFAULT_MOVE_BY.map { it.key })
    }

    @Test
    fun readTimes() {
        for (v in list("readTimes")) {
            assertEquals(timesOf(v.getValue("expect")), WhenChoices.readTimes(v["value"]), v.text("name"))
        }
    }

    @Test
    fun readDaysAndMoveBy() {
        for (v in list("readDays")) {
            assertEquals(keys(v.getValue("expect")), WhenChoices.readDays(v["value"]).map { it.key }, v.text("name"))
        }
        for (v in list("readMoveBy")) {
            assertEquals(keys(v.getValue("expect")), WhenChoices.readMoveBy(v["value"]).map { it.key }, v.text("name"))
        }
    }

    @Test
    fun morningEvening() {
        for (v in list("morningEvening")) {
            val times = timesOf(v.getValue("times"))
            assertEquals(v.text("morning"), WhenChoices.morningTime(times)?.let(WhenChoices::formatTime))
            assertEquals(v.text("evening"), WhenChoices.eveningTime(times)?.let(WhenChoices::formatTime))
        }
    }

    @Test
    fun days() {
        for (v in list("days")) {
            val now = LocalDateTime.parse(v.getValue("now").jsonPrimitive.content)
            val times = v["times"]?.let(::timesOf) ?: WhenChoices.DEFAULT_TIMES
            val weekStart = WeekStart.fromKey(v.text("weekStart") ?: "monday")
            val ids = keys(v.getValue("ids")).mapNotNull(DayChoiceId::of)
            val expected = v.getValue("expect").jsonArray.map {
                val e = it.jsonObject
                Triple(e.text("id"), e.text("day"), e.text("time"))
            }
            val actual = WhenChoices.days(ids, now, times, weekStart).map {
                Triple(it.id.key, it.date.toString(), it.time?.let(WhenChoices::formatTime))
            }
            assertEquals(expected, actual, v.text("name"))
        }
    }

    @Test
    fun moveBy() {
        for (zone in zones) {
            for (v in list("moveBy")) {
                val due = LocalDateTime.parse(v.getValue("due").jsonPrimitive.content).toInstant(zone)
                val today = LocalDate.parse(v.getValue("today").jsonPrimitive.content)
                val step = MoveStep.of(v.getValue("step").jsonPrimitive.content)!!
                val moved = WhenChoices.moveBy(due, step, today, zone).toLocalDateTime(zone)
                assertEquals(LocalDateTime.parse(v.getValue("expect").jsonPrimitive.content), moved, "${v.text("name")} in $zone")
            }
        }
    }
}
