package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.BrowsedFolder
import ai.tatawur.rafiq.client.PathCheck
import ai.tatawur.rafiq.client.ProjectPickerState
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * The project folder: tap a recent folder, browse a root one level at a time, or type the path. A typed path is
 * checked with your server, and the field says why it will not work before you tap Start.
 */
@Composable
internal fun ProjectPicker(
    state: ProjectPickerState,
    actions: ProjectPickerActions,
    fieldColors: TextFieldColors,
) {
    val spacing = RafiqTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        val browsing = state.browsing
        if (browsing == null) {
            FolderSection(R.string.projects_recent, state.recent, actions.onChoose)
            FolderSection(R.string.projects_browse, state.roots, actions.onOpen)
        } else {
            BrowsePanel(browsing, actions)
        }
        PathField(state, actions.onType, fieldColors)
    }
}

@Composable
private fun FolderSection(
    titleRes: Int,
    paths: List<String>,
    onTap: (String) -> Unit,
) {
    if (paths.isEmpty()) return
    Column {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.labelMedium,
            color = RafiqTheme.colors.textMuted,
        )
        paths.forEach { path ->
            FolderRow(path, mono = true, onClick = { onTap(path) })
        }
    }
}

/** One folder being browsed: where you are, a way up, its folders, and a button to use it. */
@Composable
private fun BrowsePanel(
    browsing: BrowsedFolder,
    actions: ProjectPickerActions,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onUp) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.projects_up),
                    modifier = Modifier.size(spacing.icon),
                    tint = colors.text,
                )
            }
            Text(
                text = browsing.listing?.path ?: browsing.path,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = plexMono),
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        OutlinedButton(
            onClick = actions.onChooseBrowsed,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
            shape = MaterialTheme.shapes.small,
        ) { Text(stringResource(R.string.projects_use_folder)) }
        val listing = browsing.listing
        when {
            browsing.failure != null -> Note(stringResource(R.string.projects_folder_failed))
            listing == null -> Note(stringResource(R.string.projects_loading))
            listing.folders.isEmpty() -> Note(stringResource(R.string.projects_no_folders))
            else -> listing.folders.forEach { name -> FolderRow(name, mono = false) { actions.onOpenChild(name) } }
        }
        if (listing?.truncated == true) Note(stringResource(R.string.projects_truncated))
    }
}

@Composable
private fun FolderRow(
    text: String,
    mono: Boolean,
    onClick: () -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium
    Column {
        Text(
            text = text,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RafiqTheme.spacing.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onClick)
                    .wrapContentHeight()
                    .padding(horizontal = RafiqTheme.spacing.xSmall),
            style = if (mono) style.copy(fontFamily = plexMono) else style,
            color = RafiqTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        HorizontalDivider(color = RafiqTheme.colors.border)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = RafiqTheme.colors.textMuted)
}

/** The typed path, with what your server said about it next to the field. */
@Composable
private fun PathField(
    state: ProjectPickerState,
    onType: (String) -> Unit,
    colors: TextFieldColors,
) {
    val check = state.check
    OutlinedTextField(
        value = state.path,
        onValueChange = onType,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.new_session_project)) },
        placeholder = { Text(stringResource(R.string.new_session_project_hint)) },
        supportingText = { Text(stringResource(checkText(check))) },
        isError = check == PathCheck.NotAbsolute || check == PathCheck.NotAFolder,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = plexMono),
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        colors = colors,
    )
}

private fun checkText(check: PathCheck): Int =
    when (check) {
        PathCheck.Empty -> R.string.new_session_project_help
        PathCheck.NotAbsolute -> R.string.projects_check_not_absolute
        PathCheck.Checking -> R.string.projects_check_checking
        PathCheck.Folder -> R.string.projects_check_folder
        PathCheck.NotAFolder -> R.string.projects_check_not_a_folder
        PathCheck.OutsideRoots -> R.string.projects_check_outside_roots
        is PathCheck.Unchecked -> R.string.projects_check_unchecked
    }

@Preview(name = "Project picker, top", widthDp = 399)
@Composable
private fun ProjectPickerTopPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProjectPicker(
                ProjectPickerState(
                    recent = listOf("/srv/weather-app", "/srv/projects/a-very-long-folder-name/docs-site"),
                    roots = listOf("/srv"),
                    path = "/srv/wether-app",
                    check = PathCheck.NotAFolder,
                ),
                ProjectPickerActions(),
                previewFieldColors(),
            )
        }
    }
}

@Preview(name = "Project picker, browsing", widthDp = 399)
@Composable
private fun ProjectPickerBrowsePreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProjectPicker(
                ProjectPickerState(
                    roots = listOf("/srv"),
                    browsing =
                        BrowsedFolder(
                            path = "/srv",
                            root = "/srv",
                            listing = ProjectFolderListing("/srv", listOf("api-server", "docs-site", "weather-app")),
                        ),
                ),
                ProjectPickerActions(),
                previewFieldColors(),
            )
        }
    }
}

@Composable
private fun previewFieldColors() = newSessionFieldColors()
