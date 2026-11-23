package com.novelmemory.mobile;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.JsonReader;
import android.util.JsonWriter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class Store extends SQLiteOpenHelper {
    private static final Pattern HEADING = Pattern.compile("^(?:第\\s*[一二三四五六七八九十百千万零〇两壹贰叁肆伍陆柒捌玖拾佰仟萬0-9０-９]+\\s*[章节卷回部集篇幕].{0,90}|[一二三四五六七八九十百千万零〇两壹贰叁肆伍陆柒捌玖拾佰仟萬0-9０-９]{1,8}\\s*[章节卷回部集篇幕].{0,90}|卷\\s*[一二三四五六七八九十百千万零〇两壹贰叁肆伍陆柒捌玖拾佰仟萬0-9０-９]+.{0,90}|[一二三四五六七八九十百零〇两壹贰叁肆伍陆柒捌玖拾佰]{1,6}|(?:chapter|chap\\.?)\\s*(?:\\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)(?:\\s*[:：.\\-—])?\\s*.{0,90}|(?:序章|序幕|楔子|引子|前言|尾声|后记|番外|终章|外传|插曲).{0,90}|\\d{1,5}\\s*[.、．:：\\-—]\\s*.{1,90})$", Pattern.CASE_INSENSITIVE);

    Store(Context context) { super(context, "novel_memory_mobile.db", null, 4); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE archives(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,description TEXT DEFAULT '',overview TEXT DEFAULT '',tags_json TEXT DEFAULT '[]',hits_json TEXT DEFAULT '[]',edits_json TEXT DEFAULT '[]',rewrite_global_prompt TEXT,created_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE documents(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,source_id INTEGER NOT NULL,filename TEXT,title TEXT,char_count INTEGER,UNIQUE(archive_id,source_id))");
        db.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,doc_id INTEGER NOT NULL,ord INTEGER NOT NULL,title TEXT NOT NULL,text TEXT NOT NULL,char_start INTEGER DEFAULT 0,char_end INTEGER DEFAULT 0,token_count INTEGER DEFAULT 0,summary TEXT DEFAULT '',tags TEXT DEFAULT '',error TEXT DEFAULT '',rewrite TEXT DEFAULT '',rewrite_partial TEXT DEFAULT '',chapter_prompt TEXT DEFAULT '',extra_instruction TEXT DEFAULT '',rewrite_status TEXT DEFAULT '未改写')");
        db.execSQL("CREATE INDEX chapter_order ON chapters(archive_id,doc_id,ord)");
        db.execSQL("CREATE TABLE summaries(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,scope TEXT NOT NULL,source_key TEXT NOT NULL,title TEXT,content TEXT,UNIQUE(archive_id,scope,source_key))");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,session_id INTEGER NOT NULL DEFAULT 0,role TEXT NOT NULL,content TEXT NOT NULL,timeline_before INTEGER NOT NULL DEFAULT 0,timeline_after INTEGER NOT NULL DEFAULT 0)");
        createChatTables(db);
    }

    private static void createChatTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,mode TEXT NOT NULL,profile_id INTEGER NOT NULL DEFAULT 0,name TEXT NOT NULL,cutoff INTEGER NOT NULL DEFAULT 0,start_cutoff INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_sessions_scope ON chat_sessions(archive_id,mode,profile_id,updated_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_session ON messages(session_id,id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS roleplay_profiles(id INTEGER PRIMARY KEY AUTOINCREMENT,archive_id INTEGER NOT NULL,name TEXT NOT NULL,data_json TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_presets(archive_id INTEGER PRIMARY KEY,config_json TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<1||newVersion!=4)throw new IllegalStateException("Unsupported database schema");
        if(oldVersion<2){
            db.execSQL("ALTER TABLE messages ADD COLUMN session_id INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN timeline_before INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN timeline_after INTEGER NOT NULL DEFAULT 0");
            createChatTables(db);
            long now=System.currentTimeMillis()/1000;
            try(Cursor c=db.rawQuery("SELECT DISTINCT archive_id FROM messages WHERE session_id=0",null)) {
                while(c.moveToNext()) {
                    long archiveId=c.getLong(0);
                    android.content.ContentValues values=new android.content.ContentValues();
                    values.put("archive_id",archiveId);values.put("mode","qa");values.put("profile_id",0);
                    values.put("name","原有对话");values.put("created_at",now);values.put("updated_at",now);
                    long sessionId=db.insertOrThrow("chat_sessions",null,values);
                    db.execSQL("UPDATE messages SET session_id=? WHERE archive_id=? AND session_id=0",new Object[]{sessionId,archiveId});
                }
            }
        }
        if(oldVersion<3)db.execSQL("ALTER TABLE archives ADD COLUMN rewrite_global_prompt TEXT");
        if(oldVersion<4)try(Cursor chapters=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='chapters'",null)){
            if(chapters.moveToFirst())db.execSQL("ALTER TABLE chapters ADD COLUMN rewrite_partial TEXT DEFAULT ''");
        }
    }

    static String s(Cursor c, String key) { int i=c.getColumnIndexOrThrow(key); return c.isNull(i)?"":c.getString(i); }
    static long n(Cursor c, String key) { return c.getLong(c.getColumnIndexOrThrow(key)); }

    synchronized long createArchive(String name) {
        SQLiteDatabase db=getWritableDatabase();
        android.content.ContentValues v=new android.content.ContentValues();
        v.put("name", name.trim().isEmpty()?"未命名知识库":name.trim());
        v.put("created_at", System.currentTimeMillis()/1000);
        return db.insertOrThrow("archives",null,v);
    }

    synchronized JSONArray archives() throws Exception {
        JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT a.id,a.name,a.description,COUNT(ch.id) count FROM archives a LEFT JOIN chapters ch ON ch.archive_id=a.id GROUP BY a.id ORDER BY a.id DESC",null)) {
            while(c.moveToNext()) out.put(new JSONObject().put("id",n(c,"id")).put("name",s(c,"name")).put("description",s(c,"description")).put("count",n(c,"count")));
        }
        return out;
    }

    synchronized JSONObject archive(long id) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM archives WHERE id=?",new String[]{Long.toString(id)})) {
            if(!c.moveToFirst()) throw new IllegalArgumentException("知识库不存在");
            return new JSONObject().put("id",id).put("name",s(c,"name")).put("description",s(c,"description"))
                    .put("overview",s(c,"overview")).put("tags_json",s(c,"tags_json")).put("hits_json",s(c,"hits_json"))
                    .put("edits_json",s(c,"edits_json")).put("rewrite_global_prompt",s(c,"rewrite_global_prompt"));
        }
    }

    synchronized void deleteArchive(long id) throws Exception {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            archive(id);
            String[] args={Long.toString(id)};
            db.delete("messages","archive_id=?",args);
            db.delete("chat_sessions","archive_id=?",args);
            db.delete("roleplay_profiles","archive_id=?",args);
            db.delete("chat_presets","archive_id=?",args);
            db.delete("summaries","archive_id=?",args);
            db.delete("chapters","archive_id=?",args);
            db.delete("documents","archive_id=?",args);
            db.delete("archives","id=?",args);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized String rewriteGlobalPrompt(long archiveId,String legacyPrompt) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT rewrite_global_prompt FROM archives WHERE id=?",new String[]{Long.toString(archiveId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("知识库不存在");
            if(!c.isNull(0))return c.getString(0);
        }
        String migrated=legacyPrompt==null?"":legacyPrompt;
        saveRewriteGlobalPrompt(archiveId,migrated);
        return migrated;
    }

    synchronized void saveRewriteGlobalPrompt(long archiveId,String prompt) throws Exception {
        archive(archiveId);
        android.content.ContentValues v=new android.content.ContentValues();v.put("rewrite_global_prompt",prompt==null?"":prompt);
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(archiveId)});
    }

    synchronized void saveChapterPrompt(long archiveId,long chapterId,String prompt,String extra) throws Exception {
        JSONObject chapter=chapter(chapterId);
        if(chapter.getLong("archive_id")!=archiveId)throw new IllegalArgumentException("章节不属于当前知识库");
        android.content.ContentValues v=new android.content.ContentValues();
        v.put("chapter_prompt",prompt==null?"":prompt);v.put("extra_instruction",extra==null?"":extra);
        getWritableDatabase().update("chapters",v,"id=? AND archive_id=?",new String[]{Long.toString(chapterId),Long.toString(archiveId)});
    }

    synchronized JSONObject specialTagState(long archiveId) throws Exception {
        JSONObject a=archive(archiveId);
        return new JSONObject().put("archive_id",archiveId)
                .put("tags",new JSONArray(a.getString("tags_json")))
                .put("hits",new JSONArray(a.getString("hits_json")));
    }

    synchronized JSONObject mergeSpecialTags(long archiveId,JSONArray incoming) throws Exception {
        JSONObject state=specialTagState(archiveId);JSONArray tags=state.getJSONArray("tags");
        java.util.HashMap<String,JSONObject> byName=new java.util.HashMap<>();long maxId=0,now=System.currentTimeMillis()/1000;
        for(int i=0;i<tags.length();i++){
            JSONObject tag=tags.getJSONObject(i);maxId=Math.max(maxId,tag.optLong("id"));
            byName.put(tag.optString("name").toLowerCase(java.util.Locale.ROOT),tag);
        }
        for(int i=0;i<incoming.length();i++){
            JSONObject item=incoming.getJSONObject(i);String name=item.optString("name","").trim();
            if(name.isEmpty())continue;
            String color=item.optString("color","#a557b8");
            if(!color.matches("#[0-9a-fA-F]{6}"))color="#a557b8";
            JSONObject tag=byName.get(name.toLowerCase(java.util.Locale.ROOT));
            if(tag==null){tag=new JSONObject().put("id",++maxId).put("archive_id",archiveId).put("created_at",now);tags.put(tag);byName.put(name.toLowerCase(java.util.Locale.ROOT),tag);}
            tag.put("name",name).put("color",color).put("recognition_prompt",item.optString("recognition_prompt",""))
                    .put("updated_at",now);
        }
        android.content.ContentValues v=new android.content.ContentValues();v.put("tags_json",tags.toString());
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(archiveId)});
        refreshSpecialTagColumns(archiveId);
        return specialTagState(archiveId);
    }

    synchronized void replaceModelSpecialHits(long archiveId,long chapterId,JSONArray matches) throws Exception {
        JSONObject chapter=chapter(chapterId),state=specialTagState(archiveId);
        if(chapter.getLong("archive_id")!=archiveId)throw new IllegalArgumentException("章节不属于当前知识库");
        JSONArray tags=state.getJSONArray("tags"),hits=state.getJSONArray("hits"),updated=new JSONArray();
        java.util.HashSet<Long> known=new java.util.HashSet<>(),manual=new java.util.HashSet<>(),added=new java.util.HashSet<>();
        for(int i=0;i<tags.length();i++)known.add(tags.getJSONObject(i).optLong("id"));
        long maxId=0,docId=chapter.getLong("doc_id");String title=chapter.getString("title");
        for(int i=0;i<hits.length();i++){
            JSONObject hit=hits.getJSONObject(i);maxId=Math.max(maxId,hit.optLong("id"));
            boolean same=hit.optLong("document_id")==docId&&title.equals(hit.optString("chapter_title"));
            if(same&&"manual".equals(hit.optString("source")))manual.add(hit.optLong("tag_id"));
            if(!same||"manual".equals(hit.optString("source")))updated.put(hit);
        }
        for(int i=0;i<matches.length();i++){
            JSONObject item=matches.optJSONObject(i);if(item==null)continue;
            long tagId=item.has("tag_id")?item.optLong("tag_id"):item.optLong("id");String description=item.optString("description","").trim();
            if(!known.contains(tagId)||manual.contains(tagId)||!added.add(tagId)||description.isEmpty()
                    ||description.codePointCount(0,description.length())>30)continue;
            String evidence=item.optString("evidence","").trim();
            if(evidence.length()>160)evidence=evidence.substring(0,160);
            updated.put(new JSONObject().put("id",++maxId).put("archive_id",archiveId).put("tag_id",tagId)
                    .put("document_id",docId).put("chapter_title",title).put("description",description)
                    .put("evidence",evidence).put("source","model").put("updated_at",System.currentTimeMillis()/1000));
        }
        android.content.ContentValues v=new android.content.ContentValues();v.put("hits_json",updated.toString());
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(archiveId)});
        refreshChapterTagColumn(archiveId,chapterId);
    }

    private void refreshChapterTagColumn(long archiveId,long chapterId) throws Exception {
        JSONObject chapter=chapter(chapterId),state=specialTagState(archiveId);
        JSONArray tags=state.getJSONArray("tags"),hits=state.getJSONArray("hits");
        java.util.HashMap<Long,String> names=new java.util.HashMap<>();
        for(int i=0;i<tags.length();i++){
            JSONObject tag=tags.getJSONObject(i);names.put(tag.optLong("id"),tag.optString("name"));
        }
        StringBuilder labels=new StringBuilder();
        for(int i=0;i<hits.length();i++){
            JSONObject hit=hits.getJSONObject(i);String name=names.get(hit.optLong("tag_id"));
            if(name!=null&&hit.optLong("document_id")==chapter.getLong("doc_id")
                    &&chapter.getString("title").equals(hit.optString("chapter_title")))
                labels.append('\u001f').append(name).append('\u001f');
        }
        android.content.ContentValues v=new android.content.ContentValues();v.put("tags",labels.toString());
        getWritableDatabase().update("chapters",v,"id=? AND archive_id=?",new String[]{Long.toString(chapterId),Long.toString(archiveId)});
    }

    synchronized JSONObject saveSpecialTag(long archiveId,long tagId,String name,String color,String prompt) throws Exception {
        name=name==null?"":name.trim();prompt=prompt==null?"":prompt.trim();
        if(name.isEmpty()||name.length()>40)throw new IllegalArgumentException("标签名称须为 1–40 字");
        if(prompt.isEmpty())throw new IllegalArgumentException("请填写模型识别提示词");
        if(!color.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("颜色格式无效");
        JSONObject state=specialTagState(archiveId);JSONArray tags=state.getJSONArray("tags");
        JSONArray updated=new JSONArray();boolean found=false;long maxId=0,now=System.currentTimeMillis()/1000;
        for(int i=0;i<tags.length();i++){
            JSONObject tag=tags.getJSONObject(i);long id=tag.optLong("id");maxId=Math.max(maxId,id);
            if(name.equalsIgnoreCase(tag.optString("name"))&&id!=tagId)throw new IllegalArgumentException("标签名称已存在");
            if(id==tagId){tag.put("name",name).put("color",color).put("recognition_prompt",prompt).put("updated_at",now);found=true;}
            updated.put(tag);
        }
        if(tagId>0&&!found)throw new IllegalArgumentException("标签不存在");
        if(!found)updated.put(new JSONObject().put("id",maxId+1).put("archive_id",archiveId)
                .put("name",name).put("color",color).put("recognition_prompt",prompt)
                .put("created_at",now).put("updated_at",now));
        android.content.ContentValues v=new android.content.ContentValues();v.put("tags_json",updated.toString());
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(archiveId)});
        if(found)refreshSpecialTagColumns(archiveId);
        return specialTagState(archiveId);
    }

    synchronized JSONObject deleteSpecialTag(long archiveId,long tagId) throws Exception {
        JSONObject state=specialTagState(archiveId);JSONArray tags=state.getJSONArray("tags"),hits=state.getJSONArray("hits");
        JSONArray nextTags=new JSONArray(),nextHits=new JSONArray();
        for(int i=0;i<tags.length();i++)if(tags.getJSONObject(i).optLong("id")!=tagId)nextTags.put(tags.get(i));
        for(int i=0;i<hits.length();i++)if(hits.getJSONObject(i).optLong("tag_id")!=tagId)nextHits.put(hits.get(i));
        android.content.ContentValues v=new android.content.ContentValues();v.put("tags_json",nextTags.toString());v.put("hits_json",nextHits.toString());
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(archiveId)});
        refreshSpecialTagColumns(archiveId);
        return specialTagState(archiveId);
    }

    synchronized JSONObject setSpecialHit(long archiveId,long chapterId,long tagId,String description,String evidence,boolean remove) throws Exception {
        JSONObject chapter=chapter(chapterId),state=specialTagState(archiveId);
        if(chapter.getLong("archive_id")!=archiveId)throw new IllegalArgumentException("章节不属于当前知识库");
        JSONArray tags=state.getJSONArray("tags"),hits=state.getJSONArray("hits");boolean known=false;long maxId=0;
        for(int i=0;i<tags.length();i++)if(tags.getJSONObject(i).optLong("id")==tagId)known=true;
        if(!known)throw new IllegalArgumentException("特殊标签不存在");
        description=description==null?"":description.trim();
        if(!remove&&(description.isEmpty()||description.codePointCount(0,description.length())>30))
            throw new IllegalArgumentException("情节说明须为 1–30 字");
        JSONArray updated=new JSONArray();
        for(int i=0;i<hits.length();i++){
            JSONObject hit=hits.getJSONObject(i);maxId=Math.max(maxId,hit.optLong("id"));
            if(hit.optLong("tag_id")==tagId&&hit.optLong("document_id")==chapter.getLong("doc_id")
                    &&chapter.getString("title").equals(hit.optString("chapter_title")))continue;
            updated.put(hit);
        }
        if(!remove)updated.put(new JSONObject().put("id",maxId+1).put("archive_id",archiveId).put("tag_id",tagId)
                .put("document_id",chapter.getLong("doc_id")).put("chapter_title",chapter.getString("title"))
                .put("description",description).put("evidence",evidence==null?"":evidence.trim())
                .put("source","manual").put("updated_at",System.currentTimeMillis()/1000));
        android.content.ContentValues v=new android.content.ContentValues();v.put("hits_json",updated.toString());
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(archiveId)});
        refreshChapterTagColumn(archiveId,chapterId);
        return specialTagState(archiveId);
    }

    synchronized JSONObject chapterWithSpecialHits(long chapterId) throws Exception {
        JSONObject chapter=chapter(chapterId),state=specialTagState(chapter.getLong("archive_id"));
        JSONArray tags=state.getJSONArray("tags"),hits=state.getJSONArray("hits"),matching=new JSONArray();
        for(int i=0;i<hits.length();i++){
            JSONObject hit=hits.getJSONObject(i);
            if(hit.optLong("document_id")!=chapter.getLong("doc_id")||!chapter.getString("title").equals(hit.optString("chapter_title")))continue;
            for(int j=0;j<tags.length();j++){
                JSONObject tag=tags.getJSONObject(j);
                if(tag.optLong("id")==hit.optLong("tag_id")){
                    matching.put(new JSONObject(hit.toString()).put("name",tag.optString("name"))
                            .put("color",tag.optString("color","#a45e4f")));break;
                }
            }
        }
        return chapter.put("special_hits",matching);
    }

    private void refreshSpecialTagColumns(long archiveId) throws Exception {
        JSONObject state=specialTagState(archiveId);JSONArray tags=state.getJSONArray("tags"),hits=state.getJSONArray("hits");
        java.util.HashMap<Long,String> names=new java.util.HashMap<>();
        for(int i=0;i<tags.length();i++){JSONObject t=tags.getJSONObject(i);names.put(t.optLong("id"),t.optString("name"));}
        java.util.HashMap<String,StringBuilder> labels=new java.util.HashMap<>();
        for(int i=0;i<hits.length();i++){
            JSONObject h=hits.getJSONObject(i);String name=names.get(h.optLong("tag_id"));
            if(name==null)continue;
            String key=h.optLong("document_id")+"\u0000"+h.optString("chapter_title");
            labels.computeIfAbsent(key,k->new StringBuilder()).append('\u001f').append(name).append('\u001f');
        }
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try(Cursor c=db.rawQuery("SELECT id,doc_id,title FROM chapters WHERE archive_id=?",new String[]{Long.toString(archiveId)})){
            while(c.moveToNext()){
                String key=n(c,"doc_id")+"\u0000"+s(c,"title");
                android.content.ContentValues v=new android.content.ContentValues();v.put("tags",labels.getOrDefault(key,new StringBuilder()).toString());
                db.update("chapters",v,"id=?",new String[]{Long.toString(n(c,"id"))});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    private void updateSourceMetadata(SQLiteDatabase db,long archiveId,long docId,String title,boolean deleted) throws Exception {
        String[] archiveArgs={Long.toString(archiveId)};
        try(Cursor c=db.rawQuery("SELECT hits_json,edits_json FROM archives WHERE id=?",archiveArgs)){
            if(!c.moveToFirst())throw new IllegalArgumentException("知识库不存在");
            JSONArray hits=new JSONArray(s(c,"hits_json")),edits=new JSONArray(s(c,"edits_json"));
            JSONArray remainingHits=new JSONArray(),remainingEdits=new JSONArray();
            for(int i=0;i<hits.length();i++){
                JSONObject item=hits.optJSONObject(i);
                if(item==null||item.optLong("document_id")!=docId||!title.equals(item.optString("chapter_title")))remainingHits.put(hits.get(i));
            }
            for(int i=0;i<edits.length();i++){
                JSONObject item=edits.optJSONObject(i);
                if(item==null||item.optLong("document_id")!=docId||!title.equals(item.optString("title")))remainingEdits.put(edits.get(i));
            }
            if(!deleted)remainingEdits.put(new JSONObject().put("document_id",docId).put("title",title)
                    .put("edited_at",System.currentTimeMillis()/1000).put("needs_resummary",1));
            android.content.ContentValues v=new android.content.ContentValues();v.put("hits_json",remainingHits.toString());v.put("edits_json",remainingEdits.toString());
            db.update("archives",v,"id=?",archiveArgs);
        }
    }

    synchronized JSONObject saveSourceSlice(long archiveId,long chapterId,int offset,String expected,String replacement) throws Exception {
        if(offset<0||expected==null||expected.isEmpty()||expected.codePointCount(0,expected.length())>12000)
            throw new IllegalArgumentException("请先加载要编辑的原文段落");
        if(replacement==null||replacement.trim().isEmpty()||replacement.codePointCount(0,replacement.length())>30000)
            throw new IllegalArgumentException("编辑后的原文段落须为 1–30000 字，不能留空");
        int originalChars=expected.codePointCount(0,expected.length());
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONObject chapter=chapter(chapterId);
            if(chapter.getLong("archive_id")!=archiveId)throw new IllegalArgumentException("章节不属于当前知识库");
            long total=chapter.getLong("text_chars");
            if(offset>=total||offset+originalChars>total)throw new IllegalArgumentException("原文段落位置已失效，请刷新章节");
            if(!expected.equals(textSlice(chapterId,offset,originalChars)))throw new IllegalArgumentException("原文已变化，请刷新后再编辑");
            if(expected.equals(replacement))return new JSONObject().put("changed",false).put("chapter_id",chapterId);
            db.execSQL("UPDATE chapters SET text=substr(text,1,?)||?||substr(text,?),summary='',tags='',error='',rewrite_status=CASE WHEN LENGTH(TRIM(rewrite))>0 THEN '原文已修改，改写待校对' ELSE '未改写' END WHERE id=? AND archive_id=?",
                    new Object[]{offset,replacement,offset+originalChars+1,chapterId,archiveId});
            String key="doc:"+chapter.getLong("doc_id")+":chapter:"+chapter.getString("title");
            db.delete("summaries","archive_id=? AND ((scope='chapter' AND source_key=?) OR (scope='chunk' AND source_key=?))",
                    new String[]{Long.toString(archiveId),key,"chunk:"+chapterId});
            updateSourceMetadata(db,archiveId,chapter.getLong("doc_id"),chapter.getString("title"),false);
            db.execSQL("UPDATE documents SET char_count=(SELECT COALESCE(SUM(LENGTH(text)),0) FROM chapters WHERE archive_id=? AND doc_id=?) WHERE archive_id=? AND source_id=?",
                    new Object[]{archiveId,chapter.getLong("doc_id"),archiveId,chapter.getLong("doc_id")});
            db.setTransactionSuccessful();return new JSONObject().put("changed",true).put("chapter_id",chapterId);
        }finally{db.endTransaction();}
    }

    synchronized void deleteChapter(long archiveId,long chapterId) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONObject chapter=chapter(chapterId);
            if(chapter.getLong("archive_id")!=archiveId)throw new IllegalArgumentException("章节不属于当前知识库");
            long docId=chapter.getLong("doc_id");String title=chapter.getString("title");
            db.delete("chapters","id=? AND archive_id=?",new String[]{Long.toString(chapterId),Long.toString(archiveId)});
            db.delete("summaries","archive_id=? AND ((scope='chapter' AND source_key=?) OR (scope='chunk' AND source_key=?))",
                    new String[]{Long.toString(archiveId),"doc:"+docId+":chapter:"+title,"chunk:"+chapterId});
            updateSourceMetadata(db,archiveId,docId,title,true);
            db.execSQL("UPDATE documents SET char_count=(SELECT COALESCE(SUM(LENGTH(text)),0) FROM chapters WHERE archive_id=? AND doc_id=?) WHERE archive_id=? AND source_id=?",
                    new Object[]{archiveId,docId,archiveId,docId});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    synchronized JSONArray chapters(long archiveId, int offset, int limit, String keyword) throws Exception {
        JSONArray out=new JSONArray();
        String sql="SELECT id,doc_id,ord,title,LENGTH(text) chars,summary,tags,error,CASE WHEN LENGTH(TRIM(rewrite))>0 THEN 1 ELSE 0 END rewritten,rewrite_status FROM chapters WHERE archive_id=?";
        List<String> args=new ArrayList<>(); args.add(Long.toString(archiveId));
        if(keyword!=null&&!keyword.trim().isEmpty()) { sql+=" AND (title LIKE ? OR summary LIKE ? OR tags LIKE ? OR text LIKE ?)"; for(int i=0;i<4;i++) args.add("%"+keyword.trim()+"%"); }
        sql+=" ORDER BY doc_id,ord LIMIT ? OFFSET ?"; args.add(Integer.toString(limit));args.add(Integer.toString(offset));
        try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))) {
            while(c.moveToNext()) out.put(new JSONObject().put("id",n(c,"id")).put("doc_id",n(c,"doc_id")).put("ord",n(c,"ord"))
                    .put("title",s(c,"title")).put("chars",n(c,"chars")).put("summary",s(c,"summary")).put("tags",s(c,"tags"))
                    .put("error",s(c,"error")).put("rewritten",n(c,"rewritten")==1).put("rewrite_status",s(c,"rewrite_status")));
        }
        return out;
    }

    synchronized int chapterCount(long archiveId) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM chapters WHERE archive_id=?",new String[]{Long.toString(archiveId)})){
            c.moveToFirst();return c.getInt(0);
        }
    }

    synchronized JSONObject chapter(long chapterId) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,archive_id,doc_id,ord,title,summary,tags,error,chapter_prompt,extra_instruction,rewrite_status,LENGTH(text) text_chars,LENGTH(rewrite) rewrite_chars,LENGTH(rewrite_partial) partial_chars FROM chapters WHERE id=?",new String[]{Long.toString(chapterId)})) {
            if(!c.moveToFirst()) throw new IllegalArgumentException("章节不存在");
            long chars=n(c,"text_chars"),rewriteChars=n(c,"rewrite_chars");
            return new JSONObject().put("id",chapterId).put("archive_id",n(c,"archive_id")).put("doc_id",n(c,"doc_id"))
                    .put("ord",n(c,"ord")).put("title",s(c,"title")).put("text",textSlice(chapterId,0,12000))
                    .put("text_chars",chars).put("text_truncated",chars>12000)
                    .put("summary",s(c,"summary")).put("tags",s(c,"tags")).put("error",s(c,"error"))
                    .put("rewrite",rewriteChars>120000?rewriteSlice(chapterId,0,12000):readColumn(chapterId,"rewrite",rewriteChars))
                    .put("rewrite_truncated",rewriteChars>120000)
                    .put("partial_chars",n(c,"partial_chars"))
                    .put("chapter_prompt",s(c,"chapter_prompt")).put("extra_instruction",s(c,"extra_instruction"))
                    .put("rewrite_status",s(c,"rewrite_status"));
        }
    }

    private String columnSlice(long chapterId,String column,long offset,int limit) {
        if(offset<0)offset=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT substr("+column+",?,?) part FROM chapters WHERE id=?",
                new String[]{Long.toString(offset+1),Integer.toString(Math.min(32768,limit)),Long.toString(chapterId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("章节不存在");
            return s(c,"part");
        }
    }

    private String textSlice(long chapterId,long offset,int limit){return columnSlice(chapterId,"text",offset,limit);}
    private String rewriteSlice(long chapterId,long offset,int limit){return columnSlice(chapterId,"rewrite",offset,limit);}

    synchronized JSONObject readerRewriteSlice(long chapterId,int offset,int limit) throws Exception {
        long total;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT LENGTH(rewrite) FROM chapters WHERE id=?",new String[]{Long.toString(chapterId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("章节不存在");total=c.getLong(0);
        }
        int start=(int)Math.max(0,Math.min(total,offset));int page=Math.max(1000,Math.min(12000,limit));
        return new JSONObject().put("chapter_id",chapterId).put("offset",start).put("total",total).put("text",rewriteSlice(chapterId,start,page));
    }

    private String readColumn(long chapterId,String column,long length) {
        StringBuilder out=new StringBuilder((int)Math.min(length,120000));
        for(long offset=0;offset<length;offset+=32768)out.append(columnSlice(chapterId,column,offset,32768));
        return out.toString();
    }

    synchronized JSONObject sourceSlice(long chapterId,int offset,int limit) throws Exception {
        long total;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT LENGTH(text) total FROM chapters WHERE id=?",new String[]{Long.toString(chapterId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("章节不存在");total=n(c,"total");
        }
        int start=(int)Math.max(0,Math.min(total,offset));int page=Math.max(1000,Math.min(12000,limit));
        return new JSONObject().put("chapter_id",chapterId).put("offset",start).put("total",total)
                .put("text",textSlice(chapterId,start,page));
    }

    synchronized String summarySample(long chapterId) {
        return summarySample(chapterId,12000);
    }

    synchronized String summarySample(long chapterId,int budget) {
        budget=Math.max(2000,Math.min(12000,budget));
        long length;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT LENGTH(text) total FROM chapters WHERE id=?",new String[]{Long.toString(chapterId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("章节不存在");length=n(c,"total");
        }
        if(length<=budget)return readColumn(chapterId,"text",length);
        long middle=length/2;
        int middleChars=budget/5,edgeChars=(budget-middleChars)/2;
        return textSlice(chapterId,0,edgeChars)+"\n[中间部分已抽样；原文总长 "+length+" 字]\n"
                +textSlice(chapterId,Math.max(0,middle-middleChars/2),middleChars)
                +"\n[后半部分]\n"+textSlice(chapterId,length-edgeChars,edgeChars);
    }

    synchronized String rewriteSource(long chapterId) {
        long length;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT LENGTH(text) total FROM chapters WHERE id=?",new String[]{Long.toString(chapterId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("章节不存在");length=n(c,"total");
        }
        if(length>120000)throw new IllegalStateException("本章原文超过 12 万字，请先在知识库中正确切分章节，再进行整章改写");
        return readColumn(chapterId,"text",length);
    }

    synchronized void setSummary(long chapterId, String summary, String error) throws Exception {
        JSONObject ch=chapter(chapterId);
        SQLiteDatabase db=getWritableDatabase();
        android.content.ContentValues v=new android.content.ContentValues();v.put("summary",summary);v.put("error",error);db.update("chapters",v,"id=?",new String[]{Long.toString(chapterId)});
        if(!summary.isEmpty()) {
            String key="doc:"+ch.getLong("doc_id")+":chapter:"+ch.getString("title");
            upsertSummary(db,ch.getLong("archive_id"),"chapter",key,ch.getString("title"),summary);
            upsertSummary(db,ch.getLong("archive_id"),"chunk","chunk:"+chapterId,ch.getString("title"),summary);
        }
    }

    synchronized void setError(long chapterId,String error){
        android.content.ContentValues v=new android.content.ContentValues();v.put("error",error);
        getWritableDatabase().update("chapters",v,"id=?",new String[]{Long.toString(chapterId)});
    }

    synchronized void setRewrite(long id,String content,String chapterPrompt,String extra) {
        android.content.ContentValues v=new android.content.ContentValues();v.put("rewrite",content);v.put("rewrite_partial","");v.put("chapter_prompt",chapterPrompt);v.put("extra_instruction",extra);v.put("rewrite_status",content.trim().isEmpty()?"未改写":"已改写");v.put("error","");
        getWritableDatabase().update("chapters",v,"id=?",new String[]{Long.toString(id)});
    }

    synchronized void setRewritePartial(long id,String content){android.content.ContentValues v=new android.content.ContentValues();v.put("rewrite_partial",content);getWritableDatabase().update("chapters",v,"id=?",new String[]{Long.toString(id)});}
    synchronized String rewritePartial(long id){long length;try(Cursor c=getReadableDatabase().rawQuery("SELECT LENGTH(rewrite_partial) FROM chapters WHERE id=?",new String[]{Long.toString(id)})){if(!c.moveToFirst())throw new IllegalArgumentException("章节不存在");length=c.getLong(0);}return readColumn(id,"rewrite_partial",length);}

    synchronized void setOverview(long id,String overview) {
        android.content.ContentValues v=new android.content.ContentValues();v.put("overview",overview);
        getWritableDatabase().update("archives",v,"id=?",new String[]{Long.toString(id)});
        upsertSummary(getWritableDatabase(),id,"archive","main","全书总览",overview);
    }

    synchronized void rebuildOverview(long archiveId) {
        try {String existing=archive(archiveId).optString("overview","");
            if(!existing.isEmpty()&&!existing.startsWith("按章节记忆生成的全书索引："))return;
        } catch(Exception ex) {throw new IllegalStateException(ex);}
        StringBuilder overview=new StringBuilder("按章节记忆生成的全书索引：\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT title,summary FROM chapters WHERE archive_id=? AND LENGTH(TRIM(summary))>0 ORDER BY doc_id,ord",new String[]{Long.toString(archiveId)})) {
            while(c.moveToNext()) {
                if(overview.length()>12000)break;
                String summary=s(c,"summary").replaceAll("\\s+"," ");
                overview.append(s(c,"title")).append("：").append(summary,0,Math.min(120,summary.length())).append('\n');
            }
        }
        setOverview(archiveId,overview.toString());
    }

    synchronized String specialTagSample(long archiveId) {
        StringBuilder out=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT title,summary FROM chapters WHERE archive_id=? AND LENGTH(TRIM(summary))>0 ORDER BY doc_id,ord",new String[]{Long.toString(archiveId)})) {
            int total=c.getCount(),step=Math.max(1,total/16),index=0,count=0;
            while(c.moveToNext()&&count<16){if(index++%step!=0)continue;
                String summary=s(c,"summary").replaceAll("\\s+"," ");
                out.append(s(c,"title")).append("：").append(summary,0,Math.min(260,summary.length())).append('\n');count++;
            }
        }
        return out.toString();
    }

    private static void upsertSummary(SQLiteDatabase db,long archiveId,String scope,String key,String title,String content) {
        android.content.ContentValues v=new android.content.ContentValues();v.put("archive_id",archiveId);v.put("scope",scope);v.put("source_key",key);v.put("title",title);v.put("content",content);
        db.insertWithOnConflict("summaries",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    static boolean heading(String line) {
        if(line.trim().length()>120)return false;
        return HEADING.matcher(headingTitle(line)).matches();
    }

    private static String headingTitle(String line) {
        return line.trim().replaceAll("^[#*_=~\\-\\s　]+|[#*_=~\\-\\s　]+$","")
                .replaceAll("^[【\\[（(《<「『]+|[】\\]）)》>」』]+$","")
                .replaceFirst("^\\s*正文\\s+","").trim().replaceAll("\\s+"," ");
    }

    private static Charset detectCharset(BufferedInputStream input) throws IOException {
        input.mark(65536);byte[] sample=new byte[32768];int count=input.read(sample);input.reset();
        if(count>=3 && (sample[0]&255)==239 && (sample[1]&255)==187 && (sample[2]&255)==191) return StandardCharsets.UTF_8;
        java.nio.charset.CharsetDecoder utf8=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        java.nio.charset.CoderResult result=utf8.decode(java.nio.ByteBuffer.wrap(sample,0,Math.max(count,0)),CharBuffer.allocate(Math.max(count,1)),false);
        return result.isError()?Charset.forName("GB18030"):StandardCharsets.UTF_8;
    }

    synchronized long importText(String filename,InputStream stream) throws Exception {
        long archiveId=createArchive(filename.replaceFirst("(?i)\\.(txt|md|epub)$",""));
        SQLiteDatabase db=getWritableDatabase();
        boolean success=false;
        try {
            db.beginTransaction();
            android.content.ContentValues doc=new android.content.ContentValues();doc.put("archive_id",archiveId);doc.put("source_id",1);doc.put("filename",filename);doc.put("title",filename);doc.put("char_count",0);
            db.insertOrThrow("documents",null,doc);
            BufferedInputStream buffered=new BufferedInputStream(stream);
            java.io.BufferedReader reader=new java.io.BufferedReader(new InputStreamReader(buffered,detectCharset(buffered)),65536);
            StringBuilder body=new StringBuilder();String title="正文开头";String line;int ord=0;long total=0;
            while((line=reader.readLine())!=null) {
                if(heading(line) && body.length()>0) { insertChapter(db,archiveId,1,ord++,title,body.toString(),0,0,0);body.setLength(0); }
                if(heading(line)) title=headingTitle(line);
                body.append(line).append('\n');total+=line.length()+1;
            }
            if(body.length()>0) insertChapter(db,archiveId,1,ord,"正文开头".equals(title)?"正文":title,body.toString(),0,0,0);
            android.content.ContentValues update=new android.content.ContentValues();update.put("char_count",total);
            db.update("documents",update,"archive_id=? AND source_id=1",new String[]{Long.toString(archiveId)});
            db.setTransactionSuccessful();success=true;return archiveId;
        } finally { db.endTransaction();if(!success)db.delete("archives","id=?",new String[]{Long.toString(archiveId)}); }
    }

    synchronized long importEpub(String filename,InputStream stream) throws Exception {
        String text=EpubImport.extract(stream);
        return importText(filename,new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Pattern splitPattern(String expression) {
        if(expression==null||expression.trim().isEmpty())return null;
        if(expression.length()>300)throw new IllegalArgumentException("自定义章节表达式过长");
        try{return Pattern.compile(expression,Pattern.CASE_INSENSITIVE);}catch(PatternSyntaxException ex){throw new IllegalArgumentException("章节表达式有误："+ex.getDescription());}
    }

    private static boolean isHeading(String line,Pattern custom) {
        return custom==null?heading(line):line.trim().length()<=200&&custom.matcher(line.trim()).matches();
    }

    private void ensureCanSplit(long archiveId) throws Exception {
        archive(archiveId);
        if(diagnostics(archiveId).getBoolean("possible_mojibake"))
            throw new IllegalStateException("此存档的原文已被旧版错误编码破坏，重新切分无法还原。请点击“重新导入原 TXT”，建立新知识库；旧存档会保留。");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT (SELECT COUNT(*) FROM chapters WHERE archive_id=? AND (LENGTH(TRIM(summary))>0 OR LENGTH(TRIM(rewrite))>0))+(SELECT COUNT(*) FROM summaries WHERE archive_id=?)+(SELECT COUNT(*) FROM messages WHERE archive_id=?)",new String[]{Long.toString(archiveId),Long.toString(archiveId),Long.toString(archiveId)})){
            c.moveToFirst();if(c.getLong(0)>0)throw new IllegalStateException("知识库已有总结、改写稿或对话。为避免覆盖，请重新导入原小说到一个新知识库后再切分。");
        }
        if(new JSONArray(archive(archiveId).getString("hits_json")).length()>0)
            throw new IllegalStateException("知识库已有特殊标签标注，请重新导入原小说后再切分，避免标签错位。");
    }

    synchronized JSONObject diagnostics(long archiveId) throws Exception {
        archive(archiveId);
        long chapters=0,maxChars=0,firstId=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) chapters,COALESCE(MAX(LENGTH(text)),0) max_chars,COALESCE(MIN(id),0) first_id FROM chapters WHERE archive_id=?",new String[]{Long.toString(archiveId)})){
            c.moveToFirst();chapters=n(c,"chapters");maxChars=n(c,"max_chars");firstId=n(c,"first_id");
        }
        int replacements=0;
        if(chapters==1&&maxChars>500000){
            String sample=textSlice(firstId,0,12000);
            for(int i=0;i<sample.length();i++)if(sample.charAt(i)=='\uFFFD')replacements++;
        }
        return new JSONObject().put("archive_id",archiveId).put("chapter_count",chapters)
                .put("largest_chapter_chars",maxChars).put("replacement_chars",replacements)
                .put("possible_mojibake",chapters==1&&maxChars>500000&&replacements>=5);
    }

    synchronized JSONObject previewSplit(long archiveId,String expression) throws Exception {
        Pattern custom=splitPattern(expression);ensureCanSplit(archiveId);
        int current=countChapters(archiveId,"","summary",false);final int[] proposed={0};
        JSONArray sample=new JSONArray();
        try(Cursor docs=getReadableDatabase().rawQuery("SELECT source_id FROM documents WHERE archive_id=? ORDER BY source_id",new String[]{Long.toString(archiveId)})){
            while(docs.moveToNext()){
                long docId=docs.getLong(0);final boolean[] hasBody={false};final String[] title={"正文开头"};
                forEachSourceLine(archiveId,docId,line->{
                    if(isHeading(line,custom)){
                        if(hasBody[0]){proposed[0]++;if(sample.length()<30)sample.put(title[0]);}
                        title[0]=headingTitle(line);hasBody[0]=false;
                    }
                    hasBody[0]=true;
                });
                if(hasBody[0]){proposed[0]++;if(sample.length()<30)sample.put(title[0]);}
            }
        }
        return new JSONObject().put("archive_id",archiveId).put("expression",expression==null?"":expression.trim())
                .put("current",current).put("proposed",proposed[0]).put("sample",sample);
    }

    private void forEachSourceLine(long archiveId,long docId,java.util.function.Consumer<String> consumer) {
        StringBuilder pending=new StringBuilder();
        try(Cursor parts=getReadableDatabase().rawQuery("SELECT id,LENGTH(text) chars FROM chapters WHERE archive_id=? AND doc_id=? ORDER BY ord,id",new String[]{Long.toString(archiveId),Long.toString(docId)})){
            while(parts.moveToNext()){
                long id=n(parts,"id"),length=n(parts,"chars");
                for(long offset=0;offset<length;offset+=32768){
                    String piece=textSlice(id,offset,32768);int start=0,end;
                    while((end=piece.indexOf('\n',start))>=0){
                        pending.append(piece,start,end);
                        int size=pending.length();if(size>0&&pending.charAt(size-1)=='\r')pending.setLength(size-1);
                        consumer.accept(pending.toString());pending.setLength(0);start=end+1;
                    }
                    pending.append(piece,start,piece.length());
                }
            }
        }
        if(pending.length()>0)consumer.accept(pending.toString());
    }

    synchronized int applySplit(long archiveId,String expression) throws Exception {
        Pattern custom=splitPattern(expression);ensureCanSplit(archiveId);
        SQLiteDatabase db=getWritableDatabase();int count=0;
        db.beginTransaction();
        try{
            try(Cursor docs=db.rawQuery("SELECT source_id FROM documents WHERE archive_id=? ORDER BY source_id",new String[]{Long.toString(archiveId)})){
                while(docs.moveToNext()){
                    long docId=docs.getLong(0);StringBuilder source=new StringBuilder();
                    try(Cursor parts=db.rawQuery("SELECT id,LENGTH(text) chars FROM chapters WHERE archive_id=? AND doc_id=? ORDER BY ord,id",new String[]{Long.toString(archiveId),Long.toString(docId)})){
                        while(parts.moveToNext())source.append(readColumn(n(parts,"id"),"text",n(parts,"chars")));
                    }
                    db.delete("chapters","archive_id=? AND doc_id=?",new String[]{Long.toString(archiveId),Long.toString(docId)});
                    java.io.BufferedReader lines=new java.io.BufferedReader(new java.io.StringReader(source.toString()));
                    StringBuilder body=new StringBuilder();String title="正文开头",line;int ord=0;
                    while((line=lines.readLine())!=null){
                        if(isHeading(line,custom)&&body.length()>0){insertChapter(db,archiveId,docId,ord++,title,body.toString(),0,0,0);body.setLength(0);count++;}
                        if(isHeading(line,custom))title=headingTitle(line);
                        body.append(line).append('\n');
                    }
                    if(body.length()>0){insertChapter(db,archiveId,docId,ord,"正文开头".equals(title)?"正文":title,body.toString(),0,0,0);count++;}
                    android.content.ContentValues values=new android.content.ContentValues();values.put("char_count",source.length());
                    db.update("documents",values,"archive_id=? AND source_id=?",new String[]{Long.toString(archiveId),Long.toString(docId)});
                }
            }
            db.setTransactionSuccessful();return count;
        }finally{db.endTransaction();}
    }

    private static long insertChapter(SQLiteDatabase db,long archiveId,long docId,int ord,String title,String text,int start,int end,int tokens) {
        android.content.ContentValues v=new android.content.ContentValues();v.put("archive_id",archiveId);v.put("doc_id",docId);v.put("ord",ord);v.put("title",title);v.put("text",text);v.put("char_start",start);v.put("char_end",end);v.put("token_count",tokens);
        return db.insertOrThrow("chapters",null,v);
    }

    private static Object readValue(JsonReader reader) throws Exception {
        switch(reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object=new JSONObject();reader.beginObject();while(reader.hasNext())object.put(reader.nextName(),readValue(reader));reader.endObject();return object;
            case BEGIN_ARRAY:
                JSONArray array=new JSONArray();reader.beginArray();while(reader.hasNext())array.put(readValue(reader));reader.endArray();return array;
            case STRING:return reader.nextString();
            case NUMBER:return reader.nextString();
            case BOOLEAN:return reader.nextBoolean();
            case NULL:reader.nextNull();return JSONObject.NULL;
            default:throw new IOException("Unsupported JSON token");
        }
    }

    synchronized long importKnowledgeBase(InputStream stream) throws Exception {
        long archiveId=0;SQLiteDatabase db=getWritableDatabase();
        String format="";int version=0;JSONArray pendingSummaries=new JSONArray();
        java.util.HashMap<String,Long> chapterIds=new java.util.HashMap<>();
        try(JsonReader reader=new JsonReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            db.beginTransaction();reader.beginObject();
            while(reader.hasNext()) {
                String key=reader.nextName();
                if("format".equals(key)){format=reader.nextString();continue;}
                if("version".equals(key)){version=reader.nextInt();continue;}
                if("archive".equals(key)){
                    JSONObject meta=(JSONObject)readValue(reader);archiveId=createArchive(meta.optString("name","导入知识库"));
                    android.content.ContentValues v=new android.content.ContentValues();v.put("description",meta.optString("description",""));db.update("archives",v,"id=?",new String[]{Long.toString(archiveId)});continue;
                }
                if("documents".equals(key)){
                    reader.beginArray();while(reader.hasNext()){
                        JSONObject d=(JSONObject)readValue(reader);android.content.ContentValues v=new android.content.ContentValues();
                        v.put("archive_id",archiveId);v.put("source_id",d.optLong("id",1));v.put("filename",d.optString("filename","imported.txt"));v.put("title",d.optString("title","正文"));v.put("char_count",d.optLong("char_count",0));db.insertOrThrow("documents",null,v);
                    }reader.endArray();continue;
                }
                if("chunks".equals(key)){
                    reader.beginArray();while(reader.hasNext()){
                        JSONObject c=(JSONObject)readValue(reader);long docId=c.optLong("document_id",1);
                        String title=c.optString("title","正文"),groupKey=docId+"\u0000"+title;
                        Long existing=chapterIds.get(groupKey);
                        if(existing==null){
                            long id=insertChapter(db,archiveId,docId,c.optInt("ord",0),title,c.optString("text",""),c.optInt("char_start",0),c.optInt("char_end",0),c.optInt("token_count",0));
                            chapterIds.put(groupKey,id);
                        }else{
                            db.execSQL("UPDATE chapters SET text=text||'\n\n'||?,char_end=?,token_count=token_count+? WHERE id=?",
                                    new Object[]{c.optString("text",""),c.optInt("char_end",0),c.optInt("token_count",0),existing});
                        }
                    }reader.endArray();continue;
                }
                if("summaries".equals(key)){
                    reader.beginArray();while(reader.hasNext())pendingSummaries.put(readValue(reader));reader.endArray();continue;
                }
                if("special_tags".equals(key)||"special_tag_hits".equals(key)||"chapter_source_edits".equals(key)){
                    JSONArray value=(JSONArray)readValue(reader);android.content.ContentValues v=new android.content.ContentValues();v.put("special_tags".equals(key)?"tags_json":"special_tag_hits".equals(key)?"hits_json":"edits_json",value.toString());db.update("archives",v,"id=?",new String[]{Long.toString(archiveId)});continue;
                }
                reader.skipValue();
            }
            reader.endObject();
            if(!"novel-memory-archive".equals(format)||version<2||version>4||archiveId==0)throw new IOException("仅支持桌面端 novel-memory-archive v2–v4 文件");
            for(int i=0;i<pendingSummaries.length();i++){
                JSONObject sum=pendingSummaries.getJSONObject(i);String scope=sum.optString("scope","");String sourceKey=sum.optString("source_key","");String content=sum.optString("content","");
                upsertSummary(db,archiveId,scope,sourceKey,sum.optString("title",""),content);
                if("archive".equals(scope)&&"main".equals(sourceKey)){android.content.ContentValues v=new android.content.ContentValues();v.put("overview",content);db.update("archives",v,"id=?",new String[]{Long.toString(archiveId)});}
                if("chapter".equals(scope)&&sourceKey.startsWith("doc:")&&sourceKey.contains(":chapter:")){
                    int cut=sourceKey.indexOf(":chapter:");String doc=sourceKey.substring(4,cut);String title=sourceKey.substring(cut+9);
                    android.content.ContentValues v=new android.content.ContentValues();v.put("summary",content);
                    db.update("chapters",v,"archive_id=? AND doc_id=? AND title=?",new String[]{Long.toString(archiveId),doc,title});
                }
            }
            JSONObject meta=archive(archiveId);JSONArray tags=new JSONArray(meta.getString("tags_json")),hits=new JSONArray(meta.getString("hits_json"));
            java.util.HashMap<Long,String> tagNames=new java.util.HashMap<>();
            for(int i=0;i<tags.length();i++){JSONObject tag=tags.getJSONObject(i);tagNames.put(tag.optLong("id"),tag.optString("name",""));}
            for(int i=0;i<hits.length();i++){
                JSONObject hit=hits.getJSONObject(i);String name=tagNames.get(hit.optLong("tag_id"));if(name==null||name.isEmpty())continue;
                String doc=Long.toString(hit.optLong("document_id")),title=hit.optString("chapter_title","");
                db.execSQL("UPDATE chapters SET tags=tags||? WHERE archive_id=? AND doc_id=? AND title=?",new Object[]{"\u001f"+name+"\u001f",archiveId,doc,title});
            }
            db.setTransactionSuccessful();return archiveId;
        } finally { db.endTransaction(); }
    }

    synchronized void exportKnowledgeBase(long archiveId,OutputStream stream) throws Exception {
        JSONObject archive=archive(archiveId);SQLiteDatabase db=getReadableDatabase();
        try(JsonWriter w=new JsonWriter(new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8),65536))){
            w.setIndent("  ");w.beginObject();w.name("format").value("novel-memory-archive");w.name("version").value(4);w.name("exported_at").value(System.currentTimeMillis()/1000);
            w.name("archive").beginObject().name("name").value(archive.getString("name")).name("description").value(archive.getString("description")).endObject();
            w.name("documents").beginArray();
            try(Cursor c=db.rawQuery("SELECT source_id,filename,title,char_count FROM documents WHERE archive_id=? ORDER BY source_id",new String[]{Long.toString(archiveId)})){
                while(c.moveToNext())w.beginObject().name("id").value(n(c,"source_id")).name("filename").value(s(c,"filename")).name("title").value(s(c,"title")).name("char_count").value(n(c,"char_count")).endObject();
            }w.endArray();
            w.name("chunks").beginArray();
            try(Cursor c=db.rawQuery("SELECT id,doc_id,ord,title,LENGTH(text) chars,char_start,char_end,token_count FROM chapters WHERE archive_id=? ORDER BY doc_id,ord",new String[]{Long.toString(archiveId)})){
                while(c.moveToNext())w.beginObject().name("document_id").value(n(c,"doc_id")).name("ord").value(n(c,"ord")).name("title").value(s(c,"title")).name("text").value(readColumn(n(c,"id"),"text",n(c,"chars"))).name("char_start").value(n(c,"char_start")).name("char_end").value(n(c,"char_end")).name("token_count").value(n(c,"token_count")).endObject();
            }w.endArray();
            w.name("summaries").beginArray();
            try(Cursor c=db.rawQuery("SELECT scope,source_key,title,content FROM summaries WHERE archive_id=? ORDER BY id",new String[]{Long.toString(archiveId)})){
                while(c.moveToNext())w.beginObject().name("scope").value(s(c,"scope")).name("source_key").value(s(c,"source_key")).name("title").value(s(c,"title")).name("content").value(s(c,"content")).endObject();
            }w.endArray();
            w.name("chapter_prompts").beginArray().endArray();w.name("rewrites").beginArray().endArray();
            w.name("special_tags");writeJsonArray(w,new JSONArray(archive.getString("tags_json")));
            w.name("special_tag_hits");writeJsonArray(w,new JSONArray(archive.getString("hits_json")));
            w.name("chapter_source_edits");writeJsonArray(w,new JSONArray(archive.getString("edits_json")));
            w.endObject();
        }
    }

    private static void writeJsonArray(JsonWriter w,JSONArray array) throws Exception {
        w.beginArray();for(int i=0;i<array.length();i++)writeJsonValue(w,array.get(i));w.endArray();
    }
    private static void writeJsonValue(JsonWriter w,Object value) throws Exception {
        if(value==null||value==JSONObject.NULL)w.nullValue();
        else if(value instanceof JSONObject){w.beginObject();JSONObject o=(JSONObject)value;java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){String key=keys.next();w.name(key);writeJsonValue(w,o.get(key));}w.endObject();}
        else if(value instanceof JSONArray)writeJsonArray(w,(JSONArray)value);
        else if(value instanceof Boolean)w.value((Boolean)value);
        else if(value instanceof Number)w.value((Number)value);
        else w.value(value.toString());
    }

    private static String targetWhere(long archiveId,String keyword,String mode,boolean missingOnly,JSONObject filter,List<String> args){
        String sql=" FROM chapters WHERE archive_id=?";args.add(Long.toString(archiveId));
        if(keyword!=null&&!keyword.isEmpty()){sql+=" AND (title LIKE ? OR summary LIKE ? OR tags LIKE ? OR text LIKE ?)";for(int i=0;i<4;i++)args.add("%"+keyword+"%");}
        if(filter!=null){JSONArray tags=filter.optJSONArray("tags");if(tags!=null&&tags.length()>0){
            boolean all="all".equals(filter.optString("mode","all"));sql+=" AND (";
            for(int i=0;i<tags.length();i++){if(i>0)sql+=all?" AND ":" OR ";sql+="(INSTR(tags,?)>0 OR (INSTR(tags,char(31))=0 AND tags LIKE ?))";args.add("\u001f"+tags.optString(i)+"\u001f");args.add("%"+tags.optString(i)+"%");}
            sql+=")";
        }}
        if(missingOnly)sql+=" AND "+("rewrite".equals(mode)?"LENGTH(TRIM(rewrite))=0":"(LENGTH(TRIM(summary))=0 OR summary LIKE '%本地零 Token 抽取兜底%' OR summary LIKE '%本地兜底摘要未可靠提取%')");
        return sql;
    }
    synchronized int countChapters(long archiveId,String keyword,String mode,boolean missingOnly){return countChapters(archiveId,keyword,mode,missingOnly,null);}
    synchronized int countChapters(long archiveId,String keyword,String mode,boolean missingOnly,JSONObject filter) {
        List<String> args=new ArrayList<>();String sql="SELECT COUNT(*)"+targetWhere(archiveId,keyword,mode,missingOnly,filter,args);
        try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){c.moveToFirst();return c.getInt(0);}
    }

    synchronized long countTargetChars(long archiveId,String keyword,String mode,boolean missingOnly){return countTargetChars(archiveId,keyword,mode,missingOnly,null);}
    synchronized long countTargetChars(long archiveId,String keyword,String mode,boolean missingOnly,JSONObject filter){
        List<String> args=new ArrayList<>();String sql="SELECT COALESCE(SUM(LENGTH(text)),0)"+targetWhere(archiveId,keyword,mode,missingOnly,filter,args);
        try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){c.moveToFirst();return c.getLong(0);}
    }

    synchronized List<Long> targetIds(long archiveId,String keyword,String mode,boolean missingOnly){return targetIds(archiveId,keyword,mode,missingOnly,null);}
    synchronized List<Long> targetIds(long archiveId,String keyword,String mode,boolean missingOnly,JSONObject filter) {
        List<String> args=new ArrayList<>();String sql="SELECT id"+targetWhere(archiveId,keyword,mode,missingOnly,filter,args)+" ORDER BY doc_id,ord";
        List<Long> ids=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){while(c.moveToNext())ids.add(c.getLong(0));}return ids;
    }

    synchronized JSONObject rewriteBrowse(long archiveId,String keyword,int offset,int limit) throws Exception {
        archive(archiveId);
        String search="%"+(keyword==null?"":keyword.trim())+"%";
        boolean filtered=keyword!=null&&!keyword.trim().isEmpty();
        String where="archive_id=?"+(filtered?" AND (title LIKE ? OR summary LIKE ? OR tags LIKE ? OR text LIKE ?)":"");
        String[] args=filtered?new String[]{Long.toString(archiveId),search,search,search,search}:new String[]{Long.toString(archiveId)};
        int total;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM chapters WHERE "+where,args)){c.moveToFirst();total=c.getInt(0);}
        int pageSize=Math.max(1,Math.min(limit,100));
        int safeOffset=Math.max(0,Math.min(offset,total==0?0:((total-1)/pageSize)*pageSize));
        JSONArray rows=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,LENGTH(text) chars,rewrite_status,CASE WHEN LENGTH(TRIM(rewrite))>0 THEN 1 ELSE 0 END rewritten,substr(summary,1,100) excerpt,tags FROM chapters WHERE "+where+" ORDER BY doc_id,ord LIMIT ? OFFSET ?",appendArgs(args,Integer.toString(pageSize),Integer.toString(safeOffset)))){
            while(c.moveToNext())rows.put(new JSONObject().put("id",n(c,"id")).put("title",s(c,"title")).put("chars",n(c,"chars")).put("rewrite_status",s(c,"rewrite_status")).put("rewritten",n(c,"rewritten")==1).put("excerpt",s(c,"excerpt")).put("tags",s(c,"tags")));
        }
        return new JSONObject().put("archive_id",archiveId).put("keyword",keyword==null?"":keyword).put("offset",safeOffset).put("total",total).put("rows",rows);
    }

    synchronized JSONObject readerCatalog(long archiveId) throws Exception {
        archive(archiveId);
        JSONArray chapters=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,LENGTH(text) source_chars,LENGTH(rewrite) rewrite_chars,CASE WHEN LENGTH(TRIM(rewrite))>0 THEN 1 ELSE 0 END rewritten FROM chapters WHERE archive_id=? ORDER BY doc_id,ord",new String[]{Long.toString(archiveId)})){
            while(c.moveToNext())chapters.put(new JSONObject().put("id",n(c,"id")).put("title",s(c,"title")).put("source_chars",n(c,"source_chars")).put("rewrite_chars",n(c,"rewrite_chars")).put("rewritten",n(c,"rewritten")==1));
        }
        return new JSONObject().put("archive_id",archiveId).put("rows",chapters);
    }

    private static String[] appendArgs(String[] source,String first,String second){String[] result=java.util.Arrays.copyOf(source,source.length+2);result[source.length]=first;result[source.length+1]=second;return result;}

    synchronized JSONArray messages(long archiveId) throws Exception {
        JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT role,content FROM messages WHERE archive_id=? ORDER BY id",new String[]{Long.toString(archiveId)})){
            while(c.moveToNext())out.put(new JSONObject().put("role",s(c,"role")).put("content",s(c,"content")));
        }return out;
    }
    synchronized void addMessage(long archiveId,String role,String content){android.content.ContentValues v=new android.content.ContentValues();v.put("archive_id",archiveId);v.put("role",role);v.put("content",content);getWritableDatabase().insertOrThrow("messages",null,v);}
    synchronized void clearMessages(long archiveId){getWritableDatabase().delete("messages","archive_id=?",new String[]{Long.toString(archiveId)});}

    private static String sha256(String value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }

    private String sha256Column(long chapterId,String column,long length) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(long offset=0;offset<length;offset+=32768)digest.update(columnSlice(chapterId,column,offset,32768).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }

    synchronized void exportRewrites(long archiveId,OutputStream stream) throws Exception {
        JSONObject meta=archive(archiveId);SQLiteDatabase db=getReadableDatabase();
        try(JsonWriter w=new JsonWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8))){
            w.beginObject().name("format").value("novel-memory-rewrites").name("version").value(1)
                    .name("archive_name").value(meta.getString("name")).name("chapters").beginArray();
            try(Cursor c=db.rawQuery("SELECT c.id,c.title,LENGTH(c.text) text_chars,LENGTH(c.rewrite) rewrite_chars,c.chapter_prompt,c.extra_instruction,d.filename FROM chapters c JOIN documents d ON d.archive_id=c.archive_id AND d.source_id=c.doc_id WHERE c.archive_id=? AND LENGTH(TRIM(c.rewrite))>0 ORDER BY c.doc_id,c.ord",new String[]{Long.toString(archiveId)})){
                while(c.moveToNext())w.beginObject().name("document_filename").value(s(c,"filename"))
                        .name("title").value(s(c,"title")).name("source_sha256").value(sha256Column(n(c,"id"),"text",n(c,"text_chars")))
                        .name("content").value(readColumn(n(c,"id"),"rewrite",n(c,"rewrite_chars"))).name("chapter_prompt").value(s(c,"chapter_prompt"))
                        .name("extra_instruction").value(s(c,"extra_instruction")).endObject();
            }w.endArray().endObject();
        }
    }

    synchronized int importRewrites(long archiveId,InputStream stream) throws Exception {
        archive(archiveId);SQLiteDatabase db=getWritableDatabase();String format="";int version=0,count=0;
        db.beginTransaction();
        try(JsonReader reader=new JsonReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
            reader.beginObject();while(reader.hasNext()){
                String key=reader.nextName();
                if("format".equals(key)){format=reader.nextString();continue;}
                if("version".equals(key)){version=reader.nextInt();continue;}
                if("chapters".equals(key)){
                    reader.beginArray();while(reader.hasNext()){
                        JSONObject item=(JSONObject)readValue(reader);String filename=item.getString("document_filename"),title=item.getString("title");
                        if(item.optString("content","").trim().isEmpty())throw new IOException("改写稿包含空白章节："+title);
                        long chapterId=0;int matches=0;
                        try(Cursor c=db.rawQuery("SELECT c.id,LENGTH(c.text) text_chars FROM chapters c JOIN documents d ON d.archive_id=c.archive_id AND d.source_id=c.doc_id WHERE c.archive_id=? AND d.filename=? AND c.title=?",new String[]{Long.toString(archiveId),filename,title})){
                            while(c.moveToNext()){
                                if(sha256Column(n(c,"id"),"text",n(c,"text_chars")).equalsIgnoreCase(item.getString("source_sha256"))){chapterId=n(c,"id");matches++;}
                            }
                        }
                        if(matches!=1)throw new IOException("改写稿章节与当前知识库原文不唯一或不匹配："+title);
                        setRewrite(chapterId,item.optString("content",""),item.optString("chapter_prompt",""),item.optString("extra_instruction",""));count++;
                    }reader.endArray();continue;
                }
                reader.skipValue();
            }reader.endObject();
            if(!"novel-memory-rewrites".equals(format)||version!=1)throw new IOException("不是受支持的桌面/手机改写稿文件");
            db.setTransactionSuccessful();return count;
        }finally{db.endTransaction();}
    }
}
