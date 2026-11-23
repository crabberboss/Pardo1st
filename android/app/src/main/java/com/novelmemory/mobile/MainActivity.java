package com.novelmemory.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final String STATUS_CHANNEL="pardo_work_status";private static final int STATUS_ID=101;
    private static final int IMPORT_FILE=11,EXPORT_KB=12,EXPORT_REWRITES=13,IMPORT_CARD=14,EXPORT_CARD=15,IMPORT_TEMPLATE=16,EXPORT_TEMPLATE=17,PICK_BACKGROUND=18;
    private WebView web;private Store store;private Api api;private ChatData chatData;private ChatEngine chatEngine;private RewriteTemplate rewriteTemplate;
    private final ExecutorService io=Executors.newCachedThreadPool();
    private final AtomicBoolean cancelJob=new AtomicBoolean(false);
    private final AtomicInteger activeChatRequests=new AtomicInteger(0);
    private volatile boolean jobRunning=false;
    private volatile String jobDetail="";
    private long pendingExportArchive=0;
    private long pendingImportArchive=0;
    private long pendingCardArchive=0,pendingCardId=0;
    private long pendingTemplateArchive=0;
    private ValueCallback<Uri[]> backgroundFileCallback;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new Store(this);api=new Api(this);chatData=new ChatData(store);chatEngine=new ChatEngine(this,store,chatData,api);rewriteTemplate=new RewriteTemplate(this);
        if("running".equals(getPreferences(MODE_PRIVATE).getString("last_job_status","")))getPreferences(MODE_PRIVATE).edit().putString("last_job_status","interrupted").putString("last_job_detail","上次任务已中断；可继续处理未完成章节").apply();
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);web.getSettings().setAllowContentAccess(true);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,WebChromeClient.FileChooserParams params){
                if(backgroundFileCallback!=null)backgroundFileCallback.onReceiveValue(null);
                backgroundFileCallback=callback;
                try{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("image/*");startActivityForResult(pick,PICK_BACKGROUND);}
                catch(Exception ex){backgroundFileCallback=null;callback.onReceiveValue(null);error(ex);}
                return true;
            }
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){
                new AlertDialog.Builder(MainActivity.this).setTitle("确认操作").setMessage(message)
                        .setPositiveButton("确定",(dialog,which)->result.confirm())
                        .setNegativeButton("取消",(dialog,which)->result.cancel())
                        .setOnCancelListener(dialog->result.cancel()).show();
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(),"Native");web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !url.startsWith("file:///android_asset/");}
        });setContentView(web);web.loadUrl("file:///android_asset/index.html");
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(STATUS_CHANNEL,"当前工作状态",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},STATUS_ID);
        else updateStatusNotification();
    }
    @Override public void onDestroy(){cancelJob.set(true);io.shutdownNow();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(STATUS_ID);web.destroy();store.close();super.onDestroy();}

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==STATUS_ID&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)updateStatusNotification();}
    private void updateStatusNotification(){runOnUiThread(()->{
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        String detail=jobRunning?(jobDetail.isEmpty()?"正在处理章节":jobDetail):activeChatRequests.get()>0?"正在调用模型对话":"已打开 · 当前空闲";
        Intent open=new Intent(this,MainActivity.class);open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,STATUS_CHANNEL).setSmallIcon(R.drawable.pardo_icon).setContentTitle("帕朵一号 · 工作状态")
            .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(pending).setOnlyAlertOnce(true).setOngoing(true).build();
        notification.flags|=Notification.FLAG_NO_CLEAR|Notification.FLAG_ONGOING_EVENT;
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(STATUS_ID,notification);}catch(SecurityException ignored){}
    });}

    private void emit(String type,Object value){
        if("progress".equals(type))updateStatusNotification();
        String data=value instanceof JSONObject||value instanceof JSONArray?value.toString():JSONObject.quote(String.valueOf(value));
        runOnUiThread(()->web.evaluateJavascript("window.nativeEvent("+JSONObject.quote(type)+","+data+")",null));
    }
    private void error(Exception ex){emit("error",ex.getMessage()==null?ex.toString():ex.getMessage());}
    private void async(Throwing task){io.execute(()->{try{task.run();}catch(Exception ex){error(ex);}});}
    interface Throwing{void run() throws Exception;}

    final class Bridge {
        @JavascriptInterface public void request(String operation,String raw){
            JSONObject p;try{p=new JSONObject(raw);}catch(Exception ex){error(ex);return;}
            switch(operation){
                case "pickFile":pendingImportArchive=p.optLong("archive_id",0);runOnUiThread(()->pickFile());return;
                case "setReaderBrightness":runOnUiThread(()->{
                    android.view.WindowManager.LayoutParams attributes=getWindow().getAttributes();
                    double requested=p.optDouble("value",-1);
                    attributes.screenBrightness=requested<0?-1f:(float)Math.max(.1,Math.min(1,requested));
                    getWindow().setAttributes(attributes);
                });return;
                case "exportKb":pendingExportArchive=p.optLong("archive_id");runOnUiThread(()->exportPicker(exportName(p.optString("name","knowledge-base"),false)));return;
                case "exportRewrites":pendingExportArchive=p.optLong("archive_id");runOnUiThread(()->rewriteExportPicker(exportName(p.optString("name","rewrites"),true)));return;
                case "cancelJob":cancelJob.set(true);return;
                case "resumeJob":async(()->resumeLastJob());return;
                case "pickCard":pendingCardArchive=p.optLong("archive_id",0);runOnUiThread(()->pickCard());return;
                case "exportCard":pendingCardArchive=p.optLong("archive_id",0);pendingCardId=p.optLong("profile_id",0);runOnUiThread(()->cardExportPicker(p.optString("name","角色卡")+".json"));return;
                case "pickTemplate":pendingTemplateArchive=p.optLong("archive_id",0);runOnUiThread(()->templateImportPicker());return;
                case "exportTemplate":pendingTemplateArchive=p.optLong("archive_id",0);runOnUiThread(()->templateExportPicker(p.optString("name","改写模板")+".json"));return;
                default:async(()->handle(operation,p));
            }
        }
    }

    private void handle(String op,JSONObject p) throws Exception {
        long archiveId=p.optLong("archive_id",0);
        switch(op){
            case "init":emit("init",new JSONObject().put("archives",store.archives()).put("selected_provider",api.selected()));emitLastJob();break;
            case "archives":emit("archives",store.archives());break;
            case "create":store.createArchive(p.optString("name",""));emit("archives",store.archives());break;
            case "deleteArchive":{
                synchronized(this) {
                    if(jobRunning||activeChatRequests.get()>0)throw new IllegalStateException("请先等待当前生成或对话请求结束，再删除知识库");
                    store.deleteArchive(archiveId);
                    rewriteTemplate.delete(archiveId);
                }
                emit("archiveDeleted",new JSONObject().put("deleted_id",archiveId).put("archives",store.archives()));break;
            }
            case "archive":emit("archive",store.archive(archiveId));break;
            case "diagnostics":emit("diagnostics",store.diagnostics(archiveId));break;
            case "chapters":emit("chapters",new JSONObject().put("archive_id",archiveId).put("rows",store.chapters(archiveId,p.optInt("offset",0),p.optInt("limit",40),"")).put("offset",p.optInt("offset",0)).put("total",store.chapterCount(archiveId)));break;
            case "chapter":emit("chapter",store.chapterWithSpecialHits(p.getLong("chapter_id")));break;
            case "saveChapterPrompt":store.saveChapterPrompt(archiveId,p.getLong("chapter_id"),p.optString("prompt",""),p.optString("extra_instruction",""));emit("chapterPromptSaved",new JSONObject().put("archive_id",archiveId).put("chapter_id",p.getLong("chapter_id")));break;
            case "specialTagState":emit("specialTagState",store.specialTagState(archiveId));break;
            case "specialExpandSettingsGet":emit("specialExpandSettings",new JSONObject().put("prompt",getPreferences(MODE_PRIVATE).getString("special_expand_prompt","你是小说场景标签规则编辑器。依据用户给出的标签名称和简短识别要求，扩写为详细、可执行的识别提示词。明确命中条件、排除条件和边界案例；不凭人物设定猜测。只输出扩写后的规则正文，不重复用户原文。")));break;
            case "rewriteExpandSettingsGet":emit("rewriteExpandSettings",new JSONObject().put("prompt",getPreferences(MODE_PRIVATE).getString("rewrite_expand_prompt","你是小说章节改写提示词编辑器。依据章节名称、现有章节提示词和额外要求，扩写为具体可执行的改写规则。保留原著事实、人物关系与剧情连续性。只输出新增规则正文，不重复用户输入，不直接改写小说。")));break;
            case "rewriteExpandSettingsSave":{String rule=p.optString("prompt","").trim();if(rule.isEmpty())throw new IllegalArgumentException("改写扩写系统提示词不能为空");getPreferences(MODE_PRIVATE).edit().putString("rewrite_expand_prompt",rule).apply();emit("rewriteExpandSettings",new JSONObject().put("prompt",rule));break;}
            case "expandRewritePrompt":{
                String title=p.optString("title","").trim(),original=p.optString("original","").trim(),extra=p.optString("extra","").trim();if(title.isEmpty())throw new IllegalArgumentException("请先选择章节");
                String system=getPreferences(MODE_PRIVATE).getString("rewrite_expand_prompt","依据章节名称和已有要求扩写具体可执行的章节改写提示词；只输出新增规则正文，不直接改写小说。");
                JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(new JSONObject().put("role","user").put("content","章节："+title+"\n已有章节提示词："+(original.isEmpty()?"无":original)+"\n本章额外要求："+(extra.isEmpty()?"无":extra)+"\n请输出新增的具体改写规则。"));
                String expanded;try{expanded=api.chat(messages,2400,120).trim();}catch(IllegalStateException ex){if(!ex.getMessage().contains("截断"))throw ex;expanded=api.chat(messages,3600,120).trim();}if(expanded.isEmpty())throw new IllegalStateException("模型未返回改写提示词");
                emit("rewritePromptExpanded",new JSONObject().put("archive_id",archiveId).put("chapter_id",p.optLong("chapter_id")).put("original",original).put("extra",extra).put("expanded",expanded));break;
            }
            case "specialExpandSettingsSave":{String rule=p.optString("prompt","").trim();if(rule.isEmpty())throw new IllegalArgumentException("扩写系统提示词不能为空");getPreferences(MODE_PRIVATE).edit().putString("special_expand_prompt",rule).apply();emit("specialExpandSettings",new JSONObject().put("prompt",rule));break;}
            case "expandSpecialTagPrompt":{
                String name=p.optString("name","").trim(),original=p.optString("original","").trim();if(name.isEmpty())throw new IllegalArgumentException("请先填写标签名称");
                String system=getPreferences(MODE_PRIVATE).getString("special_expand_prompt","你是小说场景标签规则编辑器。依据标签名称和现有规则扩写详细、可执行的识别提示词；写明命中条件、排除条件和边界案例。只输出扩写后的规则正文，不重复用户原文。");
                JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(new JSONObject().put("role","user").put("content","标签名称："+name+"\n现有识别提示词："+(original.isEmpty()?"无；请根据标签名称拟定规则":original)+"\n请只输出不超过500字的新增识别规则，不要思考过程或重复原文。"));
                String expanded;try{expanded=api.chat(messages,2400,120).trim();}catch(IllegalStateException ex){if(!ex.getMessage().contains("截断"))throw ex;expanded=api.chat(messages,3600,120).trim();}if(expanded.isEmpty())throw new IllegalStateException("模型未返回识别提示词");
                emit("specialTagExpanded",new JSONObject().put("archive_id",archiveId).put("name",name).put("original",original).put("expanded",expanded));break;
            }
            case "suggestSpecialTags":{
                String sample=store.specialTagSample(archiveId);
                if(sample.isEmpty())throw new IllegalArgumentException("请先生成至少一章章节总结");
                JSONArray current=store.specialTagState(archiveId).getJSONArray("tags");
                StringBuilder names=new StringBuilder();for(int i=0;i<current.length();i++)names.append(current.getJSONObject(i).optString("name")).append('、');
                JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","你是小说场景标签设计助手。只根据输入的章节摘要，提出最多5个可复用的特殊场景标签，不要输出人物姓名或普通剧情关键词。只返回JSON对象：{\"tags\":[{\"name\":\"短标签名\",\"recognition_prompt\":\"严格可操作的命中条件\",\"color\":\"#a45e4f\"}]}。不要臆造场景。"))
                    .put(new JSONObject().put("role","user").put("content","已有标签："+names+"\n章节摘要样本：\n"+sample));
                String raw=api.chat(messages,900,120);int begin=raw.indexOf('{'),end=raw.lastIndexOf('}');
                if(begin<0||end<=begin)throw new IllegalStateException("模型没有返回标签 JSON");
                JSONArray proposed=new JSONObject(raw.substring(begin,end+1)).getJSONArray("tags"),clean=new JSONArray();
                java.util.HashSet<String> seen=new java.util.HashSet<>();for(int i=0;i<current.length();i++)seen.add(current.getJSONObject(i).optString("name"));
                for(int i=0;i<Math.min(5,proposed.length());i++){JSONObject tag=proposed.optJSONObject(i);if(tag==null)continue;String name=tag.optString("name").trim(),rule=tag.optString("recognition_prompt").trim(),color=tag.optString("color");if(name.isEmpty()||rule.isEmpty()||!seen.add(name))continue;if(!color.matches("#[0-9a-fA-F]{6}"))color="#a45e4f";clean.put(new JSONObject().put("name",name.substring(0,Math.min(40,name.length()))).put("recognition_prompt",rule.substring(0,Math.min(500,rule.length()))).put("color",color));}
                emit("specialTagSuggestions",new JSONObject().put("archive_id",archiveId).put("tags",clean));break;
            }
            case "saveSpecialTag":emit("specialTagState",store.saveSpecialTag(archiveId,p.optLong("id",0),p.optString("name",""),p.optString("color","#a45e4f"),p.optString("recognition_prompt","")));break;
            case "deleteSpecialTag":emit("specialTagState",store.deleteSpecialTag(archiveId,p.getLong("id")));break;
            case "setSpecialHit":emit("specialTagState",store.setSpecialHit(archiveId,p.getLong("chapter_id"),p.getLong("tag_id"),p.optString("description",""),p.optString("evidence",""),p.optBoolean("remove",false)));emit("specialHitSaved",store.chapterWithSpecialHits(p.getLong("chapter_id")));break;
            case "sourceSlice":emit("sourceSlice",store.sourceSlice(p.getLong("chapter_id"),p.optInt("offset",0),p.optInt("limit",12000)));break;
            case "readerSlice":emit("readerSlice",(p.optBoolean("rewritten",false)?store.readerRewriteSlice(p.getLong("chapter_id"),p.optInt("offset",0),p.optInt("limit",6000)):store.sourceSlice(p.getLong("chapter_id"),p.optInt("offset",0),p.optInt("limit",6000))).put("rewritten",p.optBoolean("rewritten",false)));break;
            case "readerCatalog":emit("readerCatalog",store.readerCatalog(p.getLong("archive_id")));break;
            case "readerPrefetch":emit("readerPrefetch",(p.optBoolean("rewritten",false)?store.readerRewriteSlice(p.getLong("chapter_id"),p.optInt("offset",0),p.optInt("limit",6000)):store.sourceSlice(p.getLong("chapter_id"),p.optInt("offset",0),p.optInt("limit",6000))).put("rewritten",p.optBoolean("rewritten",false)));break;
            case "readerBrowse":emit("readerBrowse",store.rewriteBrowse(p.getLong("archive_id"),"",p.optInt("offset",0),40));break;
            case "rewriteSourceSlice":emit("rewriteSourceSlice",store.sourceSlice(p.getLong("chapter_id"),p.optInt("offset",0),p.optInt("limit",12000)));break;
            case "rewriteBrowse":emit("rewriteBrowse",store.rewriteBrowse(p.getLong("archive_id"),p.optString("keyword",""),p.optInt("offset",0),40));break;
            case "saveChapterSource":{
                JSONObject result;
                synchronized(this){
                    if(jobRunning||activeChatRequests.get()>0)throw new IllegalStateException("请等待当前生成或对话请求结束后再修改章节原文");
                    result=store.saveSourceSlice(archiveId,p.getLong("chapter_id"),p.getInt("offset"),p.getString("expected"),p.getString("replacement"));
                    if(result.optBoolean("changed",false))store.rebuildOverview(archiveId);
                }
                emit("sourceSaved",result.put("archive_id",archiveId));break;
            }
            case "deleteChapter":{
                synchronized(this){
                    if(jobRunning||activeChatRequests.get()>0)throw new IllegalStateException("请等待当前生成或对话请求结束后再删除章节");
                    store.deleteChapter(archiveId,p.getLong("chapter_id"));
                    store.rebuildOverview(archiveId);
                }
                emit("chapterDeleted",new JSONObject().put("archive_id",archiveId).put("chapter_id",p.getLong("chapter_id"))
                        .put("total",store.chapterCount(archiveId)).put("archives",store.archives()));break;
            }
            case "rewritePrompt":emit("rewritePrompt",new JSONObject().put("archive_id",archiveId)
                    .put("value",store.rewriteGlobalPrompt(archiveId,p.optString("legacy_prompt","")))
                    .put("request_id",p.optInt("request_id",0)));break;
            case "saveRewritePrompt":store.saveRewriteGlobalPrompt(archiveId,p.optString("value",""));emit("rewritePromptSaved",new JSONObject().put("archive_id",archiveId));break;
            case "previewSplit":emit("splitPreview",store.previewSplit(archiveId,p.optString("expression","")));break;
            case "applySplit":{
                if(jobRunning)throw new IllegalStateException("请先等待当前任务结束，再重新切分章节");
                int count=store.applySplit(archiveId,p.optString("expression",""));
                emit("splitDone",new JSONObject().put("archive_id",archiveId).put("count",count).put("archives",store.archives()));break;
            }
            case "saveKbDownloads":saveToDownloads(archiveId,exportName(p.optString("name","knowledge-base"),false),false);break;
            case "saveRewritesDownloads":saveToDownloads(archiveId,exportName(p.optString("name","rewrites"),true),true);break;
            case "config":emit("config",api.config(p.optString("provider",api.selected())));break;
            case "saveConfig":api.save(p.getString("provider"),p.getString("base_url"),p.getString("model"),p.optString("key",""),p.optBoolean("clear_key",false));api.select(p.getString("provider"));emit("configSaved",api.config(api.selected()));break;
            case "models":emit("apiModels",api.models(p.optString("provider",api.selected()),p.optString("base_url",""),p.optString("model",""),p.optString("key",""),p.optBoolean("clear_key",false)));break;
            case "summaryTokenGet":emit("summaryTokenSetting",new JSONObject().put("value",getPreferences(MODE_PRIVATE).getInt("summary_max_tokens",4096)).put("input_chars",getPreferences(MODE_PRIVATE).getInt("summary_input_chars",6000)));break;
            case "summaryTokenSave":{int limit=p.optInt("value",4096),input=p.optInt("input_chars",6000);if(limit<512||limit>32768)throw new IllegalArgumentException("章节总结 Token 上限须为 512–32768");if(input<2000||input>12000)throw new IllegalArgumentException("章节输入原文预算须为 2000–12000 字");getPreferences(MODE_PRIVATE).edit().putInt("summary_max_tokens",limit).putInt("summary_input_chars",input).apply();emit("summaryTokenSetting",new JSONObject().put("value",limit).put("input_chars",input));emit("notice","记忆生成预算已保存，下一次生成起生效");break;}
            case "rewriteTokenGet":emit("rewriteTokenSetting",new JSONObject().put("value",getPreferences(MODE_PRIVATE).getInt("rewrite_max_tokens",8192)));break;
            case "rewriteTokenSave":{int limit=p.optInt("value",8192);if(limit<1024||limit>32768)throw new IllegalArgumentException("整章改写输出 Token 上限须为 1024–32768");getPreferences(MODE_PRIVATE).edit().putInt("rewrite_max_tokens",limit).apply();emit("rewriteTokenSetting",new JSONObject().put("value",limit));emit("notice","改写输出额度已保存，下一次改写起生效");break;}
            case "rewriteFlowGet":emit("rewriteFlowSetting",new JSONObject().put("continuations",getPreferences(MODE_PRIVATE).getInt("rewrite_continuations",1)).put("budget",getPreferences(MODE_PRIVATE).getInt("rewrite_budget",0)));break;
            case "rewriteFlowSave":{int calls=p.optInt("continuations",1),budget=p.optInt("budget",0);if(calls<0||calls>3||budget<0||budget>1000000)throw new IllegalArgumentException("续写次数须为 0–3，预算须为 0–1000000 Token");getPreferences(MODE_PRIVATE).edit().putInt("rewrite_continuations",calls).putInt("rewrite_budget",budget).apply();emit("rewriteFlowSetting",new JSONObject().put("continuations",calls).put("budget",budget));emit("notice","续写与预算已保存");break;}
            case "rewriteUsageGet":{long id=p.getLong("archive_id");String prefix="rewrite_usage_"+id+"_";emit("rewriteUsage",new JSONObject().put("archive_id",id).put("input",getPreferences(MODE_PRIVATE).getInt(prefix+"input",0)).put("output",getPreferences(MODE_PRIVATE).getInt(prefix+"output",0)));break;}
            case "rewritePartial":emit("rewritePartial",new JSONObject().put("chapter_id",p.getLong("chapter_id")).put("content",store.rewritePartial(p.getLong("chapter_id"))));break;
            case "testApi":{
                emit("apiStatus","测试中");
                emit("apiStatus",api.test(p.optString("provider",api.selected()),p.optString("base_url",""),p.optString("model",""),p.optString("key",""),p.optBoolean("clear_key",false)));
                break;
            }
            case "messages":emit("messages",store.messages(archiveId));break;
            case "clearMessages":store.clearMessages(archiveId);emit("messages",store.messages(archiveId));break;
            case "chat":chat(archiveId,p.getString("question"));break;
            case "chatOpen":emitChatState(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.optLong("session_id",0));break;
            case "chatGlobals":emit("chatGlobals",chatEngine.globals());break;
            case "chatCreateSession":{
                JSONObject s=chatData.createSession(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.optString("name","新对话"));
                emitChatState(archiveId,s.getString("mode"),s.getLong("profile_id"),s.getLong("id"));break;
            }
            case "chatRenameSession":chatData.ensureSession(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));chatData.renameSession(p.getLong("session_id"),p.getString("name"));emitChatState(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));break;
            case "chatDeleteSession":if(activeChatRequests.get()>0)throw new IllegalStateException("请等待当前对话请求结束");chatData.ensureSession(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));chatData.deleteSession(p.getLong("session_id"));emitChatState(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),0);break;
            case "chatClearSession":if(activeChatRequests.get()>0)throw new IllegalStateException("请等待当前对话请求结束");chatData.ensureSession(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));chatData.clearSession(p.getLong("session_id"));emitChatState(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));break;
            case "chatSavePreset":chatData.savePreset(archiveId,p.getJSONObject("preset"));emitChatState(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.optLong("session_id",0));break;
            case "chatSaveGlobals":emit("chatGlobals",chatEngine.saveGlobals(p.getJSONObject("globals")));break;
            case "chatSaveProfile":{
                JSONObject card=chatData.saveProfile(archiveId,p.getJSONObject("profile"));
                emitChatState(archiveId,"roleplay",card.getLong("id"),0);break;
            }
            case "chatGenerateProfile":{
                activeChatRequests.incrementAndGet();updateStatusNotification();try {
                    JSONObject card=chatEngine.generateProfile(archiveId,p.optString("character",""),p.optString("request",""),p.optString("timeline",""),p.optInt("cutoff",0));
                    emitChatState(archiveId,"roleplay",card.getLong("id"),0);
                }finally{activeChatRequests.decrementAndGet();updateStatusNotification();}break;
            }
            case "chatDeleteProfile":if(activeChatRequests.get()>0)throw new IllegalStateException("请等待当前对话请求结束");chatData.deleteProfile(archiveId,p.getLong("profile_id"));emitChatState(archiveId,"qa",0,0);break;
            case "chatEditMessage":chatData.ensureSession(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));chatData.editMessage(p.getLong("session_id"),p.getLong("message_id"),p.getString("content"));emitChatState(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.getLong("session_id"));break;
            case "chatSend":{
                activeChatRequests.incrementAndGet();updateStatusNotification();try {
                    JSONObject result=chatEngine.respond(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.optLong("session_id",0),p.optString("text",""),p.optLong("assistant_id",0));
                    emitChatState(archiveId,result.getJSONObject("session").getString("mode"),result.getJSONObject("session").getLong("profile_id"),result.getJSONObject("session").getLong("id"));
                }finally{activeChatRequests.decrementAndGet();updateStatusNotification();}break;
            }
            case "saveRewrite":store.setRewrite(p.getLong("chapter_id"),p.getString("content"),p.optString("chapter_prompt",""),p.optString("extra_instruction",""));emit("chapter",store.chapterWithSpecialHits(p.getLong("chapter_id")));emit("notice","本章改写稿已保存");break;
            case "templateGet":store.archive(archiveId);emit("rewriteTemplate",new JSONObject().put("archive_id",archiveId).put("template",rewriteTemplate.load(archiveId)).put("apply_prompt",false));break;
            case "templateSave":{
                store.archive(archiveId);JSONObject template=rewriteTemplate.save(archiveId,p.getJSONObject("template"));
                store.saveRewriteGlobalPrompt(archiveId,template.getJSONObject("rewrite").optString("global_prompt",""));
                emit("rewriteTemplate",new JSONObject().put("archive_id",archiveId).put("template",template).put("apply_prompt",true));
                emit("notice","改写模板已保存到本机独立文件");break;
            }
            case "startJob":startJob(p);break;
            case "estimate":emit("estimate",new JSONObject()
                    .put("count",store.countChapters(archiveId,p.optString("keyword",""),p.optString("mode","rewrite"),p.optBoolean("missing_only",true),p.optJSONObject("template_filter")))
                    .put("chars",store.countTargetChars(archiveId,p.optString("keyword",""),p.optString("mode","rewrite"),p.optBoolean("missing_only",true),p.optJSONObject("template_filter")))
                    .put("launch",p.optBoolean("launch",false)));break;
            default:throw new IllegalArgumentException("未知操作："+op);
        }
    }

    private void emitChatState(long archiveId,String mode,long profileId,long sessionId) throws Exception {
        JSONArray profiles=chatData.profiles(archiveId);JSONObject session=null;JSONArray messages=new JSONArray();
        if(!"roleplay".equals(mode)||profileId>0){session=chatData.ensureSession(archiveId,mode,profileId,sessionId);messages=chatData.messages(session.getLong("id"));}
        emit("chatState",new JSONObject().put("archive_id",archiveId).put("mode",mode).put("profile_id",profileId)
                .put("profiles",profiles).put("sessions",chatData.sessions(archiveId,mode,profileId))
                .put("session",session==null?JSONObject.NULL:session).put("messages",messages)
                .put("preset",chatData.preset(archiveId)).put("globals",chatEngine.globals()));
    }

    private void chat(long archiveId,String question) throws Exception {
        if(question.trim().isEmpty())throw new IllegalArgumentException("请输入对话内容");
        JSONObject archive=store.archive(archiveId);
        JSONArray rows=store.chapters(archiveId,0,10000,"");
        List<JSONObject> best=new ArrayList<>();List<Integer> scores=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String searchable=row.optString("title","")+row.optString("summary","");int score=0;
            for(int j=0;j<question.length()-1;j++)if(searchable.contains(question.substring(j,j+2)))score++;
            if(score<=0)continue;int pos=0;while(pos<scores.size()&&scores.get(pos)>=score)pos++;
            if(pos<5){best.add(pos,row);scores.add(pos,score);if(best.size()>5){best.remove(5);scores.remove(5);}}
        }
        StringBuilder context=new StringBuilder(archive.optString("overview",""));
        for(JSONObject row:best){context.append("\n").append(row.getString("title")).append("：").append(row.optString("summary",""));}
        JSONArray history=store.messages(archiveId);JSONArray messages=new JSONArray();
        messages.put(new JSONObject().put("role","system").put("content","你在小说世界中回答，优先遵循下面的知识库事实和人物性格。不知道的内容要说明不确定，不要编造原文中没有的情节。\n"+context));
        for(int i=Math.max(0,history.length()-8);i<history.length();i++)messages.put(history.getJSONObject(i));
        messages.put(new JSONObject().put("role","user").put("content",question));
        String answer=api.chat(messages,1800,120);
        store.addMessage(archiveId,"user",question);store.addMessage(archiveId,"assistant",answer);
        emit("messages",store.messages(archiveId));
    }

    private synchronized void startJob(JSONObject p) throws Exception {
        if(jobRunning)throw new IllegalStateException("已有任务在运行；请先等待或暂停");
        String mode=p.optString("mode","summary");long archiveId=p.getLong("archive_id");
        String keyword=p.optString("keyword","").trim();boolean missingOnly=p.optBoolean("missing_only",true);
        List<Long> ids;
        if(p.optLong("chapter_id",0)>0){ids=new ArrayList<>();ids.add(p.getLong("chapter_id"));}
        else ids=store.targetIds(archiveId,keyword,mode,missingOnly,p.optJSONObject("template_filter"));
        if(ids.isEmpty()){emit("notice","没有符合条件的待处理章节");return;}
        jobRunning=true;cancelJob.set(false);jobDetail="准备处理章节";
        getPreferences(MODE_PRIVATE).edit().putString("last_job_payload",p.toString()).putString("last_job_status","running").putString("last_job_detail",jobDetail).apply();
        emitLastJob();updateStatusNotification();
        String globalPrompt=p.optString("global_prompt","");String rules=p.optString("template_rules","");
        JSONArray summaryTags="summary".equals(mode)?store.specialTagState(archiveId).getJSONArray("tags"):new JSONArray();
        StringBuilder tagGuide=new StringBuilder();
        if(summaryTags.length()>0){
            tagGuide.append("\n还要识别以下特殊关键词；只根据本章原文判断，不得猜测。正常五个栏目后，最后另起一行输出：")
                    .append("特殊标签JSON：{\"matches\":[{\"id\":标签数字ID,\"description\":\"1–30字的具体情节简述\",\"evidence\":\"简短原文依据\"}]}。")
                    .append("每个标签最多一次；未命中时写 {\"matches\":[]}。不要在普通关键词字段中重复列出这些特殊标签。\n");
            for(int i=0;i<summaryTags.length();i++){
                JSONObject tag=summaryTags.getJSONObject(i);
                String recognition=tag.optString("recognition_prompt","");
                if(recognition.length()>160)recognition=recognition.substring(0,160);
                tagGuide.append(tag.optLong("id")).append(". ").append(tag.optString("name"))
                        .append("：").append(recognition).append('\n');
            }
        }
        String specialGuide=tagGuide.toString();
        io.execute(()->{
            int done=0,failed=0,spent=0;emitProgress(mode,done,ids.size(),failed,"开始处理");
            try {
                for(long id:ids){
                    if(cancelJob.get())break;
                    JSONObject chapter=store.chapter(id);
                    try{
                        if("rewrite".equals(mode)){
                            String prompt=globalPrompt.isEmpty()?"依据原文章节改写，保持事件顺序和人物性格，输出完整章节正文，不要重复段落。":globalPrompt;
                            if(!rules.trim().isEmpty())prompt+="\n改写规则："+rules;
                            String chapterPrompt=p.optLong("chapter_id",0)>0?p.optString("chapter_prompt",""):p.optString("chapter_prompt","");
                            if(chapterPrompt.isEmpty())chapterPrompt=chapter.optString("chapter_prompt","");
                            String templateChapterPrompt=p.optString("template_chapter_prompt","");
                            String effectiveChapterPrompt=(templateChapterPrompt.isEmpty()?"":"模板章节要求："+templateChapterPrompt+"\n")+chapterPrompt;
                            String extra=p.optLong("chapter_id",0)>0?p.optString("extra_instruction",""):chapter.optString("extra_instruction","");
                            JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",prompt));
                            messages.put(new JSONObject().put("role","user").put("content","章节："+chapter.getString("title")+"\n剧情概览："+chapter.optString("summary","")+"\n本章提示词："+effectiveChapterPrompt+"\n本章额外要求："+extra+"\n请完整改写以下原文，只输出改写正文：\n"+store.rewriteSource(id)));
                            int rewriteLimit=p.optInt("max_tokens",getPreferences(MODE_PRIVATE).getInt("rewrite_max_tokens",8192));
                            if(rewriteLimit<1024||rewriteLimit>32768)throw new IllegalArgumentException("整章改写输出 Token 上限须为 1024–32768");
                            String source=store.rewriteSource(id);
                            StringBuilder draft=new StringBuilder(p.optBoolean("resume_partial",false)?store.rewritePartial(id):"");
                            int continuations=getPreferences(MODE_PRIVATE).getInt("rewrite_continuations",1);
                            int budget=getPreferences(MODE_PRIVATE).getInt("rewrite_budget",0);
                            boolean finished=false;
                            for(int attempt=0;attempt<=continuations;attempt++){
                                JSONArray requestMessages;
                                if(attempt==0&&draft.length()==0)requestMessages=messages;
                                else {
                                    String tail=draft.substring(Math.max(0,draft.length()-1200));
                                    requestMessages=new JSONArray().put(new JSONObject().put("role","system").put("content",prompt))
                                            .put(new JSONObject().put("role","user").put("content","章节："+chapter.getString("title")+"\n剧情概览："+chapter.optString("summary","")+"\n原文后段："+source.substring(Math.max(0,source.length()-6000))+"\n已生成正文末尾："+tail+"\n只续写尚未完成的情节，不重复已有正文，不输出说明。"));
                                }
                                int estimatedInput=requestMessages.toString().length()/2;
                                if(budget>0&&spent+estimatedInput+rewriteLimit>budget)throw new IllegalStateException("本次请求可能超过任务预算；已有临时稿仍保留。可调高预算或降低输出上限后继续");
                                Api.ChatResult answer=api.chatDetailed(requestMessages,rewriteLimit,600,null);
                                String joined=appendRewrite(draft.toString(),answer.content);draft.setLength(0);draft.append(joined);
                                store.setRewritePartial(id,draft.toString());
                                spent+=(answer.inputTokens+answer.outputTokens)>0?answer.inputTokens+answer.outputTokens:estimatedInput+answer.content.length()/2;
                                recordRewriteUsage(archiveId,answer.inputTokens,answer.outputTokens);
                                if(!answer.truncated){finished=true;break;}
                            }
                            if(!finished)throw new IllegalStateException("改写仍被截断；已保存 "+draft.length()+" 字临时稿，未覆盖正式稿。可从临时稿继续");
                            boolean repeated=hasLongRepeatedParagraph(draft.toString());
                            store.setRewrite(id,draft.toString(),chapterPrompt,extra);
                            if(draft.length()<source.length()*0.3)emit("rewriteQuality",new JSONObject().put("chapter_id",id).put("message","改写正文不足原文三成，请检查是否遗漏情节"));
                            if(repeated)emit("rewriteQuality",new JSONObject().put("chapter_id",id).put("message","检测到较长重复段落，请校对改写正文"));
                        } else {
                            JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","依据原文生成准确、精简的章节知识记忆。必须按固定字段输出：\n关键词：5–12 个词或短语，逗号分隔\n剧情概览：2–5 句概括主要剧情\n人物与关系：重要人物状态与关系变化\n地点/物品/设定：本章重要场景、物品、设定\n伏笔与未解问题：明确出现的线索和悬念\n没有证据的字段写“无”，不得编造。"+specialGuide));
                            int inputBudget=getPreferences(MODE_PRIVATE).getInt("summary_input_chars",6000);
                            messages.put(new JSONObject().put("role","user").put("content","章节："+chapter.getString("title")+"\n原文抽样：\n"+store.summarySample(id,inputBudget)));
                            int summaryLimit=getPreferences(MODE_PRIVATE).getInt("summary_max_tokens",4096);
                            String summary=api.chat(messages,summaryLimit,300);
                            JSONArray integratedHits=null;
                            if(summaryTags.length()>0){
                                int marker=summary.lastIndexOf("特殊标签JSON");
                                if(marker>=0){
                                    String tail=summary.substring(marker);
                                    String cleaned=summary.substring(0,marker).trim();
                                    int brace=tail.indexOf('{'),bracket=tail.indexOf('[');
                                    try{
                                        if(brace>=0&&(bracket<0||brace<bracket)){
                                            int end=tail.lastIndexOf('}');
                                            if(end>brace){
                                                JSONObject parsed=new JSONObject(tail.substring(brace,end+1));
                                                integratedHits=parsed.optJSONArray("matches");
                                                if(integratedHits==null)integratedHits=new JSONArray();
                                            }
                                        }else if(bracket>=0){
                                            int end=tail.lastIndexOf(']');
                                            if(end>bracket)integratedHits=new JSONArray(tail.substring(bracket,end+1));
                                        }
                                    }catch(Exception ignored){/* Keep the summary; malformed tag JSON never invalidates it. */}
                                    summary=cleaned;
                                }
                            }
                            if(!summary.contains("关键词")||!summary.contains("剧情概览"))throw new IllegalStateException("模型未按要求返回关键词和剧情概览");
                            store.setSummary(id,summary,"");
                            if(integratedHits!=null)store.replaceModelSpecialHits(archiveId,id,integratedHits);
                        }
                    }catch(Exception ex){failed++;store.setError(id,ex.getMessage());emit("chapterError",new JSONObject().put("mode",mode).put("id",id).put("message",ex.getMessage()));}
                    done++;emitProgress(mode,done,ids.size(),failed,chapter.getString("title"));
                }
            }catch(Exception ex){error(ex);}finally{
                if(!"rewrite".equals(mode))try{store.rebuildOverview(archiveId);}catch(Exception ex){error(ex);}
                jobRunning=false;jobDetail="";getPreferences(MODE_PRIVATE).edit().putString("last_job_status",cancelJob.get()?"paused":failed>0?"failed":"completed").apply();
                emitProgress(mode,done,ids.size(),failed,cancelJob.get()?"已暂停，可续建":"已结束");emitLastJob();updateStatusNotification();
                try{emit("jobDone",new JSONObject().put("archive_id",archiveId));}catch(Exception ignored){}
            }
        });
    }
    private void emitProgress(String mode,int done,int total,int failed,String chapter){try{
        String detail=("rewrite".equals(mode)?"改写":"总结")+" " + done + "/" + total + " 章 · 失败 " + failed + " · " + chapter;
        if(jobRunning)jobDetail=detail;
        getPreferences(MODE_PRIVATE).edit().putString("last_job_detail",detail).apply();
        emit("progress",new JSONObject().put("mode",mode).put("done",done).put("total",total).put("failed",failed).put("chapter",chapter));
        emitLastJob();
    }catch(Exception ignored){}}

    private void emitLastJob() {
        try {
            android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
            emit("lastJob",new JSONObject().put("status",prefs.getString("last_job_status","none")).put("detail",prefs.getString("last_job_detail","尚无任务")).put("has_payload",!prefs.getString("last_job_payload","").isEmpty()));
        } catch(Exception ignored) {}
    }

    private void resumeLastJob() throws Exception {
        String raw=getPreferences(MODE_PRIVATE).getString("last_job_payload","");
        if(raw.isEmpty())throw new IllegalStateException("没有可恢复的任务");
        JSONObject payload=new JSONObject(raw);
        if(payload.optLong("chapter_id",0)>0)payload.put("resume_partial",true);
        else payload.put("missing_only",true);
        startJob(payload);
    }

    static String appendRewrite(String existing,String next){
        existing=existing==null?"":existing.trim();next=next==null?"":next.trim();
        if(existing.isEmpty())return next;
        for(int size=Math.min(1200,Math.min(existing.length(),next.length()));size>=20;size--)
            if(existing.endsWith(next.substring(0,size)))return existing+next.substring(size);
        return existing+"\n\n"+next;
    }

    static boolean hasLongRepeatedParagraph(String content){
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(String paragraph:content.split("\\n\\s*\\n")){
            String normalized=paragraph.replaceAll("\\s+","");
            if(normalized.length()>=120&&!seen.add(normalized))return true;
        }
        return false;
    }

    private void recordRewriteUsage(long archiveId,int input,int output){
        if(input<=0&&output<=0)return;
        String prefix="rewrite_usage_"+archiveId+"_";
        android.content.SharedPreferences preferences=getPreferences(MODE_PRIVATE);
        int totalInput=preferences.getInt(prefix+"input",0)+Math.max(0,input);
        int totalOutput=preferences.getInt(prefix+"output",0)+Math.max(0,output);
        preferences.edit().putInt(prefix+"input",totalInput).putInt(prefix+"output",totalOutput).apply();
        try{emit("rewriteUsage",new JSONObject().put("archive_id",archiveId).put("input",totalInput).put("output",totalOutput));}catch(Exception ignored){}
    }

    private static String exportName(String name,boolean rewrites){
        String value=name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
        if(value.isEmpty())value=rewrites?"rewrites":"knowledge-base";
        String suffix=rewrites?".novel-memory-rewrites.json":".novel-memory.json";
        if(!value.toLowerCase(java.util.Locale.ROOT).endsWith(suffix))value+=suffix;
        return value;
    }
    private void pickFile(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","text/markdown","application/json","application/epub+zip","application/octet-stream"});startActivityForResult(i,IMPORT_FILE);}catch(Exception ex){error(ex);}}
    private void pickCard(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","image/png","application/octet-stream"});startActivityForResult(i,IMPORT_CARD);}catch(Exception ex){error(ex);}}
    private void templateImportPicker(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,IMPORT_TEMPLATE);}catch(Exception ex){error(ex);}}
    private void templateExportPicker(String name){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,name);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_TEMPLATE);}catch(Exception ex){error(ex);}}
    private void cardExportPicker(String name){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,name);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_CARD);}catch(Exception ex){error(ex);}}
    private void exportPicker(String name){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,name);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_KB);}catch(Exception ex){error(ex);}}
    private void rewriteExportPicker(String name){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,name);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_REWRITES);}catch(Exception ex){error(ex);}}
    private void saveToDownloads(long archiveId,String name,boolean rewrites) throws Exception {
        store.archive(archiveId);
        if(Build.VERSION.SDK_INT<29){
            pendingExportArchive=archiveId;runOnUiThread(()->{if(rewrites)rewriteExportPicker(name);else exportPicker(name);});return;
        }
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/NovelMemory");
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IllegalStateException("无法在下载目录创建文件，请使用“选择位置另存为”");
        boolean success=false;
        try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){
            if(output==null)throw new IllegalStateException("无法打开导出文件");
            if(rewrites)store.exportRewrites(archiveId,output);else store.exportKnowledgeBase(archiveId,output);
            success=true;
        }finally{if(!success)getContentResolver().delete(uri,null,null);}
        String actual=filename(uri);if("imported.txt".equals(actual))actual=name;
        emit("exported",new JSONObject().put("filename",actual).put("location","下载/NovelMemory/"+actual));
    }
    private String filename(Uri uri){try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int col=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(col>=0)return c.getString(col);}}return "imported.txt";}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK_BACKGROUND){
            if(backgroundFileCallback!=null){backgroundFileCallback.onReceiveValue(resultCode==RESULT_OK&&data!=null&&data.getData()!=null?new Uri[]{data.getData()}:null);backgroundFileCallback=null;}
            return;
        }
        if(resultCode!=RESULT_OK||data==null||data.getData()==null){
            if(requestCode==EXPORT_KB||requestCode==EXPORT_REWRITES||requestCode==EXPORT_CARD||requestCode==EXPORT_TEMPLATE)emit("exportCancelled","已取消导出，未创建文件");
            return;
        }
        Uri uri=data.getData();
        if(requestCode==IMPORT_FILE){async(()->{
            String name=filename(uri);String lower=name.toLowerCase();
            try(InputStream input=getContentResolver().openInputStream(uri)){
                long id;if(lower.endsWith(".novel-memory-rewrites.json")){
                    if(pendingImportArchive==0)throw new IllegalArgumentException("请先选择对应的知识库，再导入改写稿");
                    int count=store.importRewrites(pendingImportArchive,input);id=pendingImportArchive;emit("notice","已导入 "+count+" 章改写稿");
                }
                else if(lower.endsWith(".novel-memory.json"))id=store.importKnowledgeBase(input);
                else if(lower.endsWith(".txt")||lower.endsWith(".md"))id=store.importText(name,input);
                else if(lower.endsWith(".epub"))id=store.importEpub(name,input);
                else throw new IllegalArgumentException("仅支持 .txt、.md、.epub、.novel-memory.json 和 .novel-memory-rewrites.json");
                emit("imported",new JSONObject().put("archive_id",id).put("archives",store.archives()));
            }
        });}
        else if(requestCode==EXPORT_KB){long archiveId=pendingExportArchive;async(()->{
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){store.exportKnowledgeBase(archiveId,output);}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==EXPORT_REWRITES){long archiveId=pendingExportArchive;async(()->{
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){store.exportRewrites(archiveId,output);}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==IMPORT_CARD){long archiveId=pendingCardArchive;async(()->{
            if(archiveId==0)throw new IllegalArgumentException("请先选择知识库再导入角色卡");
            byte[] bytes;
            try(InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>5_000_000)throw new IllegalArgumentException("角色卡文件超过 5MB");}bytes=buffer.toByteArray();
            }
            final JSONObject parsed=TavernCard.parse(bytes);
            final JSONObject profile=parsed.getJSONObject("profile");
            runOnUiThread(()->new AlertDialog.Builder(MainActivity.this)
                    .setTitle("角色卡导入预览")
                    .setMessage(parsed.optString("preview"))
                    .setPositiveButton("确认导入",(dialog,which)->async(()->{
                        JSONObject saved=chatData.saveProfile(archiveId,profile);
                        emitChatState(archiveId,"roleplay",saved.getLong("id"),0);
                        emit("notice","角色卡已导入；可在对话功能中继续编辑");
                    }))
                    .setNegativeButton("取消",(dialog,which)->emit("notice","已取消导入，未写入角色卡"))
                    .show());
        });}
        else if(requestCode==EXPORT_CARD){long archiveId=pendingCardArchive,cardId=pendingCardId;async(()->{
            JSONObject card=chatData.profile(archiveId,cardId);
            card.put("first_mes",card.optString("first_message",""));card.put("mes_example",card.optString("example_dialogue",""));
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){output.write(card.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==IMPORT_TEMPLATE){long archiveId=pendingTemplateArchive;async(()->{
            store.archive(archiveId);
            byte[] bytes;
            try(InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>1_000_000)throw new IllegalArgumentException("改写模板超过 1MB");}bytes=buffer.toByteArray();
            }
            JSONObject template=rewriteTemplate.save(archiveId,new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            emit("specialTagState",store.mergeSpecialTags(archiveId,template.getJSONArray("tags")));
            store.saveRewriteGlobalPrompt(archiveId,template.getJSONObject("rewrite").optString("global_prompt",""));
            emit("rewriteTemplate",new JSONObject().put("archive_id",archiveId).put("template",template).put("apply_prompt",true));emit("notice","已导入桌面/安卓通用改写模板");
        });}
        else if(requestCode==EXPORT_TEMPLATE){long archiveId=pendingTemplateArchive;async(()->{
            store.archive(archiveId);JSONObject template=rewriteTemplate.load(archiveId);
            if(template==null)throw new IllegalStateException("请先保存改写模板");
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){output.write(template.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
    }
}
