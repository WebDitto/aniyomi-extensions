package eu.kanade.tachiyomi.lib.hunterdecoder

import kotlin.math.pow

/*
 * Copyright (C) The Tachiyomi Open Source Project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

/**
 * Helper class to unpack JavaScript code compressed by [hunter](https://github.com/nicxlau/hunter-php-javascript-obfuscator).
 *
 * Source code of hunder can be found [here](https://github.com/nicxlau/hunter-php-javascript-obfuscator).
 */
object HunterDecoder {
    private fun convertToNum(thing: String, limit: Float): Int {
        return thing.split("")
            .reversed()
            .map { it.toIntOrNull() ?: 0 }
            .reduceIndexed { index: Int, acc, num ->
                acc + (num * limit.pow(index - 1)).toInt()
            }
    }

    fun decodeScript(encodedString: String, magicStr: String, offset: Int, limit: Int): String {
        val regex = "\\w".toRegex()
        return encodedString
            .split(magicStr[limit])
            .dropLast(1)
            .map { str ->
                val replaced = regex.replace(str) { magicStr.indexOf(it.value).toString() }
                val charInt = convertToNum(replaced, limit.toFloat()) - offset
                Char(charInt)
            }.joinToString("")
    }

    fun decode(content: String): String? {
        val script = content.replace("\\s".toRegex(), "")

        val regex = """\}\("(\w+)",.*?"(\w+)",(\d+),(\d+),.*?\)""".toRegex()
        return regex.find(script)
            ?.run {
                decodeScript(
                    groupValues[1], // encoded data
                    groupValues[2], // magic string
                    groupValues[3].toIntOrNull() ?: 0, // offset
                    groupValues[4].toIntOrNull() ?: 0, // limit
                )
            }
    }
}
