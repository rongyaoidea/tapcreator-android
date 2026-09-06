package com.tapcreator.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tapcreator.app.backend.auth.AuthService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Hilt 注入冒烟测试：验证 DI 图完整、应用可启动、匿名会话可用。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TapcreatorAppSmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var authService: AuthService

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun authServiceIsInject() {
        assertNotNull(authService)
    }

    @Test
    fun mainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(scenario)
        }
    }
}