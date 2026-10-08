package com.nightdoze.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nightdoze.app.data.parseHhmm
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 滚轮可见项数。取奇数：中间那项是当前值，上下各留几项做渐隐。
 *
 * 与 `internal` 的 [ITEM_HEIGHT] / [FadeScrim] 一起供 [WheelNumberDialog] 复用。
 * 两处滚轮的项高与渐隐必须一致，否则同一个 App 里两个滚轮的手感会对不上。
 */
internal const val WHEEL_VISIBLE = 5

internal val ITEM_HEIGHT = 40.dp

/**
 * 小时 / 分钟双滚轮时间选择器（PRD 5.6）。
 *
 * Material3 自带的是表盘式 TimePicker，要一圈一圈点，与「睡前闭着眼改一下」的场景不合，
 * 所以用 LazyColumn 搭滚轮。
 *
 * **吸附不自己写。** 用 Compose Foundation 官方的 `rememberSnapFlingBehavior`：
 * 惯性衰减、越过中线自动回弹、停在整数项上，全部由它负责。自己实现过一版
 * 「测视口中点 → 算 delta → animateScrollBy」的版本，结果是真机上先后踩到
 * 「显示 00:00 却存进 23:00」和 `collectLatest` 自反馈导致的输入超时 ANR，
 * 两处都是这类几何计算 + 动画互锁的经典坑。官方这套被整个生态跑过，不重复踩。
 *
 * 代价是 `SnapFlingBehavior` 目前仍标 `@ExperimentalFoundationApi`。接受：
 * 它是 foundation 里被大量项目依赖的稳定实现，只是注解没摘。
 */
@Composable
fun WheelTimeDialog(
    title: String,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = parseHhmm(current)
    var hour by remember { mutableIntStateOf(parsed?.div(60) ?: 22) }
    var minute by remember { mutableIntStateOf(parsed?.rem(60) ?: 0) }

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
                    Wheel(count = 24, selected = hour, onSelected = { hour = it })
                    Text(
                        ":",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Wheel(count = 60, selected = minute, onSelected = { minute = it })
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm("%02d:%02d".format(hour, minute)) }) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

/**
 * 单列滚轮。
 *
 * 三个约定撑起整套逻辑，缺一不可：
 * 1. 视口高度恰好是 `WHEEL_VISIBLE` 个项高，项等高等宽、不做 contentPadding
 *    → 中线那一项恒等于「第一个可见项 + side」；
 * 2. 列表项数取 `Int.MAX_VALUE`，起点落在中段并对齐到 `count` 的整数倍
 *    → 两端都远到够不着，跨 00/23 的循环天然成立，不需要特判；
 * 3. 吸附交给 `rememberSnapFlingBehavior`
 *    → 手指松开后一定停在整数项上，于是上面那条索引换算在停下时是精确的。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(
    count: Int,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val side = WHEEL_VISIBLE / 2

    // 中段起点对齐到 count 的整数倍：于是 (起点 + side) % count == selected，
    // 首帧就直接停在目标值上，不需要任何「初始定位」的副作用代码。
    val mid = Int.MAX_VALUE / 2
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = mid - mid % count - side + selected,
    )

    val fling = rememberSnapFlingBehavior(lazyListState = state)

    // 中线项变了才回报。滑动过程中会连续回报，停下时最后一次回报的就是最终值。
    // 这正是「看到的」与「存下的」始终一致的原因。
    LaunchedEffect(state, count) {
        snapshotFlow { state.firstVisibleItemIndex }
            .map { (it + side) % count }
            .distinctUntilChanged()
            .collect(onSelected)
    }

    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .width(76.dp)
            .height(ITEM_HEIGHT * WHEEL_VISIBLE),
    ) {
        // 中线选中带
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
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(Int.MAX_VALUE) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ITEM_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "%02d".format(index % count),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // 上下渐隐：远处项淡出，做出滚轮的纵深
        FadeScrim(listOf(surface, Color.Transparent), Modifier.align(Alignment.TopCenter))
        FadeScrim(listOf(Color.Transparent, surface), Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun FadeScrim(colors: List<Color>, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ITEM_HEIGHT * (WHEEL_VISIBLE / 2))
            .background(Brush.verticalGradient(colors))
    )
}
