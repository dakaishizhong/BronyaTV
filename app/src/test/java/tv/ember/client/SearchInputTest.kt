package tv.ember.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import tv.ember.client.emby.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchInputTest {
    @Test fun typingBurstOnlyDeliversTheLastCompleteQuery()=runTest {
        val sent=mutableListOf<String>();val input=SearchInput(this) { sent+=it }
        input.change("E");advanceTimeBy(100);input.change("Ea");advanceTimeBy(100);input.change("Earthrise")
        advanceTimeBy(299);runCurrent();assertTrue(sent.isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf("Earthrise"),sent)
    }
    @Test fun remoteSubmissionBypassesDelayWithoutDuplicatingThePendingSearch()=runTest {
        val sent=mutableListOf<String>();val input=SearchInput(this) { sent+=it }
        input.change("Earthrise");advanceTimeBy(100);input.change("Earthrise",immediate=true);runCurrent()
        assertEquals(listOf("Earthrise"),sent);advanceUntilIdle();input.change(" Ｅａｒｔｈｒｉｓｅ ",true);advanceUntilIdle()
        assertEquals(1,sent.size)
    }
    @Test fun clearingOrLeavingCancelsTheDelayedQuery()=runTest {
        val sent=mutableListOf<String>();val input=SearchInput(this) { sent+=it }
        input.change("Moon");input.change("　 ");runCurrent();assertEquals(listOf(""),sent)
        input.change("Earth");input.cancel();advanceUntilIdle();assertEquals(listOf(""),sent)
        input.change("Earth");advanceUntilIdle();assertEquals(listOf("","Earth"),sent)
    }
    @Test fun normalizationPreservesChineseAccentsAndWordBoundaries() {
        assertEquals("地升",normalizeSearch("　地升　"));assertEquals("Earth rise",normalizeSearch("Ｅａｒｔｈ\u00a0\t ｒｉｓｅ"))
        assertEquals("Amélie",normalizeSearch("  Amélie  "))
    }
    @Test fun searchUsesServerOrderingUntilAnExplicitSortAndKeepsAllPageConditions() {
        val query=BrowseQuery(search=" Ｅａｒｔｈｒｉｓｅ ",types="Movie",year="1968",played=false)
        val p=query.parameters();assertEquals("Earthrise",p["SearchTerm"]);assertNull(p["SortBy"]);assertNull(p["SortOrder"])
        assertFalse(p["Fields"]!!.contains("People"));assertFalse(p["Fields"]!!.contains("Overview"))
        val sorted=query.copy(explicitSort=true,start=40)
        assertEquals("SortName",sorted.parameters()["SortBy"]);assertEquals("1968",sorted.parameters()["Years"])
        assertEquals("false",sorted.parameters()["IsPlayed"]);assertEquals(sorted,BrowseQuery.parse(sorted.json()))
    }
}
