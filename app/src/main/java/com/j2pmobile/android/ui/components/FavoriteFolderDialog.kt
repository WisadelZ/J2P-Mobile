/*
 * Copyright (C) 2026 WisadelZ
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.j2pmobile.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.FavoriteFolder
import com.j2pmobile.android.R

/**
 * 收藏夹选择弹窗：本子详情页与阅读器（在线本子）共用。
 *
 * 列表由调用方先取好（[loading] / [error] 表达取列表的状态），本组件只负责选择与回调，
 * 选中项默认「全部」；确认时把选中的收藏夹 id 交给 [onConfirm]（"0" 表示默认收藏夹）。
 */
@Composable
fun FavoriteFolderDialog(
    folders: List<FavoriteFolder>,
    loading: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val allFolders = listOf(
        FavoriteFolder("0", stringResource(R.string.favorite_folder_all))
    ) + folders
    // 弹窗每次打开都是新组合，状态随之复位，因此无需外部再重置
    var selected by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorite_choose_title)) },
        text = {
            when {
                loading -> Text(
                    text = stringResource(R.string.favorite_choose_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error != null -> Text(
                    text = stringResource(R.string.favorite_choose_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    allFolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = folder.id }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == folder.id,
                                onClick = { selected = folder.id },
                            )
                            Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && error == null,
                onClick = { onConfirm(selected) },
            ) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}