package com.novelmemory.mobile;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stores online-generated summary and original-text embeddings. No model runs on the phone. */
final class VectorMemory {
    interface Progress { void update(int done,int total,String detail); }
    private final Store store;private final Api api;
    VectorMemory(Store store,Api api){this.store=store;this.api=api;}

    private static String digest(String value) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();
    }
    private static byte[] pack(float[] vector){ByteBuffer out=ByteBuffer.allocate(vector.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float n:vector)out.putFloat(n);return out.array();}
    private static float[] unpack(byte[] bytes){ByteBuffer in=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] out=new float[bytes.length/4];for(int i=0;i<out.length;i++)out[i]=in.getFloat();return out;}
    private static boolean usable(String summary){return summary!=null&&!summary.trim().isEmpty()&&summary.contains("剧情概览")
        &&!summary.contains("本地零 Token 抽取兜底")&&!summary.startsWith("[本地归纳]");}
    private static final class Row {long id;String summary,hash;Row(long id,String summary,String hash){this.id=id;this.summary=summary;this.hash=hash;}}
    static final class SourceHit {double score;int start,end;SourceHit(double score,int start,int end){this.score=score;this.start=start;this.end=end;}}
    private String lastQueryKey="";private float[] lastQueryVector;
    private float[] queryVector(String provider,String model,String query) throws Exception {
        String key=provider+"\u0000"+model+"\u0000"+query;
        if(!key.equals(lastQueryKey)||lastQueryVector==null){
            lastQueryVector=api.embed(provider,model,new JSONArray().put(query.substring(0,Math.min(1200,query.length()))))[0];
            lastQueryKey=key;
        }
        return lastQueryVector;
    }

    synchronized JSONObject config(long archiveId) throws Exception {
        String provider="siliconflow",model="BAAI/bge-m3";boolean enabled=false;int count=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT enabled,provider,model FROM vector_configs WHERE archive_id=?",new String[]{Long.toString(archiveId)})){
            if(c.moveToFirst()){enabled=c.getInt(0)!=0;provider=c.getString(1);model=c.getString(2);}
        }
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM summary_vectors WHERE archive_id=? AND provider=? AND model=?",new String[]{Long.toString(archiveId),provider,model})){
            if(c.moveToFirst())count=c.getInt(0);
        }
        return new JSONObject().put("archive_id",archiveId).put("enabled",enabled).put("provider",provider).put("model",model).put("indexed",count);
    }
    synchronized JSONObject saveConfig(long archiveId,boolean enabled,String provider,String model) throws Exception {
        store.archive(archiveId);
        if(!"siliconflow".equals(provider)&&!"openrouter".equals(provider))throw new IllegalArgumentException("在线向量化支持硅基流动或 OpenRouter");
        model=model==null?"":model.trim();if(model.isEmpty()||model.length()>180)throw new IllegalArgumentException("请填写有效的嵌入模型 ID");
        ContentValues v=new ContentValues();v.put("archive_id",archiveId);v.put("enabled",enabled?1:0);v.put("provider",provider);v.put("model",model);
        store.getWritableDatabase().insertWithOnConflict("vector_configs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        return config(archiveId);
    }
    synchronized JSONObject sourceConfig(long archiveId) throws Exception {
        String provider="siliconflow",model="BAAI/bge-m3";boolean enabled=false;int count=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT enabled,provider,model FROM source_vector_configs WHERE archive_id=?",new String[]{Long.toString(archiveId)})){
            if(c.moveToFirst()){enabled=c.getInt(0)!=0;provider=c.getString(1);model=c.getString(2);}
        }
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM source_vectors WHERE archive_id=? AND provider=? AND model=?",new String[]{Long.toString(archiveId),provider,model})){
            if(c.moveToFirst())count=c.getInt(0);
        }
        return new JSONObject().put("archive_id",archiveId).put("enabled",enabled).put("provider",provider).put("model",model).put("indexed_parts",count);
    }
    synchronized JSONObject saveSourceConfig(long archiveId,boolean enabled,String provider,String model) throws Exception {
        store.archive(archiveId);
        if(!"siliconflow".equals(provider)&&!"openrouter".equals(provider))throw new IllegalArgumentException("安卓原文索引仅支持硅基流动或 OpenRouter 在线嵌入");
        model=model==null?"":model.trim();if(model.isEmpty()||model.length()>180)throw new IllegalArgumentException("请填写嵌入模型 ID");
        ContentValues v=new ContentValues();v.put("archive_id",archiveId);v.put("enabled",enabled?1:0);v.put("provider",provider);v.put("model",model);
        store.getWritableDatabase().insertWithOnConflict("source_vector_configs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        return sourceConfig(archiveId);
    }

    synchronized void buildSource(long archiveId,String provider,String model,AtomicBoolean cancel,Progress progress) throws Exception {
        saveSourceConfig(archiveId,true,provider,model);
        List<Long> ids=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM chapters WHERE archive_id=? ORDER BY doc_id,ord,id",new String[]{Long.toString(archiveId)})){
            while(c.moveToNext())ids.add(c.getLong(0));
        }
        if(ids.isEmpty())throw new IllegalStateException("当前知识库没有章节原文");
        int done=0;
        for(long id:ids){
            if(cancel.get())break;
            String source=store.vectorSource(id),hash=digest(source);
            List<int[]> spans=new ArrayList<>();
            int sourcePoints=source.codePointCount(0,source.length());
            for(int start=0;start<sourcePoints;start+=540){int end=Math.min(sourcePoints,start+600);String part=source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end));if(!part.trim().isEmpty())spans.add(new int[]{start,end});if(end==sourcePoints)break;}
            java.util.HashSet<Integer> cached=new java.util.HashSet<>();
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT part FROM source_vectors WHERE archive_id=? AND chapter_id=? AND provider=? AND model=? AND source_hash=?",new String[]{Long.toString(archiveId),Long.toString(id),provider,model,hash})){
                while(c.moveToNext())cached.add(c.getInt(0));
            }
            List<Integer> pending=new ArrayList<>();for(int i=0;i<spans.size();i++)if(!cached.contains(i))pending.add(i);
            if(!pending.isEmpty()){
                for(int offset=0;offset<pending.size();offset+=8){
                    if(cancel.get())break;
                    int end=Math.min(pending.size(),offset+8);JSONArray texts=new JSONArray();
                    for(int j=offset;j<end;j++){int i=pending.get(j);texts.put(source.substring(source.offsetByCodePoints(0,spans.get(i)[0]),source.offsetByCodePoints(0,spans.get(i)[1])));}
                    float[][] embedded=api.embed(provider,model,texts);
                    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
                    try{
                        for(int j=offset;j<end;j++){
                            int i=pending.get(j);
                            ContentValues v=new ContentValues();v.put("archive_id",archiveId);v.put("chapter_id",id);v.put("part",i);v.put("char_start",spans.get(i)[0]);v.put("char_end",spans.get(i)[1]);v.put("provider",provider);v.put("model",model);v.put("source_hash",hash);v.put("dimensions",embedded[j-offset].length);v.put("vector",pack(embedded[j-offset]));
                            db.insertWithOnConflict("source_vectors",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                        }
                        db.setTransactionSuccessful();
                    }finally{db.endTransaction();}
                }
                if(!cancel.get())store.getWritableDatabase().delete("source_vectors","archive_id=? AND chapter_id=? AND provider=? AND model=? AND (source_hash<>? OR part>=?)",new String[]{Long.toString(archiveId),Long.toString(id),provider,model,hash,Integer.toString(spans.size())});
            }
            if(cancel.get())break;
            done++;progress.update(done,ids.size(),"原文向量：已处理 "+done+"/"+ids.size()+" 章");
        }
    }

    synchronized Map<Long,SourceHit> searchSource(long archiveId,String query,int cutoff) throws Exception {
        JSONObject cfg=sourceConfig(archiveId);Map<Long,SourceHit> hits=new HashMap<>();
        if(!cfg.optBoolean("enabled")||cfg.optInt("indexed_parts",0)==0)return hits;
        String provider=cfg.getString("provider"),model=cfg.getString("model");
        float[] question=queryVector(provider,model,query);
        java.util.HashSet<Long> allowed=new java.util.HashSet<>();int position=0;
        try(Cursor chapters=store.getReadableDatabase().rawQuery("SELECT id FROM chapters WHERE archive_id=? ORDER BY doc_id,ord,id",new String[]{Long.toString(archiveId)})){
            while(chapters.moveToNext()){position++;if(cutoff>0&&position>cutoff)break;allowed.add(chapters.getLong(0));}
        }
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT c.id,v.char_start,v.char_end,v.source_hash,v.dimensions,v.vector FROM chapters c JOIN source_vectors v ON v.archive_id=c.archive_id AND v.chapter_id=c.id AND v.provider=? AND v.model=? WHERE c.archive_id=? ORDER BY c.doc_id,c.ord,c.id,v.part",new String[]{provider,model,Long.toString(archiveId)})){
            long previous=-1;String hash="";
            while(c.moveToNext()){
                long id=c.getLong(0);if(!allowed.contains(id))continue;
                if(id!=previous){previous=id;hash=digest(store.vectorSource(id));}
                byte[] bytes=c.getBlob(5);if(bytes==null||c.getInt(4)!=question.length||bytes.length!=question.length*4||!hash.equals(c.getString(3)))continue;
                float[] vector=unpack(bytes);double score=0;for(int i=0;i<vector.length;i++)score+=vector[i]*question[i];
                SourceHit old=hits.get(id);if(old==null||score>old.score)hits.put(id,new SourceHit(score,c.getInt(1),c.getInt(2)));
            }
        }
        return hits;
    }
    synchronized void build(long archiveId,String provider,String model,AtomicBoolean cancel,Progress progress) throws Exception {
        saveConfig(archiveId,true,provider,model);
        List<Row> pending=new ArrayList<>();int available=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT c.id,c.summary,v.summary_hash,v.provider,v.model FROM chapters c LEFT JOIN summary_vectors v ON v.archive_id=c.archive_id AND v.chapter_id=c.id WHERE c.archive_id=? ORDER BY c.doc_id,c.ord,c.id",new String[]{Long.toString(archiveId)})){
            while(c.moveToNext()){
                String summary=c.getString(1);if(!usable(summary))continue;available++;
                String hash=digest(summary);
                if(!hash.equals(c.getString(2))||!provider.equals(c.getString(3))||!model.equals(c.getString(4)))pending.add(new Row(c.getLong(0),summary,hash));
            }
        }
        if(available==0)throw new IllegalStateException("当前知识库没有可向量化的完整章节总结，请先生成或补齐章节记忆");
        int done=available-pending.size();progress.update(done,available,"已复用 "+done+" 章；待向量化 "+pending.size()+" 章");
        for(int offset=0;offset<pending.size();offset+=8){
            if(cancel.get())break;
            int end=Math.min(pending.size(),offset+8);JSONArray input=new JSONArray();
            for(int i=offset;i<end;i++)input.put(pending.get(i).summary.substring(0,Math.min(2800,pending.get(i).summary.length())));
            float[][] vectors=api.embed(provider,model,input);
            SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try{
                for(int i=offset;i<end;i++){
                    Row row=pending.get(i);ContentValues v=new ContentValues();v.put("archive_id",archiveId);v.put("chapter_id",row.id);v.put("provider",provider);v.put("model",model);v.put("summary_hash",row.hash);v.put("dimensions",vectors[i-offset].length);v.put("vector",pack(vectors[i-offset]));
                    db.insertWithOnConflict("summary_vectors",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            done+=end-offset;progress.update(done,available,"已向量化 "+done+"/"+available+" 章");
        }
    }
    synchronized Map<Long,Double> search(long archiveId,String query,int cutoff) throws Exception {
        JSONObject cfg=config(archiveId);Map<Long,Double> results=new HashMap<>();if(!cfg.optBoolean("enabled")||cfg.optInt("indexed",0)==0)return results;
        String provider=cfg.getString("provider"),model=cfg.getString("model");
        float[] queryVector=queryVector(provider,model,query);
        int position=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT c.id,c.summary,v.summary_hash,v.dimensions,v.vector FROM chapters c LEFT JOIN summary_vectors v ON v.archive_id=c.archive_id AND v.chapter_id=c.id AND v.provider=? AND v.model=? WHERE c.archive_id=? ORDER BY c.doc_id,c.ord,c.id",new String[]{provider,model,Long.toString(archiveId)})){
            while(c.moveToNext()){
                position++;if(cutoff>0&&position>cutoff)break;
                byte[] bytes=c.getBlob(4);if(bytes==null||c.getInt(3)!=queryVector.length||bytes.length!=queryVector.length*4)continue;
                String summary=c.getString(1);if(!usable(summary)||!digest(summary).equals(c.getString(2)))continue;
                float[] vector=unpack(bytes);double score=0;for(int i=0;i<vector.length;i++)score+=vector[i]*queryVector[i];
                results.put(c.getLong(0),score);
            }
        }
        return results;
    }
}
