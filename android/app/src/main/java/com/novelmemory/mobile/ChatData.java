package com.novelmemory.mobile;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

final class ChatData {
    private final Store store;
    ChatData(Store store){this.store=store;}
    private SQLiteDatabase db(){return store.getWritableDatabase();}
    private static String[] arg(long id){return new String[]{Long.toString(id)};}
    private static long now(){return System.currentTimeMillis()/1000;}
    private static String str(Cursor c,String name){return Store.s(c,name);}
    private static long num(Cursor c,String name){return Store.n(c,name);}

    synchronized JSONObject preset(long archiveId) throws Exception {
        store.archive(archiveId);
        try(Cursor c=db().rawQuery("SELECT config_json FROM chat_presets WHERE archive_id=?",arg(archiveId))){
            if(c.moveToFirst())return new JSONObject(c.getString(0));
        }
        return new JSONObject().put("system_prompt","你是严谨的小说问答助手。以检索到的知识库和原文为准，不确定时明确说明。")
                .put("role_description","小说资料研究助手").put("answer_style","条理清晰")
                .put("response_length","适中").put("spoiler_policy","允许全书剧情")
                .put("citation_mode","列出参考章节").put("history_turns",4)
                .put("custom_instruction","").put("use_archive_summary",true)
                .put("use_chapter_summary",true).put("use_source_text",true)
                .put("top_k",5).put("max_tokens",1800).put("temperature",0.6).put("roleplay_prompt","这是书中虚构世界的文学角色扮演。保持角色身份、人物性格与对话连续性，不要复读；遵守 API 服务商安全规则。");
    }

    synchronized JSONObject savePreset(long archiveId,JSONObject config) throws Exception {
        store.archive(archiveId);
        JSONObject merged=preset(archiveId);java.util.Iterator<String> keys=config.keys();
        while(keys.hasNext()){String key=keys.next();merged.put(key,config.get(key));}
        ContentValues v=new ContentValues();v.put("archive_id",archiveId);v.put("config_json",merged.toString());
        db().insertWithOnConflict("chat_presets",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        return merged;
    }

    synchronized JSONArray profiles(long archiveId) throws Exception {
        JSONArray out=new JSONArray();
        try(Cursor c=db().rawQuery("SELECT id,name,data_json FROM roleplay_profiles WHERE archive_id=? ORDER BY updated_at DESC,id DESC",arg(archiveId))){
            while(c.moveToNext())out.put(new JSONObject(c.getString(2)).put("id",c.getLong(0)).put("name",c.getString(1)));
        }return out;
    }

    synchronized JSONObject profile(long archiveId,long profileId) throws Exception {
        try(Cursor c=db().rawQuery("SELECT data_json FROM roleplay_profiles WHERE archive_id=? AND id=?",new String[]{Long.toString(archiveId),Long.toString(profileId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("角色卡不存在或不属于当前知识库");
            return new JSONObject(c.getString(0)).put("id",profileId);
        }
    }

    synchronized JSONObject saveProfile(long archiveId,JSONObject data) throws Exception {
        store.archive(archiveId);long id=data.optLong("id",0);String name=data.optString("name","").trim();
        if(name.isEmpty())name=data.optString("character_name","").trim();
        if(name.isEmpty())throw new IllegalArgumentException("请填写角色卡名称");
        JSONObject copy=new JSONObject(data.toString());copy.put("name",name);copy.remove("id");
        if(id>0){JSONObject existing=profile(archiveId,id);if(!copy.has("extensions")&&existing.has("extensions"))copy.put("extensions",existing.get("extensions"));}
        ContentValues values=new ContentValues();values.put("name",name);values.put("data_json",copy.toString());values.put("updated_at",now());
        if(id>0){profile(archiveId,id);db().update("roleplay_profiles",values,"archive_id=? AND id=?",new String[]{Long.toString(archiveId),Long.toString(id)});}
        else {values.put("archive_id",archiveId);id=db().insertOrThrow("roleplay_profiles",null,values);}
        return profile(archiveId,id);
    }

    synchronized void deleteProfile(long archiveId,long profileId) throws Exception {
        profile(archiveId,profileId);SQLiteDatabase database=db();database.beginTransaction();
        try {
            for(JSONObject session:arrayObjects(sessions(archiveId,"roleplay",profileId)))database.delete("messages","session_id=?",arg(session.getLong("id")));
            database.delete("chat_sessions","archive_id=? AND profile_id=?",new String[]{Long.toString(archiveId),Long.toString(profileId)});
            database.delete("roleplay_profiles","archive_id=? AND id=?",new String[]{Long.toString(archiveId),Long.toString(profileId)});
            database.setTransactionSuccessful();
        }finally{database.endTransaction();}
    }

    private static java.util.List<JSONObject> arrayObjects(JSONArray data) throws Exception {
        java.util.ArrayList<JSONObject> out=new java.util.ArrayList<>();for(int i=0;i<data.length();i++)out.add(data.getJSONObject(i));return out;
    }

    synchronized JSONArray sessions(long archiveId,String mode,long profileId) throws Exception {
        store.archive(archiveId);JSONArray out=new JSONArray();
        try(Cursor c=db().rawQuery("SELECT id,archive_id,mode,profile_id,name,cutoff,start_cutoff,updated_at FROM chat_sessions WHERE archive_id=? AND mode=? AND profile_id=? ORDER BY updated_at DESC,id DESC",new String[]{Long.toString(archiveId),mode,Long.toString(profileId)})){
            while(c.moveToNext())out.put(sessionRow(c));
        }return out;
    }

    private static JSONObject sessionRow(Cursor c) throws Exception {
        return new JSONObject().put("id",num(c,"id")).put("archive_id",num(c,"archive_id"))
                .put("mode",str(c,"mode")).put("profile_id",num(c,"profile_id"))
                .put("name",str(c,"name")).put("cutoff",num(c,"cutoff"))
                .put("start_cutoff",num(c,"start_cutoff")).put("updated_at",num(c,"updated_at"));
    }

    synchronized JSONObject session(long id) throws Exception {
        try(Cursor c=db().rawQuery("SELECT id,archive_id,mode,profile_id,name,cutoff,start_cutoff,updated_at FROM chat_sessions WHERE id=?",arg(id))){
            if(!c.moveToFirst())throw new IllegalArgumentException("对话存档不存在");return sessionRow(c);
        }
    }

    synchronized JSONObject ensureSession(long archiveId,String mode,long profileId,long sessionId) throws Exception {
        if(!"roleplay".equals(mode)){mode="qa";profileId=0;}
        if(profileId>0)profile(archiveId,profileId);
        if("roleplay".equals(mode)&&profileId==0)throw new IllegalArgumentException("请先选择角色卡");
        if(sessionId>0){JSONObject s=session(sessionId);if(s.getLong("archive_id")!=archiveId||!s.getString("mode").equals(mode)||s.getLong("profile_id")!=profileId)throw new IllegalArgumentException("对话存档与当前知识库或角色卡不匹配");return s;}
        JSONArray rows=sessions(archiveId,mode,profileId);
        if(rows.length()>0)return rows.getJSONObject(0);
        return createSession(archiveId,mode,profileId,"对话 1");
    }

    synchronized JSONObject createSession(long archiveId,String mode,long profileId,String name) throws Exception {
        store.archive(archiveId);if(!"roleplay".equals(mode)){mode="qa";profileId=0;}
        JSONObject card=null;if("roleplay".equals(mode))card=profile(archiveId,profileId);
        long cutoff=card==null?0:Math.max(0,card.optLong("timeline_start_cutoff",card.optLong("timeline_cutoff",0)));
        String label=name==null||name.trim().isEmpty()?"新对话":name.trim();long timestamp=now();
        ContentValues v=new ContentValues();v.put("archive_id",archiveId);v.put("mode",mode);v.put("profile_id",profileId);
        v.put("name",label);v.put("cutoff",cutoff);v.put("start_cutoff",cutoff);v.put("created_at",timestamp);v.put("updated_at",timestamp);
        long id=db().insertOrThrow("chat_sessions",null,v);
        if(card!=null&&!card.optString("first_message","").trim().isEmpty())addMessage(id,"assistant",card.getString("first_message"),cutoff,cutoff);
        return session(id);
    }

    synchronized void renameSession(long sessionId,String name) throws Exception {
        session(sessionId);if(name==null||name.trim().isEmpty())throw new IllegalArgumentException("存档名称不能为空");
        ContentValues v=new ContentValues();v.put("name",name.trim());v.put("updated_at",now());db().update("chat_sessions",v,"id=?",arg(sessionId));
    }

    synchronized JSONArray messages(long sessionId) throws Exception {
        session(sessionId);JSONArray out=new JSONArray();
        try(Cursor c=db().rawQuery("SELECT id,role,content,timeline_before,timeline_after FROM messages WHERE session_id=? ORDER BY id",arg(sessionId))){
            while(c.moveToNext())out.put(messageRow(c));
        }return out;
    }

    private static JSONObject messageRow(Cursor c) throws Exception {
        return new JSONObject().put("id",num(c,"id")).put("role",str(c,"role"))
                .put("content",str(c,"content")).put("timeline_before",num(c,"timeline_before"))
                .put("timeline_after",num(c,"timeline_after"));
    }

    synchronized JSONObject message(long sessionId,long messageId) throws Exception {
        try(Cursor c=db().rawQuery("SELECT id,role,content,timeline_before,timeline_after FROM messages WHERE id=? AND session_id=?",new String[]{Long.toString(messageId),Long.toString(sessionId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("消息不存在或不属于当前存档");return messageRow(c);
        }
    }

    synchronized JSONObject precedingUser(long sessionId,long assistantId) throws Exception {
        JSONObject answer=message(sessionId,assistantId);if(!"assistant".equals(answer.getString("role")))throw new IllegalArgumentException("只能重新生成模型回答");
        try(Cursor c=db().rawQuery("SELECT id,role,content,timeline_before,timeline_after FROM messages WHERE session_id=? AND role='user' AND id<? ORDER BY id DESC LIMIT 1",new String[]{Long.toString(sessionId),Long.toString(assistantId)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("该回答前没有用户输入");return messageRow(c);
        }
    }

    synchronized JSONArray historyBefore(long sessionId,long beforeMessageId,int limit) throws Exception {
        JSONArray reverse=new JSONArray(),out=new JSONArray();
        String sql="SELECT id,role,content,timeline_before,timeline_after FROM messages WHERE session_id=?"+(beforeMessageId>0?" AND id<?":"")+" ORDER BY id DESC LIMIT ?";
        String[] args=beforeMessageId>0?new String[]{Long.toString(sessionId),Long.toString(beforeMessageId),Integer.toString(Math.max(0,limit))}:new String[]{Long.toString(sessionId),Integer.toString(Math.max(0,limit))};
        try(Cursor c=db().rawQuery(sql,args)){
            while(c.moveToNext())reverse.put(messageRow(c));
        }
        for(int i=reverse.length()-1;i>=0;i--)out.put(reverse.getJSONObject(i));return out;
    }

    private long addMessage(long sessionId,String role,String content,long before,long after) throws Exception {
        JSONObject session=session(sessionId);ContentValues v=new ContentValues();v.put("archive_id",session.getLong("archive_id"));
        v.put("session_id",sessionId);v.put("role",role);v.put("content",content);v.put("timeline_before",before);v.put("timeline_after",after);
        return db().insertOrThrow("messages",null,v);
    }

    synchronized void appendPair(long sessionId,String userText,String answer,long before,long after) throws Exception {
        SQLiteDatabase database=db();database.beginTransaction();
        try {addMessage(sessionId,"user",userText,before,before);addMessage(sessionId,"assistant",answer,before,after);setCutoff(sessionId,after);database.setTransactionSuccessful();}
        finally{database.endTransaction();}
    }

    private void setCutoff(long sessionId,long cutoff) {
        ContentValues v=new ContentValues();v.put("cutoff",cutoff);v.put("updated_at",now());db().update("chat_sessions",v,"id=?",arg(sessionId));
    }

    synchronized void replaceResponse(long sessionId,long assistantId,String userText,String answer,long after) throws Exception {
        JSONObject user=precedingUser(sessionId,assistantId);SQLiteDatabase database=db();database.beginTransaction();
        try {
            ContentValues u=new ContentValues();u.put("content",userText);database.update("messages",u,"id=? AND session_id=?",new String[]{Long.toString(user.getLong("id")),Long.toString(sessionId)});
            ContentValues a=new ContentValues();a.put("content",answer);a.put("timeline_after",after);database.update("messages",a,"id=? AND session_id=?",new String[]{Long.toString(assistantId),Long.toString(sessionId)});
            ContentValues s=new ContentValues();s.put("updated_at",now());database.update("chat_sessions",s,"id=?",arg(sessionId));
            database.setTransactionSuccessful();
        }finally{database.endTransaction();}
    }

    synchronized void editMessage(long sessionId,long messageId,String content) throws Exception {
        message(sessionId,messageId);if(content==null||content.trim().isEmpty())throw new IllegalArgumentException("消息不能为空");
        ContentValues v=new ContentValues();v.put("content",content.trim());db().update("messages",v,"session_id=? AND id=?",new String[]{Long.toString(sessionId),Long.toString(messageId)});
        ContentValues s=new ContentValues();s.put("updated_at",now());db().update("chat_sessions",s,"id=?",arg(sessionId));
    }

    synchronized void clearSession(long sessionId) throws Exception {
        JSONObject session=session(sessionId);SQLiteDatabase database=db();database.beginTransaction();
        try {database.delete("messages","session_id=?",arg(sessionId));setCutoff(sessionId,session.getLong("start_cutoff"));database.setTransactionSuccessful();}
        finally{database.endTransaction();}
    }

    synchronized void deleteSession(long sessionId) throws Exception {
        session(sessionId);SQLiteDatabase database=db();database.beginTransaction();
        try {database.delete("messages","session_id=?",arg(sessionId));database.delete("chat_sessions","id=?",arg(sessionId));database.setTransactionSuccessful();}
        finally{database.endTransaction();}
    }
}
