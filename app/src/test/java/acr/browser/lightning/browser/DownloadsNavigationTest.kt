package acr.browser.lightning.browser

import acr.browser.lightning.browser.tab.TabInitializer
import acr.browser.lightning.browser.tab.TabModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.lang.reflect.Proxy

class DownloadsNavigationTest {
    @Test
    fun `return selects the originating page before closing downloads`() = runTest {
        val model = TestModel(tab(1, "https://origin.org"), tab(2, "https://other.org"), downloads(3))
        model.returnFromDownloads(3, 1, model::select, model::openHome)
        assertThat(model.events).containsExactly("select:1", "delete:3")
        assertThat(model.selectedTab?.id).isEqualTo(1)
    }

    @Test
    fun `restored normal downloads tab returns to an existing page without an origin`() = runTest {
        val model = TestModel(tab(1, "https://example.org"), downloads(2))
        model.returnFromDownloads(2, null, model::select, model::openHome)
        assertThat(model.events).containsExactly("select:1", "delete:2")
        assertThat(model.selectedTab?.id).isEqualTo(1)
    }

    @Test
    fun `closing the only restored downloads tab creates home before deletion`() = runTest {
        val model = TestModel(downloads(1))
        model.returnFromDownloads(1, 99, model::select, model::openHome)
        assertThat(model.events).containsExactly("home", "delete:1")
        assertThat(model.selectedTab?.url).isEqualTo("file:///homepage.html")
        assertThat(model.tabsList).hasSize(1)
    }

    @Test
    fun `deleted origin falls back to a regular page ahead of another downloads tab`() = runTest {
        val model = TestModel(tab(1, "https://example.org"), downloads(2), downloads(3))
        model.returnFromDownloads(3, 99, model::select, model::openHome)
        assertThat(model.events).containsExactly("select:1", "delete:3")
    }

    private fun downloads(id: Int) = tab(id, "file:///internal/downloads.html")

    // Only the navigation-relevant properties of TabModel are needed; no WebView is involved.
    private fun tab(id: Int, url: String): TabModel = Proxy.newProxyInstance(
        TabModel::class.java.classLoader, arrayOf(TabModel::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getId" -> id
            "getUrl" -> url
            "getTabType" -> TabModel.Type.NORMAL
            else -> error("Unexpected tab operation: ${method.name}")
        }
    } as TabModel

    private inner class TestModel(vararg initial: TabModel) : BrowserContract.Model {
        override val tabsList = initial.toMutableList()
        override var selectedTab: TabModel? = initial.lastOrNull()
        val events = mutableListOf<String>()

        fun select(id: Int) { selectTab(id) }
        fun openHome() {
            events += "home"
            val home = tab(100, "file:///homepage.html")
            tabsList += home
            selectedTab = home
        }
        override fun selectTab(id: Int): TabModel? {
            events += "select:$id"
            selectedTab = tabsList.first { it.id == id }
            return selectedTab
        }
        override suspend fun deleteTab(id: Int) {
            // A regression deleting before selecting would leave no current page here.
            assertThat(selectedTab?.id).isNotEqualTo(id)
            events += "delete:$id"
            tabsList.removeAll { it.id == id }
        }
        override fun tabsListChanges(): Flow<List<TabModel>> = flowOf(tabsList)
        override suspend fun initializeTabs(): List<TabModel> = tabsList
        override suspend fun createTab(tabInitializer: TabInitializer, tabType: TabModel.Type): TabModel = error("Unused")
        override suspend fun reopenTab(): TabModel? = error("Unused")
        override suspend fun deleteAllTabs() = error("Unused")
        override fun markAllNonEphemeral() = Unit
        override suspend fun freeze() = Unit
        override suspend fun clean() = Unit
    }
}
