package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import ai.tatawur.rafiq.ui.components.AgentTag
import ai.tatawur.rafiq.ui.components.ConnectionBanner
import ai.tatawur.rafiq.ui.components.FeedbackBar
import ai.tatawur.rafiq.ui.components.StatusBadge
import ai.tatawur.rafiq.ui.components.StatusDot
import ai.tatawur.rafiq.ui.components.statusLabel
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/** What the session view can ask its state holder to do. Previews pass the no-op defaults. */
@Immutable
data class SessionActions(
    val onBack: () -> Unit = {},
    val onSelectThread: (threadId: String) -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onQuickReply: (QuickReply) -> Unit = {},
    val onCancel: (threadId: String) -> Unit = {},
    val onApprove: (approvalId: String, optionId: String) -> Unit = { _, _ -> },
    val onJump: (ApprovalCard) -> Unit = {},
    val onScrolledToKey: () -> Unit = {},
    val onDismissFeedback: () -> Unit = {},
    val onPairAgain: () -> Unit = {},
)

/** What the session list can ask its state holder or the navigation to do. */
@Immutable
data class SessionListActions(
    val onOpen: (sessionId: String) -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onFilter: (SessionFilter) -> Unit = {},
    val onApprove: (approvalId: String, optionId: String) -> Unit = { _, _ -> },
    val onDismissFeedback: () -> Unit = {},
    val onPairAgain: () -> Unit = {},
)

/** What the "New session" form can ask its state holder or the navigation to do. */
@Immutable
data class NewSessionActions(
    val onBack: () -> Unit = {},
    val onRetryAgents: () -> Unit = {},
    val onSelectAgent: (String) -> Unit = {},
    val onProjectPathChange: (String) -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onDismissFeedback: () -> Unit = {},
)
