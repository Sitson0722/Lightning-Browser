package acr.browser.lightning.browser

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.ThemableActivity
import acr.browser.lightning.browser.keys.KeyEventAdapter
import acr.browser.lightning.browser.search.IntentExtractor
import acr.browser.lightning.browser.tab.FileUploadRequest
import acr.browser.lightning.browser.tab.TabPager
import acr.browser.lightning.browser.ui.TabConfiguration
import acr.browser.lightning.compose.BrowserTheme
import acr.browser.lightning.di.injector
import acr.browser.lightning.search.SuggestionsModel
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject
import javax.inject.Named

/**
 * The base browser activity that governs the browsing experience for both default and incognito
 * browsers.
 */
abstract class BrowserActivity : ThemableActivity(), BrowserContract.View {

    private var pendingFileUpload: FileUploadRequest? = null
    private var fileChooserOpen = false
    private var qrScannerOpen = false

    @Suppress("ConvertLambdaToReference")
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val request = pendingFileUpload
        pendingFileUpload = null
        fileChooserOpen = false
        request?.onResult(result)
    }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        qrScannerOpen = false
        result.contents?.let { presenter.onEvent(BrowserUiEvent.QrScanResult(it)) }
    }

    @Inject
    internal lateinit var keyEventAdapter: KeyEventAdapter

    @Inject
    internal lateinit var presenter: BrowserPresenter

    @Inject
    internal lateinit var tabPager: TabPager

    @Inject
    internal lateinit var intentExtractor: IntentExtractor

    @Named("tab")
    @Inject
    internal lateinit var tabConfigurationProvider: StateFlow<@JvmSuppressWildcards TabConfiguration?>

    @Inject
    internal lateinit var suggestionsModel: SuggestionsModel

    override fun onCreate(savedInstanceState: Bundle?) {

        val browserFrame = FrameLayout(this)
        val customFrame = FrameLayout(this)
        injector.browserComponentBuilder()
            .activity(this)
            .browserFrame(browserFrame)
            .customFrame(customFrame)
            .initialIntent(intent.takeIf { savedInstanceState == null })
            .build()
            .inject(this)

        super.onCreate(savedInstanceState)

        fileChooserOpen = savedInstanceState?.getBoolean(FILE_CHOOSER_OPEN) ?: false
        qrScannerOpen = savedInstanceState?.getBoolean(QR_SCANNER_OPEN) ?: false

        setContent {
            val currentState = presenter.state.collectAsMutableState(
                produceState = { BrowserComposeState(it) },
                updateFrom = { updateFrom(it) }
            )

            BrowserTheme(appThemeStateFlow) {
                BrowserScreen(
                    tabConfigurationProvider,
                    useBlackStatusBarStateFlow,
                    currentState,
                    presenter,
                    browserFrame,
                    customFrame,
                    suggestionsModel
                )
                LaunchedEffect(currentState.showCustomView) {
                    requestedOrientation = if (currentState.showCustomView) {
                        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    setFullscreen(
                        enabled = currentState.showCustomView,
                        immersive = currentState.showCustomView
                    )
                }
            }
        }

        presenter.onViewAttached(this)

        tabPager.longPressListener = { id, longPress ->
            presenter.onEvent(BrowserUiEvent.PageLongPress(id, longPress))
        }

        onBackPressedDispatcher.addCallback {
            presenter.onEvent(BrowserUiEvent.NavigateBack)
        }
    }

    @SuppressLint("StateFlowValueCalledInComposition")
    @Composable
    fun <T, R> StateFlow<T>.collectAsMutableState(
        produceState: (T) -> R,
        updateFrom: R.(T) -> Unit
    ): R {
        val state = remember { produceState(value) }
        LaunchedEffect(null) {
            collectLatest {
                state.updateFrom(it)
            }
        }
        return state
    }

    override fun onNewIntent(intent: Intent) {
        intentExtractor.extractUrlFromIntent(intent)?.let {
            presenter.onEvent(BrowserUiEvent.NewAction(it))
        }
        super.onNewIntent(intent)
    }

    override fun onDestroy() {
        val request = pendingFileUpload
        pendingFileUpload = null
        request?.cancel()
        super.onDestroy()
        presenter.onViewDetached()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(FILE_CHOOSER_OPEN, fileChooserOpen)
        outState.putBoolean(QR_SCANNER_OPEN, qrScannerOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        presenter.onViewHidden()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyEventAdapter.adaptKeyEvent(event)?.let {
            presenter.onEvent(BrowserUiEvent.KeyComboClick(it))
            true
        } ?: super.onKeyUp(keyCode, event)
    }

    /**
     * @see BrowserContract.View.showFileChooser
     */
    override fun showFileChooser(request: FileUploadRequest) {
        if (!request.isPending) return
        if (fileChooserOpen || qrScannerOpen || isFinishing || isDestroyed) {
            request.cancel()
            return
        }
        pendingFileUpload = request
        fileChooserOpen = true
        try {
            launcher.launch(request.intent)
        } catch (_: ActivityNotFoundException) {
            onFileChooserLaunchFailed()
        } catch (_: SecurityException) {
            onFileChooserLaunchFailed()
        }
    }

    private fun onFileChooserLaunchFailed() {
        val request = pendingFileUpload
        pendingFileUpload = null
        fileChooserOpen = false
        request?.cancel()
        Toast.makeText(this, R.string.message_file_chooser_unavailable, Toast.LENGTH_LONG).show()
    }

    override fun showQrScanner() {
        if (qrScannerOpen || fileChooserOpen || isFinishing || isDestroyed) return
        qrScannerOpen = true
        try {
            qrScannerLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(getString(R.string.scan_qr_prompt))
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
            )
        } catch (_: ActivityNotFoundException) {
            onQrScannerLaunchFailed()
        } catch (_: SecurityException) {
            onQrScannerLaunchFailed()
        }
    }

    private fun onQrScannerLaunchFailed() {
        qrScannerOpen = false
        Toast.makeText(this, R.string.message_qr_scanner_unavailable, Toast.LENGTH_LONG).show()
    }

    override fun showScannedText(text: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_text_title)
            .setMessage(text.take(MAX_SCANNED_TEXT_LENGTH))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_copy_text) { _, _ ->
                presenter.onEvent(BrowserUiEvent.CopyScannedText(text))
            }
            .show()
    }

    private fun setFullscreen(enabled: Boolean, immersive: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (enabled) {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) {
                    hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            } else {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private companion object {
        const val MAX_SCANNED_TEXT_LENGTH = 4_096
        const val FILE_CHOOSER_OPEN = "file_chooser_open"
        const val QR_SCANNER_OPEN = "qr_scanner_open"
    }

    // TODO: Animate color change
//    private fun animateColorChange(color: Int) {
//        if (!userPreferencesDataStore.colorModeEnabled.get() || userPreferencesDataStore.useTheme.get() != AppTheme.LIGHT || isIncognito()) {
//            return
//        }
//        val adapter = tabsAdapter as? DesktopTabRecyclerViewAdapter
//        val colorAnimator = ColorAnimator(defaultColor)
//        binding.toolbar.startAnimation(
//            colorAnimator.animateTo(
//                color
//            ) { mainColor, secondaryColor ->
//                if (userPreferencesDataStore.tabConfiguration.get() != TabConfiguration.DESKTOP) {
//                    backgroundDrawable.color = mainColor
//                    window.setBackgroundDrawable(backgroundDrawable)
//                } else {
//                    adapter?.updateForegroundTabColor(mainColor)
//                }
//                binding.toolbar.setBackgroundColor(mainColor)
//                binding.searchContainer.background?.tint(secondaryColor)
//            })
//    }
}
