/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingCategoriesTest {
    @Test
    fun acceptsCommaSemicolonAndWhitespaceSeparatedCategories() {
        assertEquals(
            listOf("RU", "private", "telegram"),
            parseRoutingCategories(" RU, private; telegram ", listOf("fallback")),
        )
    }

    @Test
    fun removesDuplicatesIgnoringCase() {
        assertEquals(
            listOf("CATEGORY-RU", "telegram"),
            parseRoutingCategories("CATEGORY-RU, category-ru, telegram", listOf("fallback")),
        )
    }

    @Test
    fun usesDefaultsForBlankInput() {
        assertEquals(
            listOf("RU"),
            parseRoutingCategories("  ", listOf("RU")),
        )
    }
}
