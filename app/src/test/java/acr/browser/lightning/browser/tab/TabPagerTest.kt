package acr.browser.lightning.browser.tab

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.browser.view.CompositeTouchListener
import acr.browser.lightning.browser.view.WebViewLongPressHandler
import android.app.Activity
import android.webkit.WebView
import android.widget.FrameLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class TabPagerTest {
    @Test
    fun `selecting a removed tab keeps the current WebView attached`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        val pager = TabPager(container, WebViewLongPressHandler(activity))
        val view = createWebView(activity)
        pager.addTab(1, lazyOf(view))
        assertThat(pager.selectTab(1)).isTrue()
        pager.removeTab(2)

        assertThat(pager.selectTab(2)).isFalse()
        assertThat(container.childCount).isEqualTo(1)
        assertThat(container.getChildAt(0)).isSameAs(view)
    }

    @Test
    fun `selecting a tab detaches it from an old parent before attaching`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val oldParent = FrameLayout(activity)
        val container = FrameLayout(activity)
        val pager = TabPager(container, WebViewLongPressHandler(activity))
        val view = createWebView(activity)
        oldParent.addView(view)
        pager.addTab(1, lazyOf(view))

        assertThat(pager.selectTab(1)).isTrue()
        assertThat(oldParent.childCount).isZero()
        assertThat(view.parent).isSameAs(container)
    }

    private fun createWebView(activity: Activity) = WebView(activity).apply {
        id = 1
        // Match WebViewFactory: the long-press handler adds a delegate to this listener.
        tag = CompositeTouchListener().also(::setOnTouchListener)
    }
}
