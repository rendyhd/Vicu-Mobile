package com.rendyhd.vicu.location

/**
 * The street line of an address in the order of the country the place is in: "Keizersgracht 12"
 * in the Netherlands, "123 Main Street" in the United States. The order follows the place, not the
 * phone's language, so a trip abroad shows that country's addresses as they are written there.
 */
object StreetAddress {
    /**
     * ISO 3166-1 alpha-2 codes of the countries that write the house number before the street.
     * Every other country (most of Europe and Latin America) writes it after.
     */
    private val NUMBER_FIRST = setOf(
        "AU", "CA", "FR", "GB", "HK", "IE", "IN", "LU", "MC", "MY", "NZ", "PH", "SG", "US", "ZA",
    )

    /** [street] and [number] joined for [countryCode]; an unknown country puts the number after. */
    fun line(street: String?, number: String?, countryCode: String?): String {
        val parts = listOf(street.orEmpty().trim(), number.orEmpty().trim())
            .let { if (numberFirst(countryCode)) it.reversed() else it }
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    fun numberFirst(countryCode: String?): Boolean = countryCode?.trim()?.uppercase() in NUMBER_FIRST
}
