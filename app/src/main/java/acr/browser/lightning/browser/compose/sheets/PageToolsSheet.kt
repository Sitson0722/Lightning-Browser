package acr.browser.lightning.browser.compose.sheets

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.dialog.DialogItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageToolsSheet(
    presenter: BrowserPresenter,
) {
    ListItemSheet(
        title = stringResource(R.string.dialog_tools_title),
        items = listOf(
            DialogItem(
                icon = R.drawable.ic_action_desktop,
                title = R.string.dialog_toggle_desktop,
                isConditionMet = true,
                onClick = { presenter.onEvent(BrowserUiEvent.ToggleDesktopAgentClick) }
            )
        ),
        presenter = presenter
    )
}
