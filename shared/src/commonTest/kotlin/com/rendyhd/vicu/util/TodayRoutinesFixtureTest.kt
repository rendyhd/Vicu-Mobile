package com.rendyhd.vicu.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `todayRoutines` vectors of cross-app-semantics-v1.json (section 6.10). */
class TodayRoutinesFixtureTest {
    private val block = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("todayRoutines").jsonObject

    private data class Item(val routine: String, val name: String, val minutes: Int, val status: String, val carried: Boolean)

    private fun minutesOf(time: String): Int = time.substringBefore(':').toInt() * 60 + time.substringAfter(':').toInt()

    private fun timeOf(minutes: Int): String = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    @Test
    fun constantsMatch() {
        assertEquals(block.getValue("soonMinutes").jsonPrimitive.int, TodayRoutines.SOON_MINUTES)
        assertEquals(block.getValue("defaultLimit").jsonPrimitive.int, TodayRoutines.DEFAULT_LIMIT)
    }

    @Test
    fun everyCase() {
        for (case in block.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val items = case.getValue("occurrences").jsonArray.map { it.jsonObject }.map {
                Item(
                    routine = it.getValue("routine").jsonPrimitive.content,
                    name = it.getValue("name").jsonPrimitive.content,
                    minutes = minutesOf(it.getValue("time").jsonPrimitive.content),
                    status = it.getValue("status").jsonPrimitive.content,
                    carried = it["carried"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            val limit = case.getValue("limit").jsonPrimitive.intOrNull ?: TodayRoutines.ALL
            val plan = TodayRoutines.plan(
                items = items,
                nowMinutes = minutesOf(case.getValue("now").jsonPrimitive.content),
                limit = limit,
                routineId = { it.routine },
                name = { it.name },
                minutes = { it.minutes },
                finished = { it.status != "open" },
                carried = { it.carried },
            )
            val rows = case.getValue("rows").jsonArray.map { it.jsonObject }.map { row ->
                Triple(
                    row.getValue("routine").jsonPrimitive.content,
                    row.getValue("time").jsonPrimitive.content,
                    (row.getValue("progress") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                )
            }
            assertEquals(rows, plan.rows.map { Triple(it.routineId, timeOf(it.minutes), it.progress) }, name)
            assertEquals(case.getValue("visible").jsonArray.map { it.jsonPrimitive.content }, plan.visible.map { it.routineId }, name)
            val line = case.getValue("line")
            if (line is JsonObject) {
                assertEquals(line.getValue("count").jsonPrimitive.int, plan.hidden.size, name)
                assertEquals(line.getValue("text").jsonPrimitive.content, plan.lineText, name)
            } else {
                assertNull(plan.lineText, name)
            }
        }
    }
}
