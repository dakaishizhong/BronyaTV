package tv.ember.client

import android.app.Activity
import android.content.Intent
import android.content.pm.FeatureInfo
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.ui.LoginActivity
import tv.ember.client.ui.MainActivity

/** Covers the package entry points used by vendor launchers and installer Open buttons. */
@RunWith(AndroidJUnit4::class)
class LaunchCompatibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun closeScreens() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is LoginActivity }.forEach(Activity::finish)
        }
    }

    @Test fun regularAndTvLaunchersResolveTheSameSingleEntry() {
        val pm = context.packageManager
        val regular = pm.getLaunchIntentForPackage(context.packageName)
        val tv = pm.getLeanbackLaunchIntentForPackage(context.packageName)
        assertNotNull("Installer Open must receive a regular launch intent", regular)
        assertNotNull("Android TV must retain its Leanback entry", tv)
        assertEquals(MainActivity::class.java.name, regular!!.component!!.className)
        assertEquals(regular.component, tv!!.component)
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val entries = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName), 0)
            assertEquals("One app tile per launcher", 1, entries.size)
            assertNotNull("Package-scoped implicit Open must resolve", pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            ))
            assertNotNull(entries.single().loadIcon(pm))
            assertNotNull(pm.getActivityBanner(regular.component!!))
        }
    }

    @Test fun vendorFirmwareDoesNotNeedToAdvertiseLeanback() {
        val features = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        val leanback = features.single { it.name == "android.software.leanback" }
        assertEquals(0, leanback.flags and FeatureInfo.FLAG_REQUIRED)
    }

    @Test fun installerStyleOpenWithoutASessionDisplaysTheLoginForm() {
        val app = context.applicationContext as BronyaApp
        val saved = app.sessions.load()
        app.sessions.clear()
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            assertNotNull(launch)
            instrumentation.runOnMainSync { context.startActivity(launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            val end = System.currentTimeMillis() + 15000
            var displayed = false
            while (!displayed && System.currentTimeMillis() < end) {
                instrumentation.runOnMainSync {
                    displayed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<LoginActivity>().any { all(it.window.decorView).filterIsInstance<EditText>().size == 5 }
                }
                if (!displayed) Thread.sleep(100)
            }
            assertTrue("The package Open action must reach the visible login screen", displayed)
        } finally {
            if (saved != null) app.sessions.save(saved)
        }
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
    } else emptyList()
}
