package org.bidon.demoapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bidon.demoapp.component.AppOutlinedButton
import org.bidon.demoapp.component.AppTextButton
import org.bidon.demoapp.component.Body2Text
import org.bidon.demoapp.component.ItemSelector
import org.bidon.demoapp.component.Subtitle1Text
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdCacheSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.SortStrategy

@Composable
internal fun AdCacheSettingsView(
    onApply: (AdCacheSettings) -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val adCacheSettings = remember {
        mutableStateOf(
            AdCacheSettings(
                banner = AdSettings(SortStrategy.TIMESTAMP, 1, 10_000L),
                interstitial = AdSettings(SortStrategy.TIMESTAMP, 1, 10_000L),
                rewardedVideo = AdSettings(SortStrategy.TIMESTAMP, 1, 10_000L)
            )
        )
    }
    val adTypes = listOf("Interstitial", "Rewarded Video", "Banner")

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Subtitle1Text(text = "AdCaching")
            Spacer(modifier = Modifier.weight(1f))
            AppTextButton(
                modifier = Modifier.padding(top = 0.dp), text = "Reset"
            ) {
                adCacheSettings.value = AdCacheSettings() // Reset all ad settings to null
                onApply(adCacheSettings.value)
            }
        }

        // TabRow with an underline to highlight the section
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth()
        ) {
            adTypes.forEachIndexed { index, adType ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Body2Text(adType) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Configuration view based on the selected tab
        val currentAdTypeSettings = when (selectedTabIndex) {
            0 -> adCacheSettings.value.interstitial
            1 -> adCacheSettings.value.rewardedVideo
            else -> adCacheSettings.value.banner
        }

        AdTypeSettingsView(
            adSettings = currentAdTypeSettings,
            onSettingsChange = { newSettings ->
                adCacheSettings.value = when (selectedTabIndex) {
                    0 -> adCacheSettings.value.copy(interstitial = newSettings)
                    1 -> adCacheSettings.value.copy(rewardedVideo = newSettings)
                    else -> adCacheSettings.value.copy(banner = newSettings)
                }
                onApply(adCacheSettings.value)
            }
        )
    }
}

@Composable
private fun AdTypeSettingsView(
    adSettings: AdSettings,
    onSettingsChange: (AdSettings) -> Unit
) {
    var sortStrategy by remember { mutableStateOf(adSettings.sortStrategy) }
    var adunitCacheSize by remember { mutableStateOf(adSettings.cacheSize) }
    var noFillDelayMs by remember { mutableStateOf(adSettings.retryDelayMs) }

    Column(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
    ) {
        // Sort Strategy Selector
        ItemSelector(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start,
            title = "Sort Strategy",
            items = listOf(SortStrategy.TIMESTAMP, SortStrategy.ECPM),
            selectedItem = sortStrategy,
            getItemTitle = { itemSortStrategy ->
                when (itemSortStrategy) {
                    SortStrategy.TIMESTAMP -> "Timestamp"
                    SortStrategy.ECPM -> "ECPM"
                }
            },
            onItemClicked = { itemSortStrategy -> sortStrategy = itemSortStrategy }
        )

        // Ad Unit Cache Size
        OutlinedTextField(
            value = adunitCacheSize.toString(),
            onValueChange = { adunitCacheSize = it.toIntOrNull() ?: 0 },
            label = { Text("Ad Unit Cache Size") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )

        // No Fill Delay (ms)
        OutlinedTextField(
            value = noFillDelayMs.toString(),
            onValueChange = { noFillDelayMs = it.toLongOrNull() ?: 0L },
            label = { Text("No Fill Delay (ms)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )

        AppOutlinedButton(
            text = "Apply Settings",
            modifier = Modifier
                .align(Alignment.End)
                .padding(vertical = 4.dp)
        ) {
            onSettingsChange(
                AdSettings(
                    sortStrategy = sortStrategy,
                    cacheSize = adunitCacheSize,
                    retryDelayMs = noFillDelayMs
                )
            )
        }
    }
    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), thickness = 1.dp)
}
