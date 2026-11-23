package com.novelmemory.mobile;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.webkit.WebView;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class MainActivityTest {
    @Test public void continuationOverlapAndRepeatedParagraphAreDetected() {
        String lead="前文完整衔接并且持续铺垫人物情绪".repeat(3);
        assertEquals(lead+"后文",MainActivity.appendRewrite(lead,lead+"后文"));
        String paragraph="角色走过长廊，发现一扇锁住的门。".repeat(10);
        assertTrue(MainActivity.hasLongRepeatedParagraph(paragraph+"\n\n"+paragraph));
    }
    @Test public void javascriptConfirmHasNativeDialogHandler() {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        ViewGroup root=activity.findViewById(android.R.id.content);
        WebView web=(WebView)root.getChildAt(0);
        assertNotNull(web.getWebChromeClient());
        NotificationManager manager=(NotificationManager)activity.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification status=manager.getActiveNotifications()[0].getNotification();
        assertTrue((status.flags&Notification.FLAG_NO_CLEAR)!=0);
        assertTrue((status.flags&Notification.FLAG_ONGOING_EVENT)!=0);
    }

    @Test public void rewriteOutputLimitPersistsAndRejectsInvalidValue() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        Method handle=MainActivity.class.getDeclaredMethod("handle",String.class,JSONObject.class);
        handle.setAccessible(true);
        handle.invoke(activity,"rewriteTokenSave",new JSONObject().put("value",24576));
        assertEquals(24576,activity.getPreferences(Context.MODE_PRIVATE).getInt("rewrite_max_tokens",0));
        try {
            handle.invoke(activity,"rewriteTokenSave",new JSONObject().put("value",40000));
            throw new AssertionError("超限值应被拒绝");
        } catch(java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        assertEquals(24576,activity.getPreferences(Context.MODE_PRIVATE).getInt("rewrite_max_tokens",0));
    }
}
