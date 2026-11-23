package com.novelmemory.mobile;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.Inflater;

/** Read-only SillyTavern V1/V2/V3 JSON and PNG character cards. Never executes card content. */
final class TavernCard {
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "name","description","personality","scenario","first_mes","mes_example","creator_notes",
            "system_prompt","post_history_instructions","alternate_greetings","character_book","tags",
            "creator","character_version","extensions","group_only_greetings","nickname",
            "creator_notes_multilingual","source","creation_date","modification_date","spec",
            "spec_version","data","first_message","example_dialogue"
    ));

    private TavernCard(){}

    static JSONObject parse(byte[] data) throws Exception {
        if(data==null||data.length==0)throw new IllegalArgumentException("角色卡文件为空");
        JSONObject card;String source;
        if(isPng(data)){
            String raw=pngText(data);
            if(raw.isEmpty())throw new IllegalArgumentException("PNG 中没有找到 chara/ccv3 角色卡元数据");
            card=new JSONObject(raw);source="PNG";
        }else{
            if(data.length>5_000_000)throw new IllegalArgumentException("角色卡文件超过 5MB");
            String text=new String(data,StandardCharsets.UTF_8);
            if(text.startsWith("\ufeff"))text=text.substring(1);
            card=new JSONObject(text);source="JSON";
        }
        JSONObject dataObj=card.optJSONObject("data");if(dataObj==null)dataObj=card;
        JSONObject profile=new JSONObject(dataObj.toString());
        String name=profile.optString("name","").trim();
        if(name.isEmpty())name=profile.optString("nickname","").trim();
        profile.put("name",name);
        profile.put("character_name",profile.optString("character_name",name));
        if(!profile.has("first_message"))profile.put("first_message",profile.optString("first_mes",""));
        if(!profile.has("example_dialogue"))profile.put("example_dialogue",profile.optString("mes_example",""));
        JSONObject extensions=profile.optJSONObject("extensions");if(extensions==null)extensions=new JSONObject();
        JSONObject unmapped=extensions.optJSONObject("tavern_unmapped");if(unmapped==null)unmapped=new JSONObject();
        Iterator<String> keys=profile.keys();
        while(keys.hasNext()){
            String key=keys.next();
            if(!KNOWN.contains(key)&&!"extensions".equals(key))unmapped.put(key,profile.opt(key));
        }
        if(unmapped.length()>0)extensions.put("tavern_unmapped",unmapped);
        profile.put("extensions",extensions);
        String spec=card.optString("spec",dataObj.optString("spec",""));
        String version=card.optString("spec_version",dataObj.optString("spec_version",""));
        String label=spec.toLowerCase().contains("v3")||version.startsWith("3")?"SillyTavern V3"
                :(spec.toLowerCase().contains("v2")||version.startsWith("2")?"SillyTavern V2":"SillyTavern V1/未标注");
        return new JSONObject().put("card",card).put("profile",profile).put("source",source)
                .put("spec",label).put("spec_version",version).put("preview",preview(profile,card,label));
    }

    private static String preview(JSONObject profile,JSONObject card,String spec) throws Exception {
        StringBuilder out=new StringBuilder();
        out.append("格式：").append(spec).append("\n\n");
        String[][] fields={
                {"姓名","character_name"},{"描述","description"},{"性格","personality"},
                {"场景","scenario"},{"首句","first_message"},{"示例对话","example_dialogue"},
                {"系统提示","system_prompt"},{"作者注释","post_history_instructions"},
                {"用户设定","user_persona"}
        };
        for(String[] field:fields){
            String value=profile.optString(field[1],"");
            out.append(field[0]).append("：").append(value.isEmpty()?"（空）":shorten(value,80)).append('\n');
        }
        JSONObject data=card.optJSONObject("data");if(data==null)data=card;
        JSONObject book=data.optJSONObject("character_book");
        if(book!=null){
            JSONArray entries=book.optJSONArray("entries");
            out.append("\n角色书/世界书（").append(entries==null?"未知":entries.length()).append(" 条）保存在扩展区，不会自动写入知识库。\n");
        }
        JSONArray greetings=data.optJSONArray("alternate_greetings");
        if(greetings!=null&&greetings.length()>0)out.append(greetings.length()).append(" 条备选开场白保存在扩展区。\n");
        JSONArray tags=data.optJSONArray("tags");
        if(tags!=null&&tags.length()>0)out.append(tags.length()).append(" 个标签保存在扩展区。\n");
        out.append("\n确认后写入当前知识库；之后仍可在角色卡编辑器中修改。");
        return out.toString();
    }

    private static boolean isPng(byte[] data){
        byte[] sig={(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        if(data.length<8)return false;
        for(int i=0;i<8;i++)if(data[i]!=sig[i])return false;
        return true;
    }

    private static int intAt(byte[] data,int offset){
        return ((data[offset]&0xff)<<24)|((data[offset+1]&0xff)<<16)|((data[offset+2]&0xff)<<8)|(data[offset+3]&0xff);
    }

    private static String pngText(byte[] data) throws Exception {
        int pos=8;
        while(pos+8<=data.length){
            int length=intAt(data,pos);
            if(length<0||pos+12+length>data.length)break;
            String type=new String(data,pos+4,4,StandardCharsets.US_ASCII);
            int body=pos+8;
            String key=null,value=null;
            if("tEXt".equals(type)){
                int zero=body;while(zero<body+length&&data[zero]!=0)zero++;
                key=new String(data,body,zero-body,StandardCharsets.ISO_8859_1);
                value=new String(data,zero+1,body+length-zero-1,StandardCharsets.ISO_8859_1);
            }else if("iTXt".equals(type)){
                int zero=body;while(zero<body+length&&data[zero]!=0)zero++;
                key=new String(data,body,zero-body,StandardCharsets.ISO_8859_1);
                int cursor=zero+1;
                for(int i=0;i<4;i++){while(cursor<body+length&&data[cursor]!=0)cursor++;cursor++;}
                value=new String(data,Math.min(cursor,body+length),Math.max(0,body+length-cursor),StandardCharsets.UTF_8);
            }else if("zTXt".equals(type)){
                int zero=body;while(zero<body+length&&data[zero]!=0)zero++;
                key=new String(data,body,zero-body,StandardCharsets.ISO_8859_1);
                if(zero+2<=body+length){
                    Inflater inflater=new Inflater();
                    try{
                        inflater.setInput(data,zero+2,body+length-zero-2);
                        ByteArrayOutputStream out=new ByteArrayOutputStream();
                        byte[] buffer=new byte[8192];
                        while(!inflater.finished()){int count=inflater.inflate(buffer);if(count==0&&inflater.needsInput())break;out.write(buffer,0,count);}
                        value=new String(out.toByteArray(),StandardCharsets.UTF_8);
                    }finally{inflater.end();}
                }
            }
            if(key!=null&&("chara".equalsIgnoreCase(key)||"ccv3".equalsIgnoreCase(key))&&value!=null){
                try{
                    byte[] decoded=Base64.decode(value.replaceAll("\\s+",""),Base64.DEFAULT);
                    String text=new String(decoded,StandardCharsets.UTF_8).trim();
                    if(text.startsWith("{"))return text;
                }catch(Exception ignored){}
            }
            pos+=12+length;
            if("IEND".equals(type))break;
        }
        return "";
    }

    private static String shorten(String text,int limit){
        String clean=text.replaceAll("\\s+"," ").trim();
        return clean.length()>limit?clean.substring(0,limit)+"…":clean;
    }
}
