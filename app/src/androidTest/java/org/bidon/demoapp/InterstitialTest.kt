package org.bidon.demoapp

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import org.bidon.demoapp.navigation.NavigationGraph
import org.bidon.demoapp.ui.InterstitialScreen
import org.bidon.demoapp.ui.MainScreen
import org.junit.Rule
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 03/03/2023.
 */
class InterstitialTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun interstitial_load() {
        rule.setContent {
//            NavigationGraph(
//                navController = rememberNavController(),
//                shared = getSharedPreferences("app_test", Context.MODE_PRIVATE)
//            )
        }
    }
}