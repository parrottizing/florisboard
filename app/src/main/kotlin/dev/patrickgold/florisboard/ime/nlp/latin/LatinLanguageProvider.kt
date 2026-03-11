/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.lowercase
import dev.patrickgold.florisboard.lib.titlecase
import dev.patrickgold.florisboard.lib.uppercase
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import kotlin.math.abs

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        // Common contractions users often type without apostrophe.
        private val englishExplicitCorrections = mapOf(
            "im" to "i'm",
            "ive" to "i've",
            "id" to "i'd",
            "ill" to "i'll",
            "cant" to "can't",
            "dont" to "don't",
            "doesnt" to "doesn't",
            "didnt" to "didn't",
            "isnt" to "isn't",
            "arent" to "aren't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "wont" to "won't",
            "wouldnt" to "wouldn't",
            "couldnt" to "couldn't",
            "shouldnt" to "shouldn't",
            "youre" to "you're",
            "theyre" to "they're",
            "weve" to "we've",
            "thats" to "that's",
            "theres" to "there's",
            "whats" to "what's",
        )
    }

    private val appContext by context.appContext()

    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
    @Volatile
    private var wordData: Map<String, Int> = emptyMap()
    @Volatile
    private var rankedWordData: List<RankedWord> = emptyList()
    @Volatile
    private var loadedDictionaryLanguage: String = ""

    override val providerId = ProviderId

    private data class RankedWord(
        val word: String,
        val frequency: Int,
        val distance: Int = Int.MAX_VALUE,
    )

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        val targetLanguage = dictionaryLanguageFor(subtype)
        if (wordData.isNotEmpty() && loadedDictionaryLanguage == targetLanguage) {
            return@withContext
        }
        val assetPath = when (targetLanguage) {
            "ru" -> "ime/dict/data_ru.json"
            else -> "ime/dict/data.json"
        }
        // Here we use readText() because the dictionaries are json dictionaries.
        val rawData = appContext.assets.readText(assetPath)
        val loadedWordData = Json.decodeFromString(wordDataSerializer, rawData)
        val rankedWords = loadedWordData
            .entries
            .asSequence()
            .map { (word, frequency) ->
                RankedWord(word = word, frequency = frequency)
            }
            .sortedWith(
                compareByDescending<RankedWord> { it.frequency }
                    .thenBy { it.word }
            )
            .toList()
        synchronized(this@LatinLanguageProvider) {
            if (wordData.isEmpty() || loadedDictionaryLanguage != targetLanguage) {
                wordData = loadedWordData
                rankedWordData = rankedWords
                loadedDictionaryLanguage = targetLanguage
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        ensureDictionaryLoaded(subtype)
        val normalizedWord = word.trim().lowercase(subtype.primaryLocale)
        if (normalizedWord.isEmpty()) {
            return SpellingResult.unspecified()
        }
        if (!isSuggestibleToken(normalizedWord)) {
            return SpellingResult.validWord()
        }
        val explicitCorrection = explicitCorrectionFor(normalizedWord, subtype)
        if (explicitCorrection != null) {
            return SpellingResult.typo(
                suggestions = arrayOf(applyInputCase(word, explicitCorrection, subtype)),
                isHighConfidenceResult = true,
            )
        }
        val hasExactMatch = wordData.containsKey(normalizedWord)
        val inputFrequency = wordData[normalizedWord] ?: 0
        val typoCandidates = findTypoCandidates(
            input = normalizedWord,
            maxCandidateCount = maxSuggestionCount,
        )
        val promotedTypo = typoCandidates.firstOrNull()?.takeIf { candidate ->
            !hasExactMatch || candidate.frequency >= inputFrequency + 24
        }
        if (promotedTypo == null) {
            if (hasExactMatch) {
                return SpellingResult.validWord()
            }
            return SpellingResult.typo(emptyArray())
        }
        val suggestions = buildList {
            add(applyInputCase(word, promotedTypo.word, subtype))
            addAll(
                typoCandidates
                    .map { candidate -> applyInputCase(word, candidate.word, subtype) }
            )
        }
            .distinct()
            .take(maxSuggestionCount)
            .toTypedArray()
        val hasHighConfidence = promotedTypo.distance <= 1 && promotedTypo.frequency >= 96
        return SpellingResult.typo(suggestions, isHighConfidenceResult = hasHighConfidence)
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        ensureDictionaryLoaded(subtype)
        if (rankedWordData.isEmpty()) {
            return emptyList()
        }
        val inputWord = extractInputWord(content)
        if (inputWord.isEmpty()) {
            return emptyList()
        }
        val normalizedWord = inputWord.lowercase(subtype.primaryLocale)
        if (!isSuggestibleToken(normalizedWord)) {
            return emptyList()
        }

        val explicitCorrection = explicitCorrectionFor(normalizedWord, subtype)
        val hasExactMatch = wordData.containsKey(normalizedWord)
        val inputFrequency = wordData[normalizedWord] ?: 0
        val typoCandidates = findTypoCandidates(
            input = normalizedWord,
            maxCandidateCount = maxCandidateCount * 2,
        )
        val prefixCandidates = findPrefixCandidates(
            input = normalizedWord,
            maxCandidateCount = maxCandidateCount * 2,
        )
        val bestTypo = typoCandidates.firstOrNull()
        val promotedTypo = bestTypo?.takeIf { candidate ->
            !hasExactMatch || candidate.frequency >= inputFrequency + 24
        }

        val suggestions = buildList {
            val seen = mutableSetOf<String>()

            fun addCandidate(word: String, confidence: Double, autoCommit: Boolean) {
                val normalized = word.lowercase(subtype.primaryLocale)
                if (normalized in seen || size >= maxCandidateCount) return
                seen.add(normalized)
                add(
                    WordSuggestionCandidate(
                        text = word,
                        confidence = confidence.coerceIn(0.0, 1.0),
                        isEligibleForAutoCommit = autoCommit,
                        sourceProvider = this@LatinLanguageProvider,
                    )
                )
            }

            if (explicitCorrection != null) {
                addCandidate(
                    word = applyInputCase(inputWord, explicitCorrection, subtype),
                    confidence = 0.995,
                    autoCommit = true,
                )
            }

            if (promotedTypo != null) {
                val corrected = applyInputCase(inputWord, promotedTypo.word, subtype)
                addCandidate(
                    word = corrected,
                    confidence = confidenceFor(promotedTypo),
                    autoCommit = shouldAutoCommit(
                        input = normalizedWord,
                        inputFrequency = inputFrequency,
                        candidate = promotedTypo,
                    ),
                )
            }

            addCandidate(
                word = inputWord,
                confidence = if (hasExactMatch) 0.92 else 0.45,
                autoCommit = false,
            )

            typoCandidates.forEach { candidate ->
                addCandidate(
                    word = applyInputCase(inputWord, candidate.word, subtype),
                    confidence = confidenceFor(candidate),
                    autoCommit = false,
                )
            }

            prefixCandidates.forEach { candidate ->
                addCandidate(
                    word = applyInputCase(inputWord, candidate.word, subtype),
                    confidence = confidenceFor(candidate),
                    autoCommit = false,
                )
            }
        }
        return suggestions
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.keys.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.getOrDefault(word, 0) / 255.0
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }

    private fun findPrefixCandidates(input: String, maxCandidateCount: Int): List<RankedWord> {
        return rankedWordData
            .asSequence()
            .filter { ranked ->
                ranked.word != input &&
                    isSameScript(ranked.word, input) &&
                    ranked.word.startsWith(input)
            }
            .take(maxCandidateCount)
            .toList()
    }

    private fun findTypoCandidates(input: String, maxCandidateCount: Int): List<RankedWord> {
        if (input.length < 2) return emptyList()
        val firstChar = input.firstOrNull() ?: return emptyList()
        val maxDistance = maxDistanceFor(input.length)
        return rankedWordData
            .asSequence()
            .filter { ranked ->
                ranked.word != input &&
                    isSameScript(ranked.word, input) &&
                    ranked.word.firstOrNull() == firstChar &&
                    abs(ranked.word.length - input.length) <= maxDistance
            }
            .mapNotNull { ranked ->
                val distance = boundedLevenshtein(input, ranked.word, maxDistance)
                if (distance <= maxDistance) ranked.copy(distance = distance) else null
            }
            .sortedWith(
                compareBy<RankedWord> { it.distance }
                    .thenByDescending { it.frequency }
                    .thenBy { it.word }
            )
            .take(maxCandidateCount)
            .toList()
    }

    private fun shouldAutoCommit(input: String, inputFrequency: Int, candidate: RankedWord): Boolean {
        if (input.length < 3) return false
        if (candidate.distance != 1) return false
        if (candidate.frequency < 96) return false
        return candidate.frequency >= inputFrequency + 40
    }

    private fun confidenceFor(candidate: RankedWord): Double {
        val frequencyScore = candidate.frequency / 255.0
        val distancePenalty = when (candidate.distance) {
            0 -> 0.0
            1 -> 0.18
            2 -> 0.30
            3 -> 0.42
            Int.MAX_VALUE -> 0.12
            else -> 0.50
        }
        return (0.15 + frequencyScore * 0.80 - distancePenalty).coerceIn(0.0, 1.0)
    }

    private fun maxDistanceFor(wordLength: Int): Int {
        return when {
            wordLength <= 4 -> 1
            wordLength <= 9 -> 2
            else -> 3
        }
    }

    private fun isSuggestibleToken(token: String): Boolean {
        return token.any { it.isLetter() } && token.all { it.isLetter() || it == '\'' || it == '-' }
    }

    private fun explicitCorrectionFor(input: String, subtype: Subtype): String? {
        if (subtype.primaryLocale.language != "en") return null
        return englishExplicitCorrections[input]
    }

    private suspend fun ensureDictionaryLoaded(subtype: Subtype) {
        val targetLanguage = dictionaryLanguageFor(subtype)
        if (loadedDictionaryLanguage == targetLanguage && rankedWordData.isNotEmpty()) return
        preload(subtype)
    }

    private fun dictionaryLanguageFor(subtype: Subtype): String {
        return when (subtype.primaryLocale.language) {
            "ru", "uk", "be", "bg", "sr", "mk" -> "ru"
            else -> "en"
        }
    }

    private fun extractInputWord(content: EditorContent): String {
        val direct = content.composingText.ifBlank { content.currentWordText }.trim()
        if (direct.isNotEmpty()) return direct
        return content.textBeforeSelection
            .takeLast(64)
            .takeLastWhile { it.isLetter() || it == '\'' || it == '-' }
            .trim()
    }

    private fun isSameScript(candidate: String, input: String): Boolean {
        val inputScript = firstLetterScript(input) ?: return false
        val candidateScript = firstLetterScript(candidate) ?: return false
        return inputScript == candidateScript
    }

    private fun firstLetterScript(word: String): Character.UnicodeScript? {
        val firstLetter = word.firstOrNull { it.isLetter() } ?: return null
        return Character.UnicodeScript.of(firstLetter.code)
    }

    private fun applyInputCase(inputWord: String, suggestedWord: String, subtype: Subtype): String {
        val lettersInInput = inputWord.filter { it.isLetter() }
        if (lettersInInput.isEmpty()) return suggestedWord
        return when {
            lettersInInput.all { it.isUpperCase() } -> suggestedWord.uppercase(subtype.primaryLocale)
            lettersInInput.first().isUpperCase() -> suggestedWord.titlecase(subtype.primaryLocale)
            suggestedWord.startsWith("i'") -> "I${suggestedWord.drop(1)}"
            else -> suggestedWord
        }
    }

    private fun boundedLevenshtein(a: String, b: String, maxDistance: Int): Int {
        if (abs(a.length - b.length) > maxDistance) {
            return maxDistance + 1
        }
        if (a == b) return 0
        if (isAdjacentTransposition(a, b)) return 1
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            val aChar = a[i - 1]
            for (j in 1..b.length) {
                val substitutionCost = if (aChar == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitutionCost,
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) {
                return maxDistance + 1
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun isAdjacentTransposition(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var firstMismatch = -1
        var secondMismatch = -1
        for (i in a.indices) {
            if (a[i] != b[i]) {
                when {
                    firstMismatch == -1 -> firstMismatch = i
                    secondMismatch == -1 -> secondMismatch = i
                    else -> return false
                }
            }
        }
        return firstMismatch >= 0 &&
            secondMismatch == firstMismatch + 1 &&
            a[firstMismatch] == b[secondMismatch] &&
            a[secondMismatch] == b[firstMismatch]
    }
}
