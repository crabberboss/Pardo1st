package com.novelmemory.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** The same standalone v1 JSON contract used by the desktop rewrite-template importer. */
final class RewriteTemplate {
    private final Context context;
    RewriteTemplate(Context context){this.context=context;}

    static JSONObject validate(JSONObject input) throws Exception {
        if(!"novel-memory-rewrite-template".equals(input.optString("format"))||input.optInt("version")!=1)
            throw new IllegalArgumentException("不是桌面/安卓通用的改写模板 v1 JSON");
        String name=input.optString("name","").trim();
        if(name.isEmpty())throw new IllegalArgumentException("请填写改写模板名称");
        JSONObject filter=input.optJSONObject("filter"),rewrite=input.optJSONObject("rewrite");
        if(filter==null||rewrite==null)throw new IllegalArgumentException("模板缺少 filter 或 rewrite 对象");
        String mode=filter.optString("mode","all");
        if(!"all".equals(mode)&&!"any".equals(mode))throw new IllegalArgumentException("标签组合方式只能是 all 或 any");
        JSONArray names=filter.optJSONArray("tags");if(names==null)names=new JSONArray();
        JSONArray tags=input.optJSONArray("tags");if(tags==null)tags=new JSONArray();
        JSONArray normalizedNames=new JSONArray(),normalizedTags=new JSONArray();
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<names.length();i++){String value=names.optString(i,"").trim();if(!value.isEmpty()&&seen.add(value))normalizedNames.put(value);}
        for(int i=0;i<tags.length();i++){
            JSONObject tag=tags.optJSONObject(i);if(tag==null||tag.optString("name","").trim().isEmpty())throw new IllegalArgumentException("标签定义缺少名称");
            normalizedTags.put(new JSONObject().put("name",tag.getString("name").trim()).put("color",tag.optString("color","#a557b8"))
                    .put("recognition_prompt",tag.optString("recognition_prompt","")));
        }
        return new JSONObject().put("format","novel-memory-rewrite-template").put("version",1).put("name",name)
                .put("tags",normalizedTags).put("filter",new JSONObject().put("mode",mode).put("tags",normalizedNames))
                .put("rewrite",new JSONObject().put("global_prompt",rewrite.optString("global_prompt",""))
                        .put("chapter_prompt",rewrite.optString("chapter_prompt",""))
                        .put("rules",rewrite.optString("rules","")));
    }

    private String filename(long archiveId){return "rewrite-template-"+archiveId+".json";}
    JSONObject load(long archiveId) throws Exception {
        try(FileInputStream input=context.openFileInput(filename(archiveId))){
            byte[] bytes=new byte[1_000_001];int size=0,count;
            while(size<bytes.length&&(count=input.read(bytes,size,bytes.length-size))!=-1)size+=count;
            if(size>=bytes.length)throw new IllegalArgumentException("模板超过 1MB");
            return validate(new JSONObject(new String(bytes,0,size,StandardCharsets.UTF_8)));
        }catch(java.io.FileNotFoundException ignored){return null;}
    }
    JSONObject save(long archiveId,JSONObject input) throws Exception {
        JSONObject value=validate(input);
        try(FileOutputStream output=context.openFileOutput(filename(archiveId),Context.MODE_PRIVATE)){
            output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return value;
    }
    void delete(long archiveId){context.deleteFile(filename(archiveId));}
}
