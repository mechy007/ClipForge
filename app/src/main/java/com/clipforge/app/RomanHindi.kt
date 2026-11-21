package com.clipforge.app

/** Offline phonetic spelling for Hindi captions. English and Malayalam text pass through. */
internal object RomanHindi {
    private val hindiWord = Regex("[\\u0900-\\u0963\\u0972-\\u097f]+")
    private val vowels = "अआइईउऊऋएऐओऔ".zip(listOf("a", "aa", "i", "ee", "u", "oo", "ri", "e", "ai", "o", "au")).toMap()
    private val marks = "ािीुूृेैोौ".zip(listOf("aa", "i", "ee", "u", "oo", "ri", "e", "ai", "o", "au")).toMap()
    private val consonants = "कखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसह".zip(
        listOf("k", "kh", "g", "gh", "ng", "ch", "chh", "j", "jh", "ny", "t", "th", "d", "dh", "n",
            "t", "th", "d", "dh", "n", "p", "ph", "b", "bh", "m", "y", "r", "l", "v", "sh", "sh", "s", "h")
    ).toMap()
    private val nukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")

    fun apply(text: String): String = hindiWord.replace(text) { match ->
        val word = java.text.Normalizer.normalize(match.value, java.text.Normalizer.Form.NFD)
        buildString {
            var index = 0
            while (index < word.length) {
                val letter = word[index++]
                val consonant = consonants[letter]
                if (consonant != null) {
                    if (word.getOrNull(index) == '़') {
                        append(nukta[letter] ?: consonant)
                        index++
                    } else append(consonant)
                    val next = word.getOrNull(index)
                    when {
                        next == '्' -> index++
                        next != null && marks.containsKey(next) -> append(marks.getValue(word[index++]))
                        next != null || length == consonant.length -> append('a')
                    }
                } else {
                    append(vowels[letter] ?: when (letter) {
                        'ं', 'ँ' -> if (word.getOrNull(index) in listOf('प', 'फ', 'ब', 'भ', 'म')) "m" else "n"
                        'ः' -> "h"
                        else -> letter.toString()
                    })
                }
            }
        }
    }
}
