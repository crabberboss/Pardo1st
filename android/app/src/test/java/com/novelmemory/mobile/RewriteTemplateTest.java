package com.novelmemory.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class RewriteTemplateTest {
    @Test public void desktopTemplateRoundTripsAndFiltersChapters() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("novel_memory_mobile.db");
        Store store=new Store(context);RewriteTemplate templates=new RewriteTemplate(context);
        long archive=store.importText("模板测试.txt",new ByteArrayInputStream(
                "第一章 开端\n甲进入密室。\n第二章 相遇\n乙站在旷野。\n第三章 会合\n众人聚集。\n".getBytes(StandardCharsets.UTF_8)));
        JSONArray chapters=store.chapters(archive,0,40,"");
        assertEquals(3,chapters.length());assertEquals(3,store.chapterCount(archive));
        long first=chapters.getJSONObject(0).getLong("id"),second=chapters.getJSONObject(1).getLong("id"),third=chapters.getJSONObject(2).getLong("id");
        store.getWritableDatabase().execSQL("UPDATE chapters SET tags=? WHERE id=?",new Object[]{"密室 多人",first});
        store.getWritableDatabase().execSQL("UPDATE chapters SET tags=? WHERE id=?",new Object[]{"旷野",second});
        store.getWritableDatabase().execSQL("UPDATE chapters SET tags=? WHERE id=?",new Object[]{"多人",third});
        JSONObject desktop=new JSONObject().put("format","novel-memory-rewrite-template").put("version",1).put("name","密室多人")
                .put("tags",new JSONArray().put(new JSONObject().put("name","密室").put("color","#a557b8").put("recognition_prompt","仅在封闭房间内打标")))
                .put("filter",new JSONObject().put("mode","all").put("tags",new JSONArray().put("密室").put("多人")))
                .put("rewrite",new JSONObject().put("global_prompt","全书改写").put("chapter_prompt","本章重点").put("rules","不可重复"));
        templates.save(archive,desktop);
        JSONObject loaded=templates.load(archive);
        assertEquals("novel-memory-rewrite-template",loaded.getString("format"));
        assertEquals("不可重复",loaded.getJSONObject("rewrite").getString("rules"));
        assertEquals(1,store.countChapters(archive,"","rewrite",true,loaded.getJSONObject("filter")));
        assertEquals(first,store.targetIds(archive,"","rewrite",true,loaded.getJSONObject("filter")).get(0).longValue());
        loaded.getJSONObject("filter").put("mode","any");
        assertEquals(2,store.countChapters(archive,"","rewrite",true,loaded.getJSONObject("filter")));
        assertTrue(store.countTargetChars(archive,"","rewrite",true,loaded.getJSONObject("filter"))>0);
        templates.delete(archive);assertNull(templates.load(archive));store.close();
    }

    @Test public void invalidFormatCannotReplaceSavedTemplate() throws Exception {
        Context context=RuntimeEnvironment.getApplication();RewriteTemplate templates=new RewriteTemplate(context);long archive=987654;
        JSONObject good=new JSONObject().put("format","novel-memory-rewrite-template").put("version",1).put("name","合法")
                .put("filter",new JSONObject().put("mode","all").put("tags",new JSONArray()))
                .put("rewrite",new JSONObject());
        templates.save(archive,good);
        try{templates.save(archive,new JSONObject().put("format","other").put("name","错误"));fail("Invalid format accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("通用"));}
        assertEquals("合法",templates.load(archive).getString("name"));templates.delete(archive);
    }
}
