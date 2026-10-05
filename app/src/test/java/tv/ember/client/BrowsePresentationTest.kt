package tv.ember.client

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.data.*
import tv.ember.client.emby.EmbyApi

class BrowsePresentationTest {
    @Test fun searchFiltersAndFavoritesReachTheServerWithPagination() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api=EmbyApi(OkHttpClient(),"test")
            val session=Session(server.url("/proxy/emby").toString(),"token","u","TV")
            server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
            api.items(session,"",40,"星际","CommunityRating","Movie",true)
            val request=server.takeRequest().requestUrl!!
            assertEquals("/proxy/emby/Users/u/Items",request.encodedPath)
            assertEquals("星际",request.queryParameter("SearchTerm"))
            assertEquals("Movie",request.queryParameter("IncludeItemTypes"))
            assertEquals("IsFavorite",request.queryParameter("Filters"))
            assertEquals("40",request.queryParameter("StartIndex"))
            assertEquals("Descending",request.queryParameter("SortOrder"))
            assertEquals("true",request.queryParameter("Recursive"))
        }
    }
    @Test fun inheritedBackdropUsesParentWhilePrimaryFallbackStaysOnImageOwner() {
        val item=VideoItem.parse(JSONObject("""{"Id":"episode","Type":"Episode","SeriesId":"series","SeriesPrimaryImageTag":"poster","ParentBackdropItemId":"series","ParentBackdropImageTags":["scene"],"CommunityRating":8.7,"Genres":["科幻"],"People":[{"Name":"A","Type":"Director"}]}"""))
        val api=EmbyApi(OkHttpClient(),"test");val s=Session("https://example.com/emby","t","u","TV")
        val backdrop=api.landscapeUrl(s,item,true).toHttpUrl()
        assertEquals("/emby/Items/series/Images/Backdrop/0",backdrop.encodedPath)
        assertEquals("scene",backdrop.queryParameter("tag"));assertEquals("1280",backdrop.queryParameter("maxWidth"))
        assertEquals("8.7",item.communityRating);assertEquals(listOf("科幻"),item.genres);assertEquals("Director",item.people.single().type)
        assertEquals("/emby/Items/series/Images/Primary",api.landscapeUrl(s,item.copy(backdropTag="")).toHttpUrl().encodedPath)
    }
    @Test fun ownBackdropTakesPriorityAndMissingMetadataRemainsEmpty() {
        val item=VideoItem.parse(JSONObject("""{"Id":"movie","BackdropImageTags":["own"],"ParentBackdropItemId":"parent","ParentBackdropImageTags":["inherited"]}"""))
        assertEquals("movie",item.backdropId);assertEquals("own",item.backdropTag)
        assertTrue(item.communityRating.isEmpty());assertTrue(item.genres.isEmpty());assertTrue(item.people.isEmpty())
    }
    @Test fun cinemaCopyUsesServerOriginalTitleAndStudioWhenPresent() {
        val item=VideoItem.parse(JSONObject("""{"Id":"dune2","Name":"沙丘 2","OriginalTitle":"Dune: Part Two","Studios":[{"Name":"WARNER BROS. PICTURES"}]}"""))
        assertEquals("Dune: Part Two",item.originalTitle);assertEquals("WARNER BROS. PICTURES",item.studio)
        val missing=VideoItem.parse(JSONObject("""{"Id":"dune2"}"""))
        assertEquals("",missing.originalTitle);assertEquals("",missing.studio)
    }
}
