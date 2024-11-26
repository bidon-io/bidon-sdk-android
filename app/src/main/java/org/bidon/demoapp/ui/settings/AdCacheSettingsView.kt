package org.bidon.demoapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.bidon.demoapp.component.AppOutlinedButton
import org.bidon.demoapp.component.AppTextButton
import org.bidon.demoapp.component.Body2Text
import org.bidon.demoapp.component.Subtitle1Text
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdCacheSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.Companion.MIN_CACHE_SIZE
import org.bidon.sdk.cache.AdCacheSettingsProvider.Companion.MIN_RETRY_DELAY_MS

@Composable
internal fun AdCacheSettingsView(
    onApply: (AdCacheSettings) -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val adCacheSettings = remember {
        mutableStateOf(
            AdCacheSettings(
                banner = AdSettings(MIN_CACHE_SIZE, MIN_RETRY_DELAY_MS),
                interstitial = AdSettings(MIN_CACHE_SIZE, MIN_RETRY_DELAY_MS),
                rewardedVideo = AdSettings(MIN_CACHE_SIZE, MIN_RETRY_DELAY_MS),
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
    val focusManager = LocalFocusManager.current
    var adunitCacheSize by remember(adSettings) { mutableStateOf(adSettings.cacheSize.toString()) }
    var noFillDelayMs by remember(adSettings) { mutableStateOf(adSettings.retryDelayMs.toString()) }

    Column(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
    ) {
        // Ad Unit Cache Size
        OutlinedTextField(
            value = adunitCacheSize,
            onValueChange = { newValue ->
                adunitCacheSize = newValue.filter { it.isDigit() }
            },
            label = { Text("Ad Unit Cache Size") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                }
            )
        )

        // No Fill Delay (ms)
        OutlinedTextField(
            value = noFillDelayMs,
            onValueChange = { newValue ->
                noFillDelayMs = newValue.filter { it.isDigit() }
            },
            label = { Text("No Fill Delay (ms)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                }
            )
        )

        AppOutlinedButton(
            text = "Apply Settings",
            modifier = Modifier
                .align(Alignment.End)
                .padding(vertical = 4.dp)
        ) {
            onSettingsChange(
                AdSettings(
                    cacheSize = adunitCacheSize.toIntOrNull() ?: adSettings.cacheSize,
                    retryDelayMs = noFillDelayMs.toIntOrNull() ?: adSettings.retryDelayMs
                )
            )
        }
    }
    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), thickness = 1.dp)
}
