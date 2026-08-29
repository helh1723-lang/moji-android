package com.moji.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moji.app.MainActivity
import com.moji.app.MojiApplication
import com.moji.app.data.Direction
import com.moji.app.voice.VoiceDraft
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces the reported interaction: confirm bill one, then save both from bill two.
 * This always runs with AI disabled, so no user text, category names, or API key leaves
 * the test device; it still exercises the exact multi-draft confirmation and Room save path.
 */
@RunWith(AndroidJUnit4::class)
class MultiBillConfirmationInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val app get() = composeRule.activity.application as MojiApplication

    @Before fun prepareCleanEmulatorAppData() {
        runBlocking {
            app.database.clearAllTables()
            app.repository.seedCategories()
            app.settings.completeOnboarding()
        }
    }

    @Test fun confirmsFirstDraftThenSavesBothDrafts() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("账本").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("手动记账").performClick()
        composeRule.onNodeWithText("文本输入").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("吃饭花了12元，买零食花了10元")
        composeRule.onNodeWithText("解析并确认").performClick()

        composeRule.onNodeWithText("确认并查看下一笔").performClick()
        composeRule.onNodeWithText("保存全部 2 笔账单").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { app.repository.transactionCountForBackup() == 2 }
        }
        composeRule.onAllNodesWithText("保存全部 2 笔账单").assertCountEquals(0)
    }

    @Test fun repositoryCommitsTwoConfirmedDraftsAtomically() {
        runBlocking {
            app.repository.saveVoiceTransactions(
                listOf(
                    VoiceDraft("吃饭 12 元", 1_200, listOf("food_meal"), System.currentTimeMillis(), "午饭", Direction.EXPENSE),
                    VoiceDraft("零食 10 元", 1_000, listOf("food_snack"), System.currentTimeMillis(), "薯片", Direction.EXPENSE)
                )
            )
            assertEquals(2, app.repository.transactionCountForBackup())
        }
    }
}
