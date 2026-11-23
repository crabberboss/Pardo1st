package com.novelmemory.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class StoreTest {
    @Test public void commonChapterHeadingsAreRecognized() {
        assertTrue(Store.heading("第123章 新领地"));
        assertTrue(Store.heading("【第十章】 密室"));
        assertTrue(Store.heading("Chapter XII: Departure"));
        assertTrue(Store.heading("123. 归途"));
        assertFalse(Store.heading("他提到了第123章，但这只是正文。"));
    }

    @Test public void importedLocalFallbackIsEligibleForMissingSummaryRepair() throws Exception {
        Store store=freshStore();
        long archive=store.importText("兜底.txt",new ByteArrayInputStream("第一章 开端\n甲进入密室。".getBytes(StandardCharsets.UTF_8)));
        long chapter=store.chapters(archive,0,40,"").getJSONObject(0).getLong("id");
        store.setSummary(chapter,"关键词：密室\n剧情概览：甲进入密室。\n生成说明：模型未返回正式答案，本章由本地零 Token 抽取兜底生成。","");
        assertEquals(1,store.countChapters(archive,"","summary",true));
        assertEquals(1,store.targetIds(archive,"","summary",true).size());
        store.close();
    }

    private Store freshStore(){
        Context context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("novel_memory_mobile.db");
        return new Store(context);
    }

    @Test public void rewriteBrowseFiltersAndPartialDraftStaysSeparate() throws Exception {
        Store store=freshStore();
        long archive=store.importText("筛选.txt",new ByteArrayInputStream("第1章 密室\n两人交谈。\n第2章 旷野\n一人出发。".getBytes(StandardCharsets.UTF_8)));
        JSONArray chapters=store.chapters(archive,0,40,"");
        long first=chapters.getJSONObject(0).getLong("id");
        assertEquals(1,store.rewriteBrowse(archive,"密室",0,40).getInt("total"));
        JSONArray catalog=store.readerCatalog(archive).getJSONArray("rows");
        assertEquals(2,catalog.length());
        assertEquals(first,catalog.getJSONObject(0).getLong("id"));
        assertEquals(false,catalog.getJSONObject(0).getBoolean("rewritten"));
        assertTrue(catalog.getJSONObject(0).getInt("source_chars")>0);
        store.setRewritePartial(first,"仅有半章");
        assertEquals("仅有半章",store.rewritePartial(first));
        assertEquals(4,store.chapter(first).getInt("partial_chars"));
        store.setRewrite(first,"正式完成的正文","提示词","");
        assertEquals("",store.rewritePartial(first));
        assertEquals("正式完成的正文",store.readerRewriteSlice(first,0,6000).getString("text"));
        assertTrue(store.readerCatalog(archive).getJSONArray("rows").getJSONObject(0).getBoolean("rewritten"));
        store.close();
    }

    @Test public void textImportAndDesktopKnowledgeBaseRoundTrip() throws Exception {
        Store store=freshStore();
        String text="第1章 开端\n甲进入密室。\n第2章 远行\n乙到达旷野。\n";
        long first=store.importText("测试.txt",new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2,store.chapters(first,0,40,"").length());
        long chapterId=store.chapters(first,0,40,"").getJSONObject(0).getLong("id");
        store.setSummary(chapterId,"关键词：密室\n剧情概览：甲进入密室。","");
        store.rebuildOverview(first);
        assertTrue(store.archive(first).getString("overview").contains("甲进入密室"));
        ByteArrayOutputStream file=new ByteArrayOutputStream();store.exportKnowledgeBase(first,file);
        JSONObject packageJson=new JSONObject(file.toString("UTF-8"));
        assertEquals("novel-memory-archive",packageJson.getString("format"));
        assertEquals(4,packageJson.getInt("version"));
        assertEquals(2,packageJson.getJSONArray("chunks").length());
        long second=store.importKnowledgeBase(new ByteArrayInputStream(file.toByteArray()));
        assertEquals(2,store.chapters(second,0,40,"").length());
        assertTrue(store.chapters(second,0,40,"").getJSONObject(0).getString("summary").contains("密室"));
        store.close();
    }

    @Test public void legacyV2KnowledgeBaseImportsWithoutChangingOtherArchives() throws Exception {
        Store store=freshStore();
        long existing=store.importText("保留.txt",new ByteArrayInputStream("第一章 保留\n原文。".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream file=new ByteArrayOutputStream();store.exportKnowledgeBase(existing,file);
        JSONObject oldPackage=new JSONObject(file.toString("UTF-8"));
        oldPackage.put("version",2);
        oldPackage.remove("special_tags");oldPackage.remove("special_tag_hits");oldPackage.remove("chapter_source_edits");
        long imported=store.importKnowledgeBase(new ByteArrayInputStream(oldPackage.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(1,store.chapters(imported,0,40,"").length());
        assertEquals(1,store.chapters(existing,0,40,"").length());
        store.deleteArchive(imported);
        assertEquals(1,store.archives().length());
        assertEquals(1,store.chapters(existing,0,40,"").length());
        try { store.archive(imported);fail("Deleted archive should not remain"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("不存在")); }
        store.close();
    }

    @Test public void chapterSourceEditAndDeleteAreScopedToSelectedArchive() throws Exception {
        Store store=freshStore();String source="第1章 开端\n甲进入密室。\n第2章 远行\n乙到达旷野。\n";
        long first=store.importText("原稿.txt",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        long other=store.importText("另一本.txt",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        long chapter=store.chapters(first,0,40,"").getJSONObject(0).getLong("id");
        long otherChapter=store.chapters(other,0,40,"").getJSONObject(0).getLong("id");
        store.setSummary(chapter,"关键词：密室\n剧情概览：甲进入密室。","");
        String original=store.chapter(chapter).getString("text");
        try{store.saveSourceSlice(other,chapter,0,original,"改错书");fail("Cross-archive edit accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("不属于"));}
        store.saveSourceSlice(first,chapter,0,original,original.replace("密室","庭院"));
        assertTrue(store.chapter(chapter).getString("text").contains("庭院"));
        assertEquals("",store.chapter(chapter).getString("summary"));
        assertEquals(1,new JSONArray(store.archive(first).getString("edits_json")).length());
        assertTrue(store.chapter(otherChapter).getString("text").contains("密室"));
        try{store.saveSourceSlice(first,chapter,0,original,"过期修改");fail("Stale edit accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("已变化"));}
        ByteArrayOutputStream exported=new ByteArrayOutputStream();store.exportKnowledgeBase(first,exported);
        assertTrue(exported.toString("UTF-8").contains("庭院"));
        try{store.deleteChapter(other,chapter);fail("Cross-archive delete accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("不属于"));}
        store.deleteChapter(first,chapter);assertEquals(1,store.chapterCount(first));
        assertEquals(2,store.chapterCount(other));
        assertEquals(0,new JSONArray(store.archive(first).getString("edits_json")).length());
        store.close();
    }

    @Test public void rewriteGlobalPromptPersistsPerArchiveIncludingEmptyValue() throws Exception {
        Store store=freshStore();long a=store.createArchive("甲书"),b=store.createArchive("乙书");
        assertEquals("旧版提示词",store.rewriteGlobalPrompt(a,"旧版提示词"));
        assertEquals("",store.rewriteGlobalPrompt(b,""));
        store.saveRewriteGlobalPrompt(a,"只改甲书");
        assertEquals("只改甲书",store.rewriteGlobalPrompt(a,"不应重新导入"));
        assertEquals("",store.rewriteGlobalPrompt(b,"乙书旧值"));
        store.saveRewriteGlobalPrompt(a,"");store.close();
        Store reopened=new Store(RuntimeEnvironment.getApplication());
        assertEquals("",reopened.rewriteGlobalPrompt(a,"不能复活旧值"));
        assertEquals("",reopened.rewriteGlobalPrompt(b,"不能串库"));reopened.close();
    }

    @Test public void chapterPromptAndSpecialTagsStayScopedAndRoundTrip() throws Exception {
        Store store=freshStore();
        String source="第一章 密室\n甲与乙商议逃离。\n第二章 旷野\n丙独自远行。\n";
        long a=store.importText("甲书.txt",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        long b=store.importText("乙书.txt",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        long chapter=store.chapters(a,0,40,"").getJSONObject(0).getLong("id");
        long otherChapter=store.chapters(b,0,40,"").getJSONObject(0).getLong("id");
        store.saveChapterPrompt(a,chapter,"仅此章采用悬疑叙事","保留原对话");
        assertEquals("仅此章采用悬疑叙事",store.chapter(chapter).getString("chapter_prompt"));
        assertEquals("",store.chapter(otherChapter).getString("chapter_prompt"));
        JSONObject state=store.saveSpecialTag(a,0,"密室双人","#a45e4f","两人位于封闭空间并交谈");
        long tagId=state.getJSONArray("tags").getJSONObject(0).getLong("id");
        store.setSpecialHit(a,chapter,tagId,"甲乙在密室讨论逃离","甲与乙商议",false);
        store.replaceModelSpecialHits(a,chapter,new JSONArray().put(new JSONObject()
                .put("tag_id",tagId).put("description","模型不应覆盖人工说明")));
        assertEquals("甲乙在密室讨论逃离",store.chapterWithSpecialHits(chapter)
                .getJSONArray("special_hits").getJSONObject(0).getString("description"));
        long modelTag=store.saveSpecialTag(a,0,"紧急会谈","#438d96","发生重要会谈")
                .getJSONArray("tags").getJSONObject(1).getLong("id");
        store.replaceModelSpecialHits(a,chapter,new JSONArray().put(new JSONObject()
                .put("tag_id",modelTag).put("description","两人在密室紧急会谈")));
        assertEquals(2,store.chapterWithSpecialHits(chapter).getJSONArray("special_hits").length());
        store.replaceModelSpecialHits(a,chapter,new JSONArray());
        assertEquals(1,store.chapterWithSpecialHits(chapter).getJSONArray("special_hits").length());
        assertEquals(1,store.chapterWithSpecialHits(chapter).getJSONArray("special_hits").length());
        assertEquals(0,store.chapterWithSpecialHits(otherChapter).getJSONArray("special_hits").length());
        JSONObject filter=new JSONObject().put("mode","all").put("tags",new JSONArray().put("密室双人"));
        assertEquals(1,store.countChapters(a,"","rewrite",false,filter));
        assertEquals(0,store.countChapters(b,"","rewrite",false,filter));
        ByteArrayOutputStream out=new ByteArrayOutputStream();store.exportKnowledgeBase(a,out);
        long imported=store.importKnowledgeBase(new ByteArrayInputStream(out.toByteArray()));
        long importedChapter=store.chapters(imported,0,40,"").getJSONObject(0).getLong("id");
        assertEquals("密室双人",store.chapterWithSpecialHits(importedChapter).getJSONArray("special_hits").getJSONObject(0).getString("name"));
        assertEquals(1,store.countChapters(imported,"","rewrite",false,filter));
        store.deleteSpecialTag(a,tagId);
        assertEquals(0,store.countChapters(a,"","rewrite",false,filter));
        assertEquals(1,store.countChapters(imported,"","rewrite",false,filter));
        store.close();
    }

    @Test public void chatSessionsEditsAndRegenerationStayInPlace() throws Exception {
        Store store=freshStore();ChatData chat=new ChatData(store);
        long archive=store.importText("对话.txt",new ByteArrayInputStream("第一章 开端\n甲。".getBytes(StandardCharsets.UTF_8)));
        long first=chat.ensureSession(archive,"qa",0,0).getLong("id");
        chat.appendPair(first,"旧问题","旧回答",0,0);
        chat.appendPair(first,"后续问题","后续回答",0,0);
        long second=chat.createSession(archive,"qa",0,"另一个存档").getLong("id");
        chat.appendPair(second,"另一问","另一答",0,0);
        JSONArray before=chat.messages(first);long answerId=before.getJSONObject(1).getLong("id");
        chat.replaceResponse(first,answerId,"改过的问题","重生成的回答",0);
        JSONArray after=chat.messages(first);
        assertEquals(4,after.length());
        assertEquals(answerId,after.getJSONObject(1).getLong("id"));
        assertEquals("改过的问题",after.getJSONObject(0).getString("content"));
        assertEquals("重生成的回答",after.getJSONObject(1).getString("content"));
        assertEquals("后续回答",after.getJSONObject(3).getString("content"));
        chat.editMessage(first,answerId,"人工编辑后的回答");
        assertEquals("人工编辑后的回答",chat.historyBefore(first,0,10).getJSONObject(1).getString("content"));
        assertEquals(2,chat.messages(second).length());
        chat.clearSession(first);assertEquals(0,chat.messages(first).length());assertEquals(2,chat.messages(second).length());
        store.close();
    }

    @Test public void roleplayCardAndArchiveDeletionAreScoped() throws Exception {
        Store store=freshStore();ChatData chat=new ChatData(store);
        long target=store.createArchive("待删除"),other=store.createArchive("保留");
        JSONObject card=chat.saveProfile(target,new JSONObject().put("name","甲").put("character_name","甲")
                .put("first_message","你好").put("timeline_start_cutoff",3));
        long session=chat.createSession(target,"roleplay",card.getLong("id"),"场景一").getLong("id");
        assertEquals(3,chat.session(session).getLong("cutoff"));
        assertEquals("你好",chat.messages(session).getJSONObject(0).getString("content"));
        store.deleteArchive(target);
        assertEquals(1,store.archives().length());
        assertEquals(other,store.archives().getJSONObject(0).getLong("id"));
        assertEquals(0,chat.profiles(other).length());
        try{chat.session(session);fail("Deleted session must not remain");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("不存在"));}
        store.close();
    }

    @Test public void upgradeFromPreviousApkKeepsOldConversation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("novel_memory_mobile.db");
        SQLiteDatabase old=context.openOrCreateDatabase("novel_memory_mobile.db",Context.MODE_PRIVATE,null);
        old.execSQL("CREATE TABLE archives(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,description TEXT DEFAULT '',overview TEXT DEFAULT '',tags_json TEXT DEFAULT '[]',hits_json TEXT DEFAULT '[]',edits_json TEXT DEFAULT '[]',created_at INTEGER DEFAULT 0)");
        old.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL)");
        old.execSQL("INSERT INTO archives(id,name) VALUES(1,'旧知识库')");
        old.execSQL("INSERT INTO messages(archive_id,role,content) VALUES(1,'user','旧问题'),(1,'assistant','旧回答')");
        old.setVersion(1);old.close();
        Store upgraded=new Store(context);ChatData chat=new ChatData(upgraded);
        JSONArray sessions=chat.sessions(1,"qa",0);assertEquals(1,sessions.length());
        assertEquals("原有对话",sessions.getJSONObject(0).getString("name"));
        long sessionId=sessions.getJSONObject(0).getLong("id");
        assertEquals("旧问题",chat.messages(sessionId).getJSONObject(0).getString("content"));
        assertEquals("旧回答",chat.messages(sessionId).getJSONObject(1).getString("content"));
        upgraded.close();
    }

    @Test public void upgradeFromV2AddsPerArchiveRewritePromptWithoutLosingArchives() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("novel_memory_mobile.db");
        SQLiteDatabase old=context.openOrCreateDatabase("novel_memory_mobile.db",Context.MODE_PRIVATE,null);
        old.execSQL("CREATE TABLE archives(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,description TEXT DEFAULT '',overview TEXT DEFAULT '',tags_json TEXT DEFAULT '[]',hits_json TEXT DEFAULT '[]',edits_json TEXT DEFAULT '[]',created_at INTEGER DEFAULT 0)");
        old.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY,archive_id INTEGER NOT NULL)");
        old.execSQL("INSERT INTO archives(id,name) VALUES(1,'旧甲'),(2,'旧乙')");old.setVersion(2);old.close();
        Store upgraded=new Store(context);
        assertEquals("旧甲提示词",upgraded.rewriteGlobalPrompt(1,"旧甲提示词"));
        assertEquals("",upgraded.rewriteGlobalPrompt(2,""));
        assertEquals(2,upgraded.archives().length());upgraded.close();
    }

    @Test public void rewriteBundleRoundTripAndKeywordSelection() throws Exception {
        Store store=freshStore();
        long first=store.importText("测试.txt",new ByteArrayInputStream("第1章 开端\n甲进入密室。\n第2章 远行\n乙到达旷野。".getBytes(StandardCharsets.UTF_8)));
        long chapterId=store.chapters(first,0,40,"").getJSONObject(0).getLong("id");
        store.setSummary(chapterId,"关键词：密室\n剧情概览：甲进入密室。","");
        assertEquals(1,store.targetIds(first,"密室","rewrite",true).size());
        store.setRewrite(chapterId,"改写后的密室","保持悬疑","加强动作描写");
        assertEquals(0,store.targetIds(first,"密室","rewrite",true).size());
        ByteArrayOutputStream kb=new ByteArrayOutputStream();store.exportKnowledgeBase(first,kb);
        ByteArrayOutputStream rewrites=new ByteArrayOutputStream();store.exportRewrites(first,rewrites);
        assertEquals("novel-memory-rewrites",new JSONObject(rewrites.toString("UTF-8")).getString("format"));
        long second=store.importKnowledgeBase(new ByteArrayInputStream(kb.toByteArray()));
        assertEquals(1,store.importRewrites(second,new ByteArrayInputStream(rewrites.toByteArray())));
        long copied=store.chapters(second,0,40,"").getJSONObject(0).getLong("id");
        assertEquals("改写后的密室",store.chapter(copied).getString("rewrite"));
        assertEquals("加强动作描写",store.chapter(copied).getString("extra_instruction"));
        assertEquals(0,new JSONObject(kb.toString("UTF-8")).getJSONArray("rewrites").length());
        store.close();
    }

    @Test public void desktopMultiChunkChapterIsReassembledForRewriteHash() throws Exception {
        Store store=freshStore();
        long first=store.importText("两段.txt",new ByteArrayInputStream("第一章 开端\n甲。\n乙。".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream kb=new ByteArrayOutputStream();store.exportKnowledgeBase(first,kb);
        JSONObject packageJson=new JSONObject(kb.toString("UTF-8"));
        JSONObject original=packageJson.getJSONArray("chunks").getJSONObject(0);
        String[] halves=original.getString("text").split("甲。\\n",2);
        String a=halves[0]+"甲。\n",b=halves[1];
        packageJson.put("chunks",new org.json.JSONArray()
                .put(new JSONObject(original.toString()).put("text",a).put("ord",0))
                .put(new JSONObject(original.toString()).put("text",b).put("ord",1)));
        long second=store.importKnowledgeBase(new ByteArrayInputStream(packageJson.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(1,store.chapters(second,0,40,"").length());
        long id=store.chapters(second,0,40,"").getJSONObject(0).getLong("id");
        assertEquals(a+"\n\n"+b,store.chapter(id).getString("text"));
        store.close();
    }

    @Test public void desktopSpecialTagsCanFilterWholeBookRewrite() throws Exception {
        Store store=freshStore();
        long first=store.importText("场景.txt",new ByteArrayInputStream("第1章 密室\n两人对话。\n第2章 旷野\n众人行军。".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream kb=new ByteArrayOutputStream();store.exportKnowledgeBase(first,kb);
        JSONObject packageJson=new JSONObject(kb.toString("UTF-8"));
        packageJson.put("special_tags",new org.json.JSONArray().put(new JSONObject().put("id",7).put("name","封闭空间")));
        String title=packageJson.getJSONArray("chunks").getJSONObject(0).getString("title");
        packageJson.put("special_tag_hits",new org.json.JSONArray().put(new JSONObject()
                .put("tag_id",7).put("document_id",1).put("chapter_title",title).put("description","两人在密室对话")));
        long second=store.importKnowledgeBase(new ByteArrayInputStream(packageJson.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(1,store.targetIds(second,"封闭空间","rewrite",false).size());
        store.close();
    }

    @Test public void customSplitPreviewAndApplyPreserveText() throws Exception {
        Store store=freshStore();
        long archive=store.importText("非标准章节.txt",new ByteArrayInputStream("01 开端\n甲。\n02 远行\n乙。\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1,store.chapters(archive,0,40,"").length());
        String expression="^\\d{2} .+$";
        JSONObject preview=store.previewSplit(archive,expression);
        assertEquals(1,preview.getInt("current"));assertEquals(2,preview.getInt("proposed"));
        assertEquals(2,store.applySplit(archive,expression));
        JSONArray chapters=store.chapters(archive,0,40,"");
        assertEquals("01 开端",chapters.getJSONObject(0).getString("title"));
        assertEquals("02 远行",chapters.getJSONObject(1).getString("title"));
        String whole=store.chapter(chapters.getJSONObject(0).getLong("id")).getString("text")
                +store.chapter(chapters.getJSONObject(1).getLong("id")).getString("text");
        assertEquals("01 开端\n甲。\n02 远行\n乙。\n",whole);
        store.setSummary(chapters.getJSONObject(0).getLong("id"),"关键词：开端\n剧情概览：甲。","");
        try{store.applySplit(archive,expression);fail("Generated memory must prevent destructive split");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("已有总结"));}
        store.close();
    }

    @Test public void utf8DetectionDoesNotTreatCutOffSampleAsGbk() throws Exception {
        Store store=freshStore();
        String prefix="第1章 开端\n";
        String novel=prefix+"a".repeat(32767-prefix.getBytes(StandardCharsets.UTF_8).length)
                +"甲\n第2章 远行\n乙。\n";
        long archive=store.importText("编码边界.txt",new ByteArrayInputStream(novel.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2,store.chapters(archive,0,40,"").length());
        assertEquals("第2章 远行",store.chapters(archive,0,40,"").getJSONObject(1).getString("title"));
        store.close();
    }

    @Test public void oversizedSingleChapterCanBePreviewedAndResplit() throws Exception {
        Store store=freshStore();
        String novel="01 开端\n"+"甲".repeat(1_000_000)+"\n02 远行\n乙。\n";
        long archive=store.importText("大章节.txt",new ByteArrayInputStream(novel.getBytes(StandardCharsets.UTF_8)));
        JSONArray rows=store.chapters(archive,0,40,"");assertEquals(1,rows.length());
        long first=rows.getJSONObject(0).getLong("id");
        assertEquals(12000,store.chapter(first).getString("text").length());
        assertEquals(12000,store.sourceSlice(first,500000,12000).getString("text").length());
        assertTrue(store.summarySample(first).contains("原文总长"));
        String economySample=store.summarySample(first,6000);
        assertTrue(economySample.contains("原文总长"));
        assertTrue(economySample.length()<6200);
        assertEquals(2,store.previewSplit(archive,"^\\d{2} .+$").getInt("proposed"));
        assertEquals(2,store.applySplit(archive,"^\\d{2} .+$"));
        assertEquals(2,store.chapters(archive,0,40,"").length());
        ByteArrayOutputStream exported=new ByteArrayOutputStream();store.exportKnowledgeBase(archive,exported);
        assertEquals(2,new JSONObject(exported.toString("UTF-8")).getJSONArray("chunks").length());
        store.close();
    }

    @Test public void optionalLocalNovelRegression() throws Exception {
        String path=System.getenv("NOVEL_MEMORY_REGRESSION_FILE");
        Assume.assumeTrue(path!=null&&new File(path).isFile());
        Store store=freshStore();
        try(FileInputStream input=new FileInputStream(path)){
            long archive=store.importText("实际小说.txt",input);
            assertTrue("Actual novel should be split into hundreds of chapters",store.countChapters(archive,"","summary",false)>800);
            long chars=store.countTargetChars(archive,"","summary",false);
            assertTrue("Correct decoding should keep the novel near its UTF-8 character count",chars>3_000_000&&chars<4_000_000);
            assertFalse(store.diagnostics(archive).getBoolean("possible_mojibake"));
            JSONArray first=store.chapters(archive,0,5,"");boolean found=false;
            for(int i=0;i<first.length();i++)if("第1章 开端".equals(first.getJSONObject(i).getString("title")))found=true;
            assertTrue("First numbered chapter should be present",found);
        }finally{store.close();}
    }

    @Test public void optionalDesktopV2ArchiveRegression() throws Exception {
        String path=System.getenv("NOVEL_MEMORY_V2_ARCHIVE_FILE");
        Assume.assumeTrue(path!=null&&new File(path).isFile());
        Store store=freshStore();
        try(FileInputStream input=new FileInputStream(path)){
            long archive=store.importKnowledgeBase(input);
            assertTrue(store.countChapters(archive,"","summary",false)>1500);
            assertFalse(store.chapters(archive,0,1,"").getJSONObject(0).getString("summary").isEmpty());
        }finally{store.close();}
    }

    @Test public void damagedOldArchiveIsFlaggedInsteadOfResplit() throws Exception {
        Store store=freshStore();
        String damaged="乱码"+"\uFFFD".repeat(12)+"甲".repeat(600_000);
        long archive=store.importText("旧乱码.txt",new ByteArrayInputStream(damaged.getBytes(StandardCharsets.UTF_8)));
        assertTrue(store.diagnostics(archive).getBoolean("possible_mojibake"));
        try{store.previewSplit(archive,"");fail("Damaged source cannot be repaired by splitting");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("重新导入"));}
        store.close();
    }
}
