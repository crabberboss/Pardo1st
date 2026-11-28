package com.novelmemory.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.content.ContentValues;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class VectorMemoryTest {
    @Test public void configurationIsPerArchiveAndDefaultsOff() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("novel_memory_mobile.db");
        Store store=new Store(context);VectorMemory vectors=new VectorMemory(store,new Api(context));
        long first=store.createArchive("甲"),second=store.createArchive("乙");
        assertFalse(vectors.config(first).getBoolean("enabled"));
        vectors.saveConfig(first,true,"siliconflow","BAAI/bge-m3");
        assertTrue(vectors.config(first).getBoolean("enabled"));
        assertFalse(vectors.config(second).getBoolean("enabled"));
        assertEquals(0,vectors.search(second,"测试",0).size());
        try{vectors.build(second,"siliconflow","BAAI/bge-m3",new java.util.concurrent.atomic.AtomicBoolean(false),(done,total,detail)->{});fail("Expected missing summary error");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("章节总结"));}
        store.deleteArchive(first);
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM vector_configs WHERE archive_id=?",new String[]{Long.toString(first)})){
            assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));
        }
        store.close();
    }

    @Test public void originalVectorsRoundTripWithKnowledgeBaseWithoutKeys() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("novel_memory_mobile.db");
        Store store=new Store(context);VectorMemory vectors=new VectorMemory(store,new Api(context));
        long first=store.importText("小说.txt",new ByteArrayInputStream("第一章 起点\n甲在古屋找到地图。".getBytes(StandardCharsets.UTF_8)));
        long chapter=store.chapters(first,0,40,"").getJSONObject(0).getLong("id");
        String source=store.vectorSource(chapter);byte[] digest=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format("%02x",b&255));
        byte[] embedding=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1).putFloat(0).array();
        ContentValues row=new ContentValues();row.put("archive_id",first);row.put("chapter_id",chapter);row.put("part",0);row.put("char_start",0);row.put("char_end",source.length());row.put("provider","siliconflow");row.put("model","BAAI/bge-m3");row.put("source_hash",hash.toString());row.put("dimensions",2);row.put("vector",embedding);
        store.getWritableDatabase().insertOrThrow("source_vectors",null,row);
        vectors.saveSourceConfig(first,true,"siliconflow","BAAI/bge-m3");
        ByteArrayOutputStream output=new ByteArrayOutputStream();store.exportKnowledgeBase(first,output);
        JSONObject archive=new JSONObject(output.toString("UTF-8"));
        assertEquals(5,archive.getInt("version"));
        assertEquals(1,archive.getJSONObject("source_vector_index").getJSONArray("records").length());
        assertFalse(archive.toString().contains("api_key"));
        long second=store.importKnowledgeBase(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(1,vectors.sourceConfig(second).getInt("indexed_parts"));
        assertTrue(vectors.sourceConfig(second).getBoolean("enabled"));
        store.close();
    }
}
