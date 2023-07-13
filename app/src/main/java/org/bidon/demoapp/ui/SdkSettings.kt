package org.bidon.demoapp.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.bidon.demoapp.component.AppOutlinedButton
import org.bidon.demoapp.component.AppTextButton
import org.bidon.demoapp.component.CaptionText
import org.bidon.demoapp.component.ItemSelector
import org.bidon.demoapp.component.NumberSelector
import org.bidon.demoapp.component.Subtitle1Text
import org.bidon.demoapp.ui.ext.LocalDateTimeNow
import org.bidon.demoapp.ui.settings.TestModeInfo
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.regulation.Coppa
import org.bidon.sdk.regulation.Gdpr
import org.bidon.sdk.segment.models.Gender
import org.json.JSONObject
import java.lang.Math.random

/**
 * Created by Aleksei Cherniaev on 21/06/2023.
 */
@Composable
fun SdkSettings() {
    val shared = LocalContext.current.getSharedPreferences("app_test", Context.MODE_PRIVATE)
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        val testModeState = remember {
            mutableStateOf(shared.getBoolean(TestModeKey, false))
        }
        val coppaState = remember {
            mutableStateOf(
                shared.getInt("coppa", Coppa.Default.code).let { code ->
                    Coppa.values().first { it.code == code }
                }
            )
        }
        val gdprState = remember {
            mutableStateOf(
                shared.getInt("gdpr", Gdpr.Default.code).let { code ->
                    Gdpr.values().first { it.code == code }
                }
            )
        }
        Subtitle1Text(text = "Bidon SDK settings")
        AppOutlinedButton(
            modifier = Modifier.padding(top = 16.dp),
            text = "Add SDK-level Extras"
        ) {
            BidonSdk.addExtra("token_json", JSONObject("""{"a":"sdk_level"}"""))
            BidonSdk.addExtra("sdk_level_long", LocalDateTimeNow)
        }
        SegmentAttrButton()
        ItemSelector(
            modifier = Modifier.padding(top = 16.dp),
            horizontalAlignment = Alignment.Start,
            title = "Test mode",
            items = listOf(true, false),
            selectedItem = testModeState.value,
            getItemTitle = { testMode ->
                "True".takeIf { testMode } ?: "False"
            },
            onItemClicked = { testMode ->
                shared.edit {
                    putBoolean(TestModeKey, testMode)
                }
                TestModeInfo.isTesMode.value = testMode
                testModeState.value = testMode
                BidonSdk.setTestMode(testMode)
            }
        )
        ItemSelector(
            modifier = Modifier.padding(top = 16.dp),
            horizontalAlignment = Alignment.Start,
            title = "COPPA",
            items = Coppa.values().toList(),
            selectedItem = coppaState.value,
            getItemTitle = { coppa: Coppa ->
                "$coppa"
            },
            onItemClicked = { coppa ->
                shared.edit {
                    putInt("coppa", coppa.code)
                }
                coppaState.value = coppa
                BidonSdk.regulation.coppa = coppa
            }
        )
        ItemSelector(
            modifier = Modifier.padding(top = 16.dp),
            horizontalAlignment = Alignment.Start,
            title = "GDPR",
            items = Gdpr.values().toList(),
            selectedItem = gdprState.value,
            getItemTitle = { gdpr ->
                "$gdpr"
            },
            onItemClicked = { gdpr ->
                shared.edit {
                    putInt("gdpr", gdpr.code)
                }
                gdprState.value = gdpr
                BidonSdk.regulation.gdpr = gdpr
            }
        )
    }
}

@Composable
private fun SegmentAttrButton(
    genders: List<String> = Gender.values().map { it.code } + "Not set",
) {
    val isHidden = remember {
        mutableStateOf(true)
    }
    val gender = remember {
        mutableStateOf<Gender?>(null)
    }
    BidonSdk.segment.setGender(gender.value)
    val age = remember {
        mutableStateOf(0)
    }
    BidonSdk.segment.setAge(age.value)
    val level = remember {
        mutableStateOf(0)
    }
    BidonSdk.segment.setLevel(level.value)
    val inAppAmount = remember {
        mutableStateOf(0.0)
    }
    BidonSdk.segment.setTotalInAppAmount(inAppAmount.value)
    val isPaying = remember {
        mutableStateOf(false)
    }
    BidonSdk.segment.setPaying(isPaying = isPaying.value)

    Column {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Subtitle1Text(
                text = "Segment values",
                modifier = Modifier.clickable {
                    isHidden.value = !isHidden.value
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            AppTextButton(
                modifier = Modifier.padding(top = 0.dp),
                text = "Random values"
            ) {
                gender.value = Gender.values().random()
                age.value = (50 * random()).toInt() + 18
                level.value = (80 * random()).toInt()
                inAppAmount.value = 100.0 * random()
                isPaying.value = true.takeIf { random() > 0.5 } ?: false
                BidonSdk.segment.setCustomAttributes(mapOf("attr1" to "hello world"))
                BidonSdk.segment.putCustomAttribute(attribute = "attr2", value = 28)
            }
        }
        AnimatedVisibility(visible = isHidden.value) {
            val text = buildString {
                append(gender.value?.code ?: "Not set")
                append(", ")
                appendLine("${age.value} years")
                appendLine("Game level ${level.value}, ")
                append("In-app amount $${inAppAmount.value}, ")
                append("Paying: ${isPaying.value}")
            }
            CaptionText(
                text = text,
                color = Color.White.copy(alpha = 0.4f)
            )
        }
        AnimatedVisibility(visible = !isHidden.value) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.secondary
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    ItemSelector(
                        modifier = Modifier.padding(top = 0.dp),
                        horizontalAlignment = Alignment.Start,
                        title = "Gender",
                        items = genders,
                        selectedItem = gender.value?.code ?: "Not set",
                        getItemTitle = { genderString ->
                            genderString
                        },
                        onItemClicked = { genderString ->
                            val newGender = Gender.values().firstOrNull { it.code == genderString }
                            gender.value = newGender
                        }
                    )
                    NumberSelector(
                        title = "Age",
                        modifier = Modifier.padding(top = 4.dp),
                        value = age.value,
                        onPlusClicked = {
                            age.value += 7
                        },
                        onMinusClicked = {
                            age.value = maxOf(0, age.value - 7)
                        }
                    )
                    NumberSelector(
                        title = "Game Level",
                        modifier = Modifier.padding(top = 0.dp),
                        value = level.value,
                        onPlusClicked = {
                            level.value += 1
                        },
                        onMinusClicked = {
                            level.value -= 1
                        }
                    )
                    NumberSelector(
                        title = "In-App Amount",
                        modifier = Modifier.padding(top = 0.dp),
                        value = inAppAmount.value,
                        onPlusClicked = {
                            inAppAmount.value += 1.24
                        },
                        onMinusClicked = {
                            inAppAmount.value -= 1.24
                        }
                    )
                    ItemSelector(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalAlignment = Alignment.Start,
                        title = "Is paying",
                        items = listOf(true, false),
                        selectedItem = isPaying.value,
                        getItemTitle = { isPaying ->
                            "True".takeIf { isPaying } ?: "False"
                        },
                        onItemClicked = { testMode ->
                            isPaying.value = testMode
                        }
                    )
                }
            }
        }
    }

}

internal const val TestModeKey = "test_mode"