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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.AlbumItem

/** 封面宽高比固定 3:4，与桌面版 COVER_WIDTH / COVER_HEIGHT 一致。 */
private const val COVER_RATIO = 3f / 4f

/**
 * 网格里的本子卡片：封面 + 名称（固定两行）。
 *
 * 对齐要点：
 * - 封面用固定宽高比，同行卡片高度天然一致；
 * - 名称用 `minLines = maxLines = 2` 占满两行，短标题也不会让这一行凹下去；
 * - 点击封面或名称都进详情页 —— 整张卡片就是一个点击目标，不做点击放大。
 */
@Composable
fun AlbumCard(
    item: AlbumItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        CoverImage(
            url = item.coverUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}