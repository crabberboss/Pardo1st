package com.novelmemory.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Api {
    private final SharedPreferences prefs;
    private static final String KEY_ALIAS="novel_memory_api_keys";
    Api(Context context){prefs=context.getSharedPreferences("api_profiles",Context.MODE_PRIVATE);}

    static String defaultUrl(String provider){
        switch(provider){
            case "deepseek":return "https://api.deepseek.com";
            case "siliconflow":return "https://api.siliconflow.cn/v1";
            case "openrouter":return "https://openrouter.ai/api/v1";
            case "gemini":return "https://generativelanguage.googleapis.com/v1beta/openai";
            case "glm":return "https://open.bigmodel.cn/api/paas/v4";
            default:return "http://127.0.0.1:1234/v1";
        }
    }
    static String defaultModel(String provider){
        switch(provider){
            // deepseek-chat / deepseek-reasoner were retired on 2026-07-24.
            case "deepseek":return "deepseek-v4-flash";
            case "siliconflow":return "Pro/zai-org/GLM-4.7";
            case "openrouter":return "deepseek/deepseek-v4-flash";
            case "gemini":return "gemini-2.5-flash";
            case "glm":return "glm-4.6";
            default:return "";
        }
    }
    static String providerLabel(String provider){
        switch(provider){
            case "deepseek":return "DeepSeek";
            case "siliconflow":return "硅基流动";
            case "openrouter":return "OpenRouter";
            case "gemini":return "Gemini";
            case "glm":return "GLM（智谱）";
            default:return "LM Studio";
        }
    }
    private static boolean keyRequired(String provider){return !"lmstudio".equals(provider);}
    private static String validProvider(String value){
        if(!"deepseek".equals(value)&&!"siliconflow".equals(value)&&!"lmstudio".equals(value)&&!"openrouter".equals(value)&&!"gemini".equals(value)&&!"glm".equals(value))throw new IllegalArgumentException("不支持的 API 渠道");return value;
    }
    static String normalizeUrl(String provider,String url){
        String base=url==null?"":url.trim().replaceAll("/+$","");
        if(base.endsWith("/chat/completions"))base=base.substring(0,base.length()-"/chat/completions".length()).replaceAll("/+$","");
        if("deepseek".equals(provider)&&base.endsWith("/v1"))base=base.substring(0,base.length()-3).replaceAll("/+$","");
        if("siliconflow".equals(provider)&&!base.endsWith("/v1"))base=base+"/v1";
        if("openrouter".equals(provider)){
            if(base.endsWith("/api"))base=base+"/v1";
            else if(!base.endsWith("/v1"))base=base+"/v1";
        }
        return base;
    }
    synchronized JSONObject config(String provider) throws Exception {
        provider=validProvider(provider);
        String url=normalizeUrl(provider,prefs.getString(provider+".url",defaultUrl(provider)));
        String model=prefs.getString(provider+".model",defaultModel(provider));
        if("deepseek".equals(provider)&&("deepseek-chat".equals(model)||"deepseek-reasoner".equals(model)))model=defaultModel(provider);
        return new JSONObject().put("provider",provider).put("label",providerLabel(provider))
                .put("base_url",url).put("model",model)
                .put("default_model",defaultModel(provider)).put("has_key",prefs.contains(provider+".key"));
    }
    synchronized void save(String provider,String url,String model,String key,boolean clearKey) throws Exception {
        provider=validProvider(provider);
        String base=normalizeUrl(provider,url==null?"":url);
        if(!base.startsWith("https://")&&!base.startsWith("http://"))throw new IllegalArgumentException("API 地址必须是 http(s):// URL");
        SharedPreferences.Editor e=prefs.edit().putString(provider+".url",base).putString(provider+".model",model==null?"":model.trim());
        if(clearKey)e.remove(provider+".key");else if(key!=null&&!key.isEmpty())e.putString(provider+".key",encrypt(key));
        e.apply();
    }
    synchronized String selected(){return prefs.getString("selected","deepseek");}
    synchronized void select(String provider){prefs.edit().putString("selected",validProvider(provider)).apply();}

    private static SecretKey secretKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY_ALIAS)){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)store.getEntry(KEY_ALIAS,null)).getSecretKey();
    }
    private static String encrypt(String text) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secretKey());
        byte[] iv=cipher.getIV(),ciphertext=cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] all=new byte[iv.length+ciphertext.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(ciphertext,0,all,iv.length,ciphertext.length);
        return Base64.encodeToString(all,Base64.NO_WRAP);
    }
    private static String decrypt(String data) throws Exception {
        byte[] all=Base64.decode(data,Base64.NO_WRAP);byte[] iv=new byte[12],ciphertext=new byte[all.length-12];
        System.arraycopy(all,0,iv,0,12);System.arraycopy(all,12,ciphertext,0,ciphertext.length);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,iv));
        return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
    }
    private synchronized String key(String provider) throws Exception {String encoded=prefs.getString(provider+".key","");return encoded.isEmpty()?"":decrypt(encoded);}

    boolean isDeepseekSelected(){return "deepseek".equals(selected());}
    String searchDeepseek(String query) throws Exception {
        String token=key("deepseek");
        if(token.isEmpty())throw new IllegalStateException("联网搜索已开启，请先配置 DeepSeek API Key");
        String text=query.trim();if(text.length()>128)text=text.substring(0,128);
        JSONObject body=new JSONObject().put("query",text).put("count",3).put("need_content",false);
        HttpResult result=request("deepseek","POST","https://api.deepseek.com/v1/tools/search",token,body,20);
        if(result.status>=400)throw new IllegalStateException("联网搜索失败：HTTP "+result.status+"；请检查 DeepSeek 搜索权限或额度");
        JSONArray rows=new JSONObject(result.body).optJSONArray("results");
        if(rows==null||rows.length()==0)return "未找到网页结果。";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<Math.min(3,rows.length());i++){JSONObject item=rows.getJSONObject(i);out.append("标题：").append(item.optString("title")).append("\n网址：").append(item.optString("url")).append("\n摘要：").append(item.optString("summary",item.optString("snippet"))).append("\n\n");}
        return out.toString();
    }

    private JSONObject resolve(String provider,String url,String model,String inputKey,boolean clearKey) throws Exception {
        provider=validProvider(provider);
        String base=normalizeUrl(provider,url==null||url.trim().isEmpty()?prefs.getString(provider+".url",defaultUrl(provider)):url);
        if(!base.startsWith("https://")&&!base.startsWith("http://"))throw new IllegalArgumentException("API 地址必须是 http(s):// URL");
        String chosen=model==null||model.trim().isEmpty()?prefs.getString(provider+".model",defaultModel(provider)):model.trim();
        String apiKey=clearKey?"":((inputKey!=null&&!inputKey.trim().isEmpty())?inputKey.trim():key(provider));
        String source=(inputKey!=null&&!inputKey.trim().isEmpty())?"input":(apiKey.isEmpty()?"missing":"saved");
        return new JSONObject().put("provider",provider).put("base_url",base).put("model",chosen)
                .put("api_key",apiKey).put("key_source",source);
    }

    private static final class HttpResult {
        int status;String body;String url;
        HttpResult(int status,String body,String url){this.status=status;this.body=body;this.url=url;}
    }
    private HttpResult request(String provider,String method,String url,String apiKey,JSONObject body,int timeoutSeconds) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setRequestMethod(method);conn.setConnectTimeout(15000);conn.setReadTimeout(timeoutSeconds*1000);
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("User-Agent","Novel Memory Android");
        if("openrouter".equals(provider))conn.setRequestProperty("X-Title","Novel Memory");
        if(apiKey!=null&&!apiKey.isEmpty())conn.setRequestProperty("Authorization","Bearer "+apiKey);
        try {
            if(body!=null){
                conn.setDoOutput(true);
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            }
            int status=conn.getResponseCode();InputStream stream=status>=400?conn.getErrorStream():conn.getInputStream();
            String response="";if(stream!=null)try(InputStream input=stream;ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1)buffer.write(part,0,count);
                response=new String(buffer.toByteArray(),StandardCharsets.UTF_8);
            }
            return new HttpResult(status,response,url);
        } finally {conn.disconnect();}
    }
    private static String classify(int status,String body){
        String text=(body==null?"":body).toLowerCase();
        if(status==401||status==403)return "auth";
        if(status==402)return "quota";
        if(status==429)return "rate_limit";
        if(status==404)return text.contains("model")?"model_not_found":"not_found";
        if(status==400){
            if(text.contains("model not found")||text.contains("model_not_found")||text.contains("invalid model")||
                    text.contains("unknown model")||text.contains("does not exist")||text.contains("no such model"))return "model_not_found";
            return "bad_request";
        }
        if(status>=500)return "server";
        if(status>=400)return "bad_request";
        return "unknown";
    }
    static String errorLabel(String kind){
        switch(kind==null?"":kind){
            case "auth":return "认证失败";
            case "model_not_found":return "模型不存在或不可用";
            case "not_found":return "接口不存在";
            case "quota":return "额度不足";
            case "rate_limit":return "请求限流";
            case "bad_request":return "请求参数错误";
            case "server":return "服务端错误";
            case "timeout":return "请求超时";
            case "connection":return "无法连接";
            case "invalid_response":return "响应格式错误";
            case "missing_key":return "缺少 API Key";
            default:return "未知错误";
        }
    }
    private static String redact(String text,String key){
        String value=text==null?"":text;
        if(key!=null&&key.length()>=8)value=value.replace(key,"[已隐藏密钥]");
        value=value.replaceAll("sk-[A-Za-z0-9_\\-]{6,}","sk-[已隐藏]");
        value=value.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]{6,}","$1[已隐藏]");
        return value;
    }
    private static String shorten(String text,int limit){String value=text==null?"":text;return value.length()>limit?value.substring(0,limit):value;}
    private static JSONObject baseInfo(JSONObject cfg) throws Exception {
        return new JSONObject().put("provider",cfg.getString("provider")).put("provider_label",providerLabel(cfg.getString("provider")))
                .put("base_url",cfg.getString("base_url")).put("model",cfg.optString("model",""))
                .put("key_source",cfg.optString("key_source","missing"));
    }
    private static String lines(JSONObject info){
        String source=info.optString("key_source","missing");
        return "渠道："+info.optString("provider_label")+"（"+info.optString("provider")+"）\n"
                +"地址："+info.optString("request_url",info.optString("base_url","未配置"))+"\n"
                +"模型："+(info.optString("model","").isEmpty()?"未填写":info.optString("model"))+"\n"
                +"密钥："+("saved".equals(source)?"已保存":("input".equals(source)?"本次输入":"未配置"));
    }
    private JSONObject failure(JSONObject cfg,int status,String kind,String detail,String requestUrl,String key){
        try{
            JSONObject info=baseInfo(cfg).put("ok",false).put("status",status).put("http_status",status)
                    .put("error_kind",kind).put("error_label",errorLabel(kind))
                    .put("request_url",requestUrl==null?cfg.optString("base_url"):requestUrl)
                    .put("detail",shorten(redact(detail,key),800)).put("error_text",shorten(redact(detail,key),800))
                    .put("models",new JSONArray());
            info.put("summary",lines(info)+"\nHTTP："+(status>0?status:"无响应")+"\n错误类型："+errorLabel(kind)
                    +"\n详情："+info.optString("detail"));
            return info;
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static JSONArray ids(JSONObject payload){
        JSONArray result=new JSONArray();JSONArray data=payload.optJSONArray("data");
        if(data!=null)for(int i=0;i<data.length();i++){
            Object item=data.opt(i);
            if(item instanceof JSONObject){String id=((JSONObject)item).optString("id",((JSONObject)item).optString("name",""));if(!id.isEmpty())result.put(id);}
            else if(item!=null)result.put(String.valueOf(item));
        }
        if(result.length()==0){JSONArray models=payload.optJSONArray("models");if(models!=null)for(int i=0;i<models.length();i++){
            Object item=models.opt(i);
            if(item instanceof JSONObject){String id=((JSONObject)item).optString("id",((JSONObject)item).optString("name",""));if(!id.isEmpty())result.put(id);}
            else if(item!=null)result.put(String.valueOf(item));
        }}
        return result;
    }
    private JSONObject listModels(JSONObject cfg) throws Exception {
        String provider=cfg.getString("provider");String apiKey=cfg.getString("api_key");
        JSONObject info=baseInfo(cfg).put("ok",false).put("request_url",cfg.getString("base_url")+"/models");
        if(keyRequired(provider)&&apiKey.isEmpty())
            return failure(cfg,0,"missing_key",providerLabel(provider)+" 尚未配置 API Key",cfg.getString("base_url")+"/models",apiKey);
        HttpResult result=request(provider,"GET",cfg.getString("base_url")+"/models",apiKey,null,20);
        if(result.status>=400)return failure(cfg,result.status,classify(result.status,result.body),result.body,result.url,apiKey);
        List<String> models=new ArrayList<>();JSONArray parsed=ids(new JSONObject(result.body));
        for(int i=0;i<parsed.length();i++)models.add(parsed.optString(i));
        JSONArray array=new JSONArray();for(String id:models)array.put(id);
        info.put("ok",true).put("status",200).put("http_status",200).put("models",array).put("model_count",models.size());
        info.put("summary",lines(info)+"\nHTTP：200\n模型列表："+models.size()+" 个");
        return info;
    }

    synchronized JSONObject models(String provider,String url,String model,String inputKey,boolean clearKey) throws Exception {
        try{return listModels(resolve(provider,url,model,inputKey,clearKey));}
        catch(Exception ex){return failure(resolve(provider,url,model,inputKey,clearKey),0,"connection",ex.getMessage(),null,"");}
    }

    synchronized JSONObject test(String provider,String url,String model,String inputKey,boolean clearKey) throws Exception {
        JSONObject cfg=resolve(provider,url,model,inputKey,clearKey);
        String key=cfg.getString("api_key");
        JSONObject listed;
        try{listed=listModels(cfg);}catch(Exception ex){return failure(cfg,0,"connection",ex.getMessage(),null,key);}
        if(!listed.optBoolean("ok")){
            if(!"glm".equals(cfg.getString("provider")))return listed;
            listed=baseInfo(cfg).put("ok",true).put("models",new JSONArray()).put("model_count",0)
                    .put("warning","GLM 模型列表不可用，改用已填写的模型名测试对话。");
        }
        String chosen=cfg.getString("model");
        if(chosen.isEmpty()){
            JSONObject failed=failure(cfg,0,"model_not_found","请先填写或选择一个模型名称",cfg.getString("base_url")+"/chat/completions",key);
            failed.put("models",listed.optJSONArray("models"));failed.put("model_count",listed.optInt("model_count"));
            failed.put("summary",lines(failed)+"\nHTTP：未发起\n错误类型：未填写模型\n详情：模型名称为空。");
            return failed;
        }
        JSONArray models=listed.optJSONArray("models");boolean inList=false;
        for(int i=0;models!=null&&i<models.length();i++)if(chosen.equals(models.optString(i))){inList=true;break;}
        String warning=models!=null&&models.length()>0&&!inList?"当前模型名不在返回的模型列表中（可能是别名或自定义名），已继续发送测试消息。":"";
        try{
            JSONObject payload=new JSONObject().put("model",chosen)
                    .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","只回复：连接成功")))
                    .put("max_tokens",32).put("temperature",0).put("stream",false);
            String chatUrl=cfg.getString("base_url")+"/chat/completions";
            HttpResult result=request(cfg.getString("provider"),"POST",chatUrl,key,payload,45);
            if(result.status>=400){
                JSONObject failed=failure(cfg,result.status,classify(result.status,result.body),result.body,result.url,key);
                failed.put("models",models);failed.put("model_count",listed.optInt("model_count"));
                failed.put("summary",lines(failed)+"\nHTTP："+result.status+"\n错误类型："+failed.optString("error_label")
                        +"\n模型列表："+listed.optInt("model_count")+" 个\n详情："+failed.optString("detail")+(warning.isEmpty()?"":"\n提示："+warning));
                return failed;
            }
            JSONObject parsed=new JSONObject(result.body);JSONArray choices=parsed.optJSONArray("choices");
            if(choices==null||choices.length()==0)return failure(cfg,200,"invalid_response","模型没有返回回答",result.url,key);
            JSONObject first=choices.optJSONObject(0);JSONObject message=first==null?null:first.optJSONObject("message");
            String content=message==null?"":message.optString("content","").trim();
            if(content.isEmpty()){
                JSONObject failed=failure(cfg,200,"invalid_response","模型返回了空内容",result.url,key);
                failed.put("models",models);failed.put("model_count",listed.optInt("model_count"));
                failed.put("summary",lines(failed)+"\nHTTP：200\n错误类型：响应格式错误\n详情：模型返回了空内容");
                return failed;
            }
            JSONObject ok=baseInfo(cfg).put("ok",true).put("status",200).put("http_status",200)
                    .put("request_url",chatUrl).put("models",models).put("model_count",listed.optInt("model_count"))
                    .put("answer",shorten(content,200));
            ok.put("summary",lines(ok)+"\nHTTP：200\n模型列表："+listed.optInt("model_count")+" 个\n测试回复："+shorten(content,200)
                    +(warning.isEmpty()?"":"\n提示："+warning));
            if(!warning.isEmpty())ok.put("warning",warning);
            return ok;
        }catch(Exception ex){
            JSONObject failed=failure(cfg,0,"connection",ex.getMessage(),cfg.getString("base_url")+"/chat/completions",key);
            failed.put("models",models);failed.put("model_count",listed.optInt("model_count"));
            failed.put("summary",lines(failed)+"\nHTTP：无响应\n错误类型：无法连接\n详情："+failed.optString("detail"));
            return failed;
        }
    }

    String chat(JSONArray messages,int maxTokens,int timeoutSeconds) throws Exception {return chat(messages,maxTokens,timeoutSeconds,null);}

    String chat(JSONArray messages,int maxTokens,int timeoutSeconds,Double temperature) throws Exception {
        ChatResult answer=chatDetailed(messages,maxTokens,timeoutSeconds,temperature);
        if(answer.truncated)throw new IllegalStateException("模型输出被截断（本次上限 "+maxTokens+" Token；模型或服务商可能有更低限制）；当前结果不保存");
        return answer.content;
    }

    static final class ChatResult {
        final String content;final boolean truncated;final int inputTokens;final int outputTokens;
        ChatResult(String content,boolean truncated,int inputTokens,int outputTokens){this.content=content;this.truncated=truncated;this.inputTokens=inputTokens;this.outputTokens=outputTokens;}
    }

    ChatResult chatDetailed(JSONArray messages,int maxTokens,int timeoutSeconds,Double temperature) throws Exception {
        String provider=selected();JSONObject stored=config(provider);
        JSONObject cfg=resolve(provider,stored.getString("base_url"),stored.getString("model"),"",false);
        String base=cfg.getString("base_url");
        String url=base.endsWith("/chat/completions")?base:base+"/chat/completions";
        String model=cfg.getString("model");if(model.isEmpty())throw new IllegalArgumentException("请先在设置中填写模型名称");
        JSONObject payload=new JSONObject().put("model",model).put("messages",messages).put("stream",false).put("max_tokens",maxTokens);
        if(temperature!=null)payload.put("temperature",Math.max(0,Math.min(2,temperature)));
        String key=cfg.getString("api_key");
        HttpResult result;
        try{result=request(provider,"POST",url,key,payload,timeoutSeconds);}
        catch(Exception ex){
            throw new IOExceptionWithStatus(0,"connection",redact(ex.getMessage(),key));
        }
        if(result.status>=400){
            String kind=classify(result.status,result.body);
            throw new IOExceptionWithStatus(result.status,kind,
                    "API 返回 HTTP "+result.status+"（"+errorLabel(kind)+"）："+shorten(redact(result.body,key),500),result.body);
        }
        try{
            JSONObject parsed=new JSONObject(result.body);JSONArray choices=parsed.optJSONArray("choices");
            if(choices==null||choices.length()==0)throw new IllegalStateException("模型没有返回回答");
            JSONObject first=choices.getJSONObject(0);String reason=first.optString("finish_reason","");
            boolean truncated="length".equals(reason)||"max_tokens".equals(reason)||"output_limit".equals(reason);
            String content=first.optJSONObject("message")!=null?first.getJSONObject("message").optString("content","").trim():"";
            if(content.isEmpty())throw new IllegalStateException("模型正式回答为空，请调整模型输出额度");
            JSONObject usage=parsed.optJSONObject("usage");
            return new ChatResult(content,truncated,usage==null?0:usage.optInt("prompt_tokens",usage.optInt("input_tokens",0)),usage==null?0:usage.optInt("completion_tokens",usage.optInt("output_tokens",0)));
        }catch(IllegalStateException ex){throw ex;}
        catch(Exception ex){throw new IllegalStateException("API 响应格式无法解析："+shorten(redact(result.body,key),400));}
    }

    static final class IOExceptionWithStatus extends Exception {
        final int status;final String kind;final String body;
        IOExceptionWithStatus(int status,String kind,String message){this(status,kind,message,"");}
        IOExceptionWithStatus(int status,String kind,String message,String body){super(message);this.status=status;this.kind=kind;this.body=body==null?"":body;}
    }
}
