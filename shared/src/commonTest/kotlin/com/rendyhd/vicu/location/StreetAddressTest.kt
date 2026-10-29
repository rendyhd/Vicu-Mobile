package com.rendyhd.vicu.location

import kotlin.test.Test
import kotlin.test.assertEquals

class StreetAddressTest {

    @Test
    fun `the number goes first where the country writes it first`() {
        assertEquals("123 Main Street", StreetAddress.line("Main Street", "123", "US"))
        assertEquals("10 Downing Street", StreetAddress.line("Downing Street", "10", "gb"))
        assertEquals("5 rue de Rivoli", StreetAddress.line("rue de Rivoli", "5", "FR"))
    }

    @Test
    fun `the number goes after elsewhere and when the country is unknown`() {
        assertEquals("Keizersgracht 12", StreetAddress.line("Keizersgracht", "12", "NL"))
        assertEquals("Unter den Linden 1", StreetAddress.line("Unter den Linden", "1", "DE"))
        assertEquals("Keizersgracht 12", StreetAddress.line("Keizersgracht", "12", null))
    }

    @Test
    fun `a missing part leaves the other alone`() {
        assertEquals("Main Street", StreetAddress.line("Main Street", null, "US"))
        assertEquals("Main Street", StreetAddress.line(" Main Street ", " ", "US"))
        assertEquals("", StreetAddress.line(null, null, "US"))
    }
}
