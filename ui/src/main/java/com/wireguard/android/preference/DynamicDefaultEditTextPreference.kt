/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference

/** An editable preference whose unstored default can change between application builds. */
class DynamicDefaultEditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.editTextPreferenceStyle,
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {
    private var dynamicDefault = ""

    fun setDynamicDefault(value: String) {
        dynamicDefault = value
        notifyChanged()
    }

    override fun getText(): String = super.getText()?.takeIf { it.isNotBlank() } ?: dynamicDefault

    override fun persistString(value: String?): Boolean {
        val normalized = value?.trim().orEmpty()
        return super.persistString(if (normalized == dynamicDefault) "" else normalized)
    }
}
