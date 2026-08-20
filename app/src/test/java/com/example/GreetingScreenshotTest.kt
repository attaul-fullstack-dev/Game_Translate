package com.example

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeSmokeTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun app_title_is_rendered() {
    composeTestRule.setContent { MyApplicationTheme { Text("Game Translator") } }

    composeTestRule.onNodeWithText("Game Translator").assertIsDisplayed()
  }
}
