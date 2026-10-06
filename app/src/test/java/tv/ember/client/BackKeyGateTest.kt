package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.ui.BackKeyGate

class BackKeyGateTest {
    @Test fun theSamePhysicalPressCannotBackThroughAnotherActivity() {
        val gate = BackKeyGate()
        assertTrue(gate.accept(1000, false))
        assertFalse(gate.accept(1000, false))
        assertFalse(gate.accept(1000, false))
        assertTrue(gate.accept(1200, false))
    }
    @Test fun aCanceledPressDoesNotNavigate() {
        val gate = BackKeyGate()
        assertFalse(gate.accept(1000, true))
        assertTrue(gate.accept(1100, false))
    }
    @Test fun releaseFromADismissedDialogCannotUnwindTheActivity() {
        val gate=BackKeyGate();val dialog=Any();val activity=Any()
        gate.begin(1000,dialog)
        assertFalse(gate.accept(1000,false,activity))
        assertTrue(gate.accept(1000,false,dialog))
        gate.begin(1000,activity)
        assertFalse(gate.accept(1000,false,activity))
        gate.begin(1200,activity)
        assertTrue(gate.accept(1200,false,activity))
    }
    @Test fun orphanReleasesAndCanceledRepeatsCannotPopAResumedParent() {
        val gate=BackKeyGate();val child=Any();val parent=Any()
        assertFalse(gate.accept(1000,false,parent))
        gate.begin(1200,child)
        assertFalse(gate.accept(1200,true,child))
        assertFalse(gate.accept(1200,false,parent))
        gate.begin(1300,parent)
        assertTrue(gate.accept(1300,false,parent))
    }

}


@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[23],manifest=org.robolectric.annotation.Config.NONE,application=android.app.Application::class)
class TvBackDispatchTest {
    class ParentActivity:tv.ember.client.ui.TvActivity()
    @Test fun dialogAndActivityUseTheSamePhysicalBackOwnership() {
        val controller=org.robolectric.Robolectric.buildActivity(ParentActivity::class.java).setup()
        val activity=controller.get();val dialog=tv.ember.client.ui.TvDialog(activity)
        var parentPops=0;var dialogPops=0
        activity.onBackPressedDispatcher.addCallback(object:androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { parentPops++ }
        })
        dialog.onBackPressedDispatcher.addCallback(object:androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { dialogPops++ }
        })
        val time=android.os.SystemClock.uptimeMillis()+10000
        fun event(action:Int,press:Long=time)=android.view.KeyEvent(press,press+10,action,android.view.KeyEvent.KEYCODE_BACK,0)
        try {
            dialog.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_DOWN))
            dialog.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_UP))
            assertEquals(1,dialogPops)
            activity.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_UP))
            activity.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_DOWN))
            activity.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_UP))
            assertEquals("the resumed parent must keep its page",0,parentPops)
            activity.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_DOWN,time+1000))
            activity.dispatchKeyEvent(event(android.view.KeyEvent.ACTION_UP,time+1000))
            assertEquals(1,parentPops)
        } finally { controller.pause().stop().destroy() }
    }
}
