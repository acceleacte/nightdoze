package com.nightdoze.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 单个数值滚轮选择器（用于「自定义」静置时长）。
 *
 * 与 [WheelTimeDialog] 是同一套结构，只换了取值域与单位后缀。两者都靠
 * Compose Foundation 官方的 `rememberSnapFlingBehavior` 做吸附（PRD 5.8）。
 *
 * 为什么不把它抽象成一个通用滚轮再让时间选择器复用：时间是两个滚轮 + ":" 分隔符 +
 * 固定补零，数值是一个滚轮 + 单位文案，公共部分只有「一个能吸附的 LazyColumn」；
 * 为了共用那 30 行去引入一个带泛型的抽象层，比重复一遍更贵（三次法则：
 * 第二次出现时先复制，第三次再抽）。
 *
 * `options` 用**显式列表**而不是 `IntRange`：滚轮要铺的是「可选值」而不是「区间」。
 * 静置时长按 5 分钟一档（5, 10, … 720），区间表达不了步长，只有列表能。
 * 旧版用的 `range: IntRange` 要求区间内每个整数都是一个合法选项，加了步长后不再成立。
 */
@Composable
fun WheelNumberDialog(
    title: String,
    options: List<Int>,
    current: Int,
    unit: String,
    suffix: (Int) -> String = { "" },
    /** 是否按「时:分」显示。true 时 [unit] 忽略，滚轮显示 `h:mm`。 */
    hourMinute: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // 落点取与 current 最接近的可选项：外部传来的值可能不在选项表里
    // （旧版存过 3/7 这类值），直接按下标索引会取到错误的一项。
    var selected by remember {
        mutableIntStateOf(options.minByOrNull { kotlin.math.abs(it - current) } ?: options.first())
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 滚轮铺的是选项表本身，回报值就是表里的真实值，不做下标换算
                    NumberWheel(
                        options = options,
                        selected = selected,
                        hourMinute = hourMinute,
                        onSelected = { selected = it },
                    )
                    if (!hourMinute) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 只有在档位本身需要解释时才显示（例如「自定义」要说明它覆盖了哪些固定档）
                val note = suffix(selected)
                if (note.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(selected) }) { Text("完成") }
                }
            }
        }
    }
}

/**
 * 数值滚轮本体。
 *
 * 结构与 [Wheel] 一致（中段起点 + 官方吸附），差异有两点：
 * 1. 只铺真实选项（可带步长，如 5 分钟一档），不做 `Int.MAX_VALUE` 的无限循环。
 *    选项有限时循环反而会让用户翻过头又看到最小值，不如老实到底；
 * 2. 项高与可见项数复用时间滚轮的常量，两个滚轮的滚动手感保持一致。
 *
 * 回报的是 `options` 里的**真实值**而非下标。选项带步长时，「下标 + 起点」那套换算
 * 会给出与实际不符的值。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberWheel(
    options: List<Int>,
    selected: Int,
    hourMinute: Boolean,
    onSelected: (Int) -> Unit,
) {
    val side = WHEEL_VISIBLE / 2
    val count = options.size
    val index = options.indexOf(selected).coerceAtLeast(0)

    // 视口只有 WHEEL_VISIBLE 项，所以只挂这么多个项，用「中段起点 - 半边」这个纯偏移
    // 把第 0 项送到中线上来。早先铺满 count 项并夹住起点，结果有 bug：
    // 贴近上下限时起点被夹住，中线上那一项就与 selected 对不上（顶到上限时选 720
    // 中线显示的却是 710）。用偏移表达就没有「夹起点」这一步，也就没有这个错位。
    //
    // 代价是首尾两项在 index 为 0 / count-1 时会各空出一行（那时中段起点取不到边界值）。
    // 这两帧刚好被上下渐隐盖住，属可接受；换掉的是整类「显示的与选中的不一致」。
    val mid = Int.MAX_VALUE / 2
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = mid - mid % count - side + index,
    )
    val fling = rememberSnapFlingBehavior(lazyListState = state)

    LaunchedEffect(state, options) {
        snapshotFlow { state.firstVisibleItemIndex }
            .map { options[(it + side) % count] }
            .distinctUntilChanged()
            .collect(onSelected)
    }

    Box(
        modifier = Modifier
            .width(if (hourMinute) 96.dp else 76.dp)
            .height(ITEM_HEIGHT * WHEEL_VISIBLE),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(ITEM_HEIGHT)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(10.dp),
                )
        )

        LazyColumn(
            state = state,
            flingBehavior = fling,
            modifier = Modifier
                .fillMaxWidth()
                .height(ITEM_HEIGHT * WHEEL_VISIBLE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ITEM_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (hourMinute) "%d:%02d".format(options[i] / 60, options[i] % 60)
                        else "%d".format(options[i]),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        FadeScrim(
            listOf(MaterialTheme.colorScheme.surface, Color.Transparent),
            Modifier.align(Alignment.TopCenter),
        )
        FadeScrim(
            listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
            Modifier.align(Alignment.BottomCenter),
        )
    }
}
