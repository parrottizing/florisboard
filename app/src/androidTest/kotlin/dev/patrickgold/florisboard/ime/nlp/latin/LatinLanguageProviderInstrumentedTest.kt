/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatinLanguageProviderInstrumentedTest {
    @Test
    fun suggestProvidesCorrectionAndAutoCommitForTeh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LatinLanguageProvider(context)
        provider.create()
        provider.preload(Subtype.DEFAULT)

        val content = EditorContent(
            text = "teh",
            offset = 0,
            localSelection = EditorRange(3, 3),
            localComposing = EditorRange(0, 3),
            localCurrentWord = EditorRange(0, 3),
        )
        val suggestions = provider.suggest(
            subtype = Subtype.DEFAULT,
            content = content,
            maxCandidateCount = 6,
            allowPossiblyOffensive = true,
            isPrivateSession = false,
        )

        assertTrue(
            "Expected at least one suggestion candidate",
            suggestions.isNotEmpty(),
        )
        assertTrue(
            "Expected 'the' to be suggested for typo 'teh'",
            suggestions.any { it.text.toString().equals("the", ignoreCase = true) },
        )
        assertTrue(
            "Expected an eligible auto-commit candidate for a clear typo",
            suggestions.any { it.isEligibleForAutoCommit },
        )
    }

    @Test
    fun suggestPromotesImToCapitalizedImContraction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LatinLanguageProvider(context)
        provider.create()
        provider.preload(Subtype.DEFAULT)

        val content = EditorContent(
            text = "im",
            offset = 0,
            localSelection = EditorRange(2, 2),
            localComposing = EditorRange(0, 2),
            localCurrentWord = EditorRange(0, 2),
        )
        val suggestions = provider.suggest(
            subtype = Subtype.DEFAULT,
            content = content,
            maxCandidateCount = 6,
            allowPossiblyOffensive = true,
            isPrivateSession = false,
        )

        assertTrue(
            "Expected \"I'm\" to be suggested for input \"im\"",
            suggestions.any { it.text.toString() == "I'm" },
        )
        assertTrue(
            "Expected \"I'm\" suggestion to be eligible for auto-commit",
            suggestions.any { it.text.toString() == "I'm" && it.isEligibleForAutoCommit },
        )
    }

    @Test
    fun suggestDoesNotLeakLatinSuggestionsForCyrillicInput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LatinLanguageProvider(context)
        provider.create()
        provider.preload(Subtype.DEFAULT)

        val content = EditorContent(
            text = "привет",
            offset = 0,
            localSelection = EditorRange(6, 6),
            localComposing = EditorRange(0, 6),
            localCurrentWord = EditorRange(0, 6),
        )
        val suggestions = provider.suggest(
            subtype = Subtype.DEFAULT,
            content = content,
            maxCandidateCount = 6,
            allowPossiblyOffensive = true,
            isPrivateSession = false,
        )

        assertTrue(
            "Expected no Latin-script leakage for Cyrillic input",
            suggestions.none { candidate ->
                candidate.text.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.LATIN }
            },
        )
    }
}
