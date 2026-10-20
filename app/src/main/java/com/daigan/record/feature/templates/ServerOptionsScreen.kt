package com.daigan.record.feature.templates

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.repository.ServerOptionRepository
import com.daigan.record.ui.components.PageHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerOptionsUiState(
    val gameName: String = "",
    val options: List<String> = emptyList(),
    /** Game names that currently have at least one option, for quick switching. */
    val knownGames: List<String> = emptyList(),
    val saveError: String? = null,
)

/**
 * UF-005: per-game 区服 option maintenance in the template area. Options live in a
 * game-name-keyed store, so they survive template edits and never rewrite the server
 * value saved on any account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ServerOptionsViewModel @Inject constructor(
    private val serverOptionRepository: ServerOptionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerOptionsUiState())
    val uiState: StateFlow<ServerOptionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            serverOptionRepository.observeGameNames().collect { names ->
                _uiState.update { it.copy(knownGames = names) }
            }
        }
        viewModelScope.launch {
            _uiState
                .map { it.gameName.trim() }
                .distinctUntilChanged()
                .flatMapLatest { name ->
                    if (name.isBlank()) flowOf(emptyList()) else serverOptionRepository.observeForGame(name)
                }
                .collect { options -> _uiState.update { it.copy(options = options) } }
        }
    }

    fun setGameName(name: String) {
        _uiState.update { it.copy(gameName = name, saveError = null) }
    }

    fun addOption(option: String) {
        val state = _uiState.value
        val game = state.gameName.trim()
        if (game.isEmpty()) return
        write { serverOptionRepository.setOptions(game, state.options + option.trim()) }
    }

    fun renameOption(oldName: String, newName: String) {
        val game = _uiState.value.gameName.trim()
        if (game.isEmpty()) return
        write { serverOptionRepository.renameOption(game, oldName, newName) }
    }

    fun removeOption(name: String) {
        val game = _uiState.value.gameName.trim()
        if (game.isEmpty()) return
        write { serverOptionRepository.removeOption(game, name) }
    }

    private inline fun write(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                _uiState.update { it.copy(saveError = error.message ?: "操作失败，请重试。") }
            }.onSuccess {
                _uiState.update { it.copy(saveError = null) }
            }
        }
    }
}

@Composable
fun ServerOptionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerOptionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ServerOptionsScreen(
        uiState = uiState,
        onBack = onBack,
        onGameNameChange = viewModel::setGameName,
        onAddOption = viewModel::addOption,
        onRenameOption = viewModel::renameOption,
        onRemoveOption = viewModel::removeOption,
        modifier = modifier,
    )
}

@Composable
internal fun ServerOptionsScreen(
    uiState: ServerOptionsUiState,
    onBack: () -> Unit,
    onGameNameChange: (String) -> Unit,
    onAddOption: (String) -> Unit,
    onRenameOption: (String, String) -> Unit,
    onRemoveOption: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingAdd by rememberSaveable { mutableStateOf(false) }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTarget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(title = "区服选项", subtitle = "按游戏维护填写账号时可点选的区服", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.gameName,
                            onValueChange = onGameNameChange,
                            label = { Text("游戏名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (uiState.knownGames.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                uiState.knownGames.forEach { name ->
                                    FilterChip(
                                        selected = uiState.gameName == name,
                                        onClick = { onGameNameChange(name) },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                        Text(
                            text = "填写账号时会展示对应游戏的区服供点选，也可直接输入；" +
                                "修改或删除选项不会改动已保存账号的区服。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "区服列表",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { pendingAdd = true },
                        enabled = uiState.gameName.isNotBlank(),
                    ) { Text("添加区服") }
                }
            }
            if (uiState.gameName.isNotBlank() && uiState.options.isEmpty()) {
                item {
                    Text(
                        text = "该游戏还没有区服选项；未配置时仍可在账号表单中直接输入区服。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(uiState.options, key = { it }) { option ->
                Card {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(text = option, modifier = Modifier.weight(1f))
                        TextButton(onClick = { renameTarget = option }) { Text("改名") }
                        TextButton(onClick = { removeTarget = option }) { Text("删除") }
                    }
                }
            }
            uiState.saveError?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (pendingAdd) {
        OptionNameDialog(
            title = "添加区服",
            initial = "",
            onDismiss = { pendingAdd = false },
            onConfirm = { value ->
                pendingAdd = false
                onAddOption(value)
            },
        )
    }
    renameTarget?.let { option ->
        OptionNameDialog(
            title = "修改区服名称",
            initial = option,
            onDismiss = { renameTarget = null },
            onConfirm = { value ->
                renameTarget = null
                onRenameOption(option, value)
            },
        )
    }
    removeTarget?.let { option ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("删除区服选项？") },
            text = { Text("删除「$option」后，已保存账号的区服值保持不变。此操作不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    onRemoveOption(option)
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun OptionNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("区服名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
