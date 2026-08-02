package com.balancesentinel.app.data.console

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

internal fun inMemorySharedPreferences(): SharedPreferences {
    val values = linkedMapOf<String, Any?>()
    val preferences = mockk<SharedPreferences>()
    val editor = mockk<SharedPreferences.Editor>()

    every { preferences.getString(any(), any()) } answers {
        values[firstArg()] as? String ?: secondArg()
    }
    every { preferences.all } answers { values.toMap() }
    every { preferences.contains(any()) } answers { firstArg<String>() in values }
    every { preferences.edit() } returns editor

    every { editor.putString(any(), any()) } answers {
        val key = firstArg<String>()
        val value = secondArg<String?>()
        if (value == null) values.remove(key) else values[key] = value
        editor
    }
    every { editor.remove(any()) } answers {
        values.remove(firstArg<String>())
        editor
    }
    every { editor.clear() } answers {
        values.clear()
        editor
    }
    every { editor.apply() } just runs
    every { editor.commit() } returns true

    return preferences
}
