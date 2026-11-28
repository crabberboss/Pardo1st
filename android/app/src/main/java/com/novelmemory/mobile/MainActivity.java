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
import android.content.ClipData;
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
import android.view.WindowInsets;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final String STATUS_CHANNEL="pardo_work_status";private static final int STATUS_ID=101;
    private static final int IMPORT_FILE=11,EXPORT_KB=12,EXPORT_REWRITES=13,IMPORT_CARD=14,EXPORT_CARD=15,IMPORT_TEMPLATE=16,EXPORT_TEMPLATE=17,PICK_BACKGROUND=18,IMPORT_GENERATION=19,EXPORT_GENERATION=20,IMPORT_SETTINGS=21,EXPORT_SETTINGS=22,IMPORT_CHAT_PRESET=23,EXPORT_CHAT_PRESET=24,IMPORT_WRITING_BOOKS=25,IMPORT_WRITING_PROFILE=26,EXPORT_WRITING_PROFILE=27;
    private WebView web;private Store store;private Api api;private VectorMemory vectorMemory;private ChatData chatData;private ChatEngine chatEngine;private RewriteTemplate rewriteTemplate;private NovelGeneration generation;private WritingPromptProfiles writingProfiles;
    private final ExecutorService io=Executors.newCachedThreadPool();
    private final AtomicBoolean cancelJob=new AtomicBoolean(false);
    private final AtomicInteger activeChatRequests=new AtomicInteger(0);
    private final AtomicInteger activeTasks=new AtomicInteger(0);
    private final AtomicInteger lightweightTasks=new AtomicInteger(0);
    private final AtomicBoolean resourcesClosed=new AtomicBoolean(false);
    private static final AtomicInteger allActiveTasks=new AtomicInteger(0);
    private static volatile MainActivity visibleActivity,detachedWorker;
    private static final Set<MainActivity> activeOwners=new HashSet<>();
    private static final ArrayDeque<String> pendingEvents=new ArrayDeque<>();
    private volatile boolean destroyed=false;
    private volatile boolean pageReady=false;
    private volatile boolean jobRunning=false;
    private volatile String jobDetail="";
    private long pendingExportArchive=0;
    private long pendingImportArchive=0;
    private long pendingCardArchive=0,pendingCardId=0;
    private long pendingTemplateArchive=0;
    private long pendingChatPresetArchive=0;
    private String pendingGenerationId="";
    private String pendingWritingProfileId="";
    private boolean pendingSettingsSecrets=false;
    private JSONObject pendingAppearance=new JSONObject();
    private ValueCallback<Uri[]> backgroundFileCallback;
    private final AtomicBoolean backPending=new AtomicBoolean(false);
    private android.window.OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new Store(this);api=new Api(this);vectorMemory=new VectorMemory(store,api);chatData=new ChatData(store);chatEngine=new ChatEngine(this,store,chatData,api);rewriteTemplate=new RewriteTemplate(this);writingProfiles=new WritingPromptProfiles(this);
        MainActivity worker=detachedWorker;
        generation=worker!=null&&worker.generation!=null&&worker.generation.isRunning()?worker.generation:
            new NovelGeneration(this,api,(type,data)->{
                emit(type,data);
                jobDetail=data.optString("detail","小说生成中");updateStatusNotification();
            });
        if((worker==null||worker.activeTasks.get()==0)&&"running".equals(getPreferences(MODE_PRIVATE).getString("last_job_status","")))getPreferences(MODE_PRIVATE).edit().putString("last_job_status","interrupted").putString("last_job_detail","上次任务已中断；可继续处理未完成章节").apply();
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);web.getSettings().setAllowContentAccess(true);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            web.setBackgroundColor(Color.rgb(129,96,88));
            web.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
                int bottom=Math.max(bars.bottom,ime.bottom);
                if(view.getPaddingLeft()!=bars.left||view.getPaddingTop()!=bars.top||view.getPaddingRight()!=bars.right||view.getPaddingBottom()!=bottom)
                    view.setPadding(bars.left,bars.top,bars.right,bottom);
                return insets;
            });
        }
        getWindow().setStatusBarColor(Color.rgb(129,96,88));
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()&~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
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
            @Override public void onPageFinished(WebView view,String url){pageReady=true;flushPendingEvents();refreshVisiblePage();openStatusDestination(getIntent());}
        });setContentView(web);web.loadUrl("file:///android_asset/index.html");
        if(Build.VERSION.SDK_INT>=33){backCallback=this::dispatchBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(STATUS_CHANNEL,"当前工作状态",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},STATUS_ID);
        else updateStatusNotification();
    }
    @Override public void onResume(){super.onResume();visibleActivity=this;flushPendingEvents();refreshVisiblePage();updateStatusNotification();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openStatusDestination(intent);refreshVisiblePage();updateStatusNotification();}
    @Override public void onPause(){if(visibleActivity==this)visibleActivity=null;super.onPause();}
    @Override public void onDestroy(){
        destroyed=true;if(visibleActivity==this)visibleActivity=null;
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if(web!=null){web.destroy();web=null;}
        if(activeTasks.get()>0||lightweightTasks.get()>0)detachedWorker=this;
        else {closeResources();
            if(allActiveTasks.get()==0)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(STATUS_ID);}
        super.onDestroy();
    }
    static boolean hasActiveWork(){return allActiveTasks.get()>0;}
    static boolean hasVisibleUi(){return visibleActivity!=null&&!visibleActivity.destroyed;}
    private void openStatusDestination(Intent intent){
        if(intent==null||!intent.getBooleanExtra("open_work_status",false)||!pageReady||web==null)return;
        intent.removeExtra("open_work_status");
        String target=generation.isRunning()?"generation":jobRunning?(jobDetail.startsWith("改写")?"rewrite":"library"):"home";
        String projectId="generation".equals(target)?generation.activeProjectId():"";
        web.evaluateJavascript("window.appOpenWorkStatus&&window.appOpenWorkStatus("+JSONObject.quote(target)+","+JSONObject.quote(projectId)+")",null);
    }
    private void closeResources(){
        if(!resourcesClosed.compareAndSet(false,true))return;
        io.shutdown();store.close();if(detachedWorker==this)detachedWorker=null;
    }
    @Override public void onBackPressed(){dispatchBack();}
    private void dispatchBack(){
        if(!backPending.compareAndSet(false,true))return;
        WebView view=web;
        if(view==null||!pageReady){backPending.set(false);moveTaskToBack(true);return;}
        view.evaluateJavascript("(function(){try{return !!(window.appHandleBack&&window.appHandleBack())}catch(e){return false}})()",result->{
            backPending.set(false);
            if(!"true".equals(result))moveTaskToBack(true);
        });
    }
    static void pauseActiveWorkForSystemLimit(){synchronized(activeOwners){for(MainActivity worker:activeOwners){
        worker.cancelJob.set(true);worker.generation.pause();}}}
    private void refreshVisiblePage(){WebView view=web;if(view==null)return;
        view.evaluateJavascript("window.refreshAfterResume&&window.refreshAfterResume()",null);
    }
    private void flushPendingEvents(){if(!pageReady||web==null||visibleActivity!=this)return;
        synchronized(pendingEvents){while(!pendingEvents.isEmpty())web.evaluateJavascript(pendingEvents.removeFirst(),null);}
    }
    private void beginTask(){synchronized(activeOwners){activeOwners.add(this);}activeTasks.incrementAndGet();allActiveTasks.incrementAndGet();
        Intent intent=new Intent(this,BackgroundWorkService.class).setAction(BackgroundWorkService.UPDATE);
        intent.putExtra("detail",jobDetail.isEmpty()?"正在调用模型或处理文件":jobDetail);
        try{startForegroundService(intent);}catch(Exception ex){android.util.Log.w("PardoWork","前台服务启动失败",ex);}
    }
    private void endTask(){allActiveTasks.decrementAndGet();if(activeTasks.decrementAndGet()>0){updateStatusNotification();return;}
        synchronized(activeOwners){activeOwners.remove(this);}
        try{startService(new Intent(this,BackgroundWorkService.class).setAction(BackgroundWorkService.STOP));}catch(Exception ignored){}
        if(destroyed){if(lightweightTasks.get()==0)closeResources();}
        else updateStatusNotification();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==STATUS_ID&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)updateStatusNotification();}
    private void updateStatusNotification(){runOnUiThread(()->{
        String detail=(jobRunning||generation.isRunning())?(jobDetail.isEmpty()?"正在处理章节":jobDetail):activeChatRequests.get()>0?"正在调用模型对话":"已打开 · 当前空闲";
        if(activeTasks.get()>0){Intent service=new Intent(this,BackgroundWorkService.class).setAction(BackgroundWorkService.UPDATE).putExtra("detail",detail);
            try{startService(service);}catch(Exception ex){android.util.Log.w("PardoWork","更新前台服务失败",ex);}return;}
        if(destroyed||Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        Intent open=new Intent(this,MainActivity.class);open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);open.putExtra("open_work_status",true);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,STATUS_CHANNEL).setSmallIcon(R.drawable.pardo_icon).setContentTitle("帕朵一号 · 工作状态")
            .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(pending).setOnlyAlertOnce(true).setOngoing(true).build();
        notification.flags|=Notification.FLAG_NO_CLEAR|Notification.FLAG_ONGOING_EVENT;
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(STATUS_ID,notification);}catch(SecurityException ignored){}
    });}

    private void emit(String type,Object value){
        if("progress".equals(type))updateStatusNotification();
        String data=value instanceof JSONObject||value instanceof JSONArray?value.toString():JSONObject.quote(String.valueOf(value));
        String script="window.nativeEvent("+JSONObject.quote(type)+","+data+")";
        MainActivity target=visibleActivity;
        if(target==null||!target.pageReady||target.web==null){
            if(!"progress".equals(type)&&!"generationProject".equals(type))synchronized(pendingEvents){
                if(pendingEvents.size()>=200)pendingEvents.removeFirst();pendingEvents.addLast(script);}
            return;
        }
        target.runOnUiThread(()->{WebView view=target.web;if(view!=null)view.evaluateJavascript(script,null);});
    }
    private void error(Exception ex){emit("error",ex.getMessage()==null?ex.toString():ex.getMessage());}
    private void async(Throwing task){beginTask();io.execute(()->{try{task.run();}catch(Exception ex){error(ex);}finally{endTask();}});}
    private void lightweight(Throwing task){lightweightTasks.incrementAndGet();io.execute(()->{
        try{task.run();}catch(Exception ex){error(ex);}finally{
            if(lightweightTasks.decrementAndGet()==0&&destroyed&&activeTasks.get()==0)closeResources();
        }
    });}
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
                case "cancelJob":cancelJob.set(true);if(detachedWorker!=null&&detachedWorker.jobRunning)detachedWorker.cancelJob.set(true);return;
                case "generationPause":generation.pause();return;
                case "generationPickImport":runOnUiThread(()->generationImportPicker());return;
                case "generationPickExport":pendingGenerationId=p.optString("id","");runOnUiThread(()->generationExportPicker(p.optString("name","小说生成项目")+".pardo-generation.json"));return;
                case "generationWritingPickBooks":runOnUiThread(()->writingBooksPicker());return;
                case "generationWritingProfileImport":runOnUiThread(()->writingProfileImportPicker());return;
                case "generationWritingProfileExport":pendingWritingProfileId=p.optString("id","");runOnUiThread(()->writingProfileExportPicker(p.optString("name","写作提示词")+".pardo-writing-prompt.json"));return;
                case "transferSettingsExport":pendingSettingsSecrets=p.optBoolean("include_keys",false);pendingAppearance=p.optJSONObject("appearance");if(pendingAppearance==null)pendingAppearance=new JSONObject();runOnUiThread(()->settingsExportPicker());return;
                case "transferSettingsImport":pendingSettingsSecrets=p.optBoolean("allow_keys",false);runOnUiThread(()->settingsImportPicker());return;
                case "transferChatPresetExport":pendingChatPresetArchive=p.optLong("archive_id",0);runOnUiThread(()->chatPresetExportPicker());return;
                case "transferChatPresetImport":pendingChatPresetArchive=p.optLong("archive_id",0);runOnUiThread(()->chatPresetImportPicker());return;
                case "resumeJob":async(()->resumeLastJob());return;
                case "pickCard":pendingCardArchive=p.optLong("archive_id",0);runOnUiThread(()->pickCard());return;
                case "exportCard":pendingCardArchive=p.optLong("archive_id",0);pendingCardId=p.optLong("profile_id",0);runOnUiThread(()->cardExportPicker(p.optString("name","角色卡")+".json"));return;
                case "pickTemplate":pendingTemplateArchive=p.optLong("archive_id",0);runOnUiThread(()->templateImportPicker());return;
                case "exportTemplate":pendingTemplateArchive=p.optLong("archive_id",0);runOnUiThread(()->templateExportPicker(p.optString("name","改写模板")+".json"));return;
                default:if(isLightweightRequest(operation))lightweight(()->handle(operation,p));
                    else async(()->handle(operation,p));
            }
        }
    }
    private static boolean isLightweightRequest(String operation){
        switch(operation){
            case "init":case "archives":case "archive":case "diagnostics":case "chapters":case "chapter":
            case "sourceSlice":case "readerSlice":case "readerPrefetch":case "readerBrowse":case "readerCatalog":
            case "epubImages":case "epubImage":case "rewriteSourceSlice":case "rewriteBrowse":case "rewritePrompt":
            case "specialTagState":case "specialExpandSettingsGet":case "rewriteExpandSettingsGet":
            case "generationExpandSettingsGet":case "generationList":case "generationGet":case "generationRefresh":case "generationPromptGet":case "generationWritingProfilesGet":
            case "generationChapter":case "transferOptions":case "config":case "summaryTokenGet":case "vectorConfigGet":case "rewriteTokenGet":
            case "rewriteFlowGet":case "rewriteUsageGet":case "rewritePartial":case "messages":case "chatOpen":
            case "chatGlobals":case "templateGet":return true;
            default:return false;
        }
    }

    private JSONObject generationExpandPrompts() throws Exception {
        JSONObject saved;try{saved=new JSONObject(getPreferences(MODE_PRIVATE).getString("generation_expand_prompts","{}"));}
        catch(Exception ignored){saved=new JSONObject();}
        JSONObject result=new JSONObject();
        result.put("outline",saved.optString("outline","你是长篇小说方向参考编辑。根据已有设定扩写可能的主角目标、冲突和发展方向；这些只是下一章细纲规划时的弱参考，不固定后续情节、结局、卷数或章节数。保留用户明确设定，只输出完整方向参考。"));
        result.put("world",saved.optString("world","你是小说世界观编辑。结合书名、主角与总纲，扩写时代、地域、势力、社会规则和关键限制；保留已有设定，明确尚未决定的内容，不把猜测写成既定事实。只输出可编辑的世界观正文。"));
        result.put("system",saved.optString("system","你是小说能力体系编辑。扩写能力来源、等级或数值字段、物品与成长规则及限制；若题材不需要数值系统，不要强加游戏面板。保留用户已有设定，只输出完整系统设定。"));
        result.put("protagonist",saved.optString("protagonist","你是角色设定编辑。围绕指定主角扩写性格、目标、能力、初始状态、关系与行为边界；保留姓名和已有事实，不凭空写入后期剧情。只输出完整角色设定。"));
        result.put("style",saved.optString("style","你是小说文风编辑。依据总纲与现有文风说明，扩写叙述视角、节奏、用词、对白与禁忌表达等可执行规则；保留用户偏好。只输出完整文风设定。"));
        result.put("chapter",saved.optString("chapter","你是章节细纲编辑。优先依据现有本章章纲和出场角色扩写起因、场景节拍、角色行动、转折与章末落点；全书方向参考只是可偏离的弱提示。不得提前写后续章节正文或违背已确认设定。只输出可供用户审核的完整本章细纲。"));
        return result;
    }
    private JSONObject generationExpandLimits() throws Exception {
        JSONObject saved;try{saved=new JSONObject(getPreferences(MODE_PRIVATE).getString("generation_expand_limits","{}"));}
        catch(Exception ignored){saved=new JSONObject();}
        return new JSONObject().put("outline",saved.optInt("outline",4096))
            .put("world",saved.optInt("world",8192)).put("system",saved.optInt("system",6144))
            .put("protagonist",saved.optInt("protagonist",4096)).put("style",saved.optInt("style",3072))
            .put("chapter",saved.optInt("chapter",4096));
    }
    private JSONObject generationExpandSettings() throws Exception {
        return new JSONObject().put("prompts",generationExpandPrompts()).put("limits",generationExpandLimits());
    }
    private void handle(String op,JSONObject p) throws Exception {
        long archiveId=p.optLong("archive_id",0);
        switch(op){
            case "transferOptions":{
                JSONObject result=new JSONObject().put("archives",store.archives()).put("projects",generation.list());
                if(archiveId>0){store.archive(archiveId);result.put("profiles",chatData.profiles(archiveId));result.put("template",rewriteTemplate.load(archiveId));}
                emit("transferOptions",result);break;
            }
            case "generationExpandSettingsGet":emit("generationExpandSettings",generationExpandSettings());break;
            case "generationExpandSettingsSave":{
                JSONObject incoming=p.getJSONObject("prompts"),incomingLimits=p.optJSONObject("limits"),saved=new JSONObject(),savedLimits=new JSONObject();
                if(incomingLimits==null)incomingLimits=generationExpandLimits();
                for(String field:new String[]{"outline","world","system","protagonist","style","chapter"}){
                    String value=incoming.optString(field,"").trim();
                    if(value.isEmpty()||value.length()>5000)throw new IllegalArgumentException("扩写提示词不能为空且不得超过 5000 字："+field);
                    saved.put(field,value);
                    int limit=incomingLimits.optInt(field,0);
                    if(limit<512||limit>32768)throw new IllegalArgumentException("扩写输出 Token 上限须为 512–32768："+field);
                    savedLimits.put(field,limit);
                }
                getPreferences(MODE_PRIVATE).edit().putString("generation_expand_prompts",saved.toString())
                    .putString("generation_expand_limits",savedLimits.toString()).apply();
                emit("generationExpandSettings",generationExpandSettings());emit("notice","小说生成扩写提示词与输出上限已保存");break;
            }
            case "generationExpand":{
                String field=p.optString("field","");if(!java.util.Arrays.asList("outline","world","system","protagonist","style","chapter").contains(field))throw new IllegalArgumentException("未知扩写类型");
                String source=p.optString("text","").trim(),context=p.optString("context","").trim();
                if(source.length()>12000||context.length()>12000)throw new IllegalArgumentException("待扩写内容过长，请先压缩到 12000 字以内");
                if(source.isEmpty()&&context.isEmpty())throw new IllegalArgumentException("请先填写本项内容或项目基础信息");
                String instruction=generationExpandPrompts().getString(field);
                JSONArray prompt=new JSONArray().put(new JSONObject().put("role","system").put("content",instruction))
                    .put(new JSONObject().put("role","user").put("content","参考项目设定：\n"+context+"\n\n当前输入：\n"+source+"\n\n请输出扩写后的完整文本，不要额外说明。"));
                int maxTokens=generationExpandLimits().getInt(field);
                if(maxTokens<512||maxTokens>32768)throw new IllegalStateException("扩写输出上限配置无效，请在设置中重新保存");
                String expanded=api.chat(prompt,maxTokens,180);
                emit("generationExpanded",new JSONObject().put("field",field).put("project_id",p.optString("project_id",""))
                    .put("request_id",p.optInt("request_id",0)).put("text",expanded));break;
            }
            case "generationList":emit("generationList",generation.list());break;
            case "generationWritingProfilesGet":emit("generationWritingProfiles",writingProfiles.list());break;
            case "generationWritingProfileSave":{
                writingProfiles.save(p.getJSONObject("profile"));
                emit("generationWritingProfileSaved",new JSONObject());emit("generationWritingProfiles",writingProfiles.list());break;
            }
            case "generationWritingProfileApply":{
                JSONObject profile=writingProfiles.get(p.getString("profile_id"));
                JSONObject updated=generation.update(p.getString("project_id"),new JSONObject()
                    .put("style",profile.getString("style_prompt"))
                    .put("generation_prompt",profile.getString("unified_prompt")));
                emit("generationProject",NovelGeneration.viewOf(updated));
                emit("generationWritingApplied",new JSONObject().put("id",updated.getString("id"))
                    .put("generation_prompt",updated.optString("generation_prompt")));
                break;
            }
            case "generationPromptGet":{
                JSONObject project=generation.view(p.getString("id"));
                emit("generationUnifiedPrompt",new JSONObject().put("id",project.getString("id"))
                    .put("name",project.optString("name")).put("prompt",project.optString("generation_prompt")));
                break;
            }
            case "generationCreate":{JSONObject project=generation.create(p);emit("generationSelected",NovelGeneration.viewOf(project));emit("generationList",generation.list());break;}
            case "generationGet":emit("generationSelected",generation.view(p.getString("id")));break;
            case "generationRefresh":emit("generationProject",generation.view(p.getString("id")));break;
            case "generationChapter":emit("generationChapter",new JSONObject().put("project_id",p.getString("id")).put("chapter",generation.chapter(p.getString("id"),p.getInt("number"))));break;
            case "generationChapterSave":{
                String id=p.getString("id");int number=p.getInt("number");
                JSONObject project=generation.saveCompletedChapter(id,number,p.getLong("expected_updated_at"),p.getJSONObject("changes"));
                emit("generationProject",NovelGeneration.viewOf(project));
                emit("generationChapter",new JSONObject().put("project_id",id).put("chapter",generation.chapter(id,number)));
                emit("notice","本章修改已保存");break;
            }
            case "generationChapterRestore":{
                String id=p.getString("id");int number=p.getInt("number");
                JSONObject project=generation.restoreCompletedChapter(id,number,p.getLong("expected_updated_at"));
                emit("generationProject",NovelGeneration.viewOf(project));
                emit("generationChapter",new JSONObject().put("project_id",id).put("chapter",generation.chapter(id,number)));
                emit("notice","已恢复本章上一版");break;
            }
            case "generationChapterRegenerate":{
                String id=p.getString("id");int number=p.getInt("number");
                generation.regenerateCompletedChapter(id,number,p.getLong("expected_updated_at"));
                emit("generationChapter",new JSONObject().put("project_id",id).put("chapter",generation.chapter(id,number)));
                emit("notice","本章正文已重新生成；请核对记忆和后续剧情");break;
            }
            case "generationUpdate":{
                if("unifiedPrompt".equals(p.optString("source"))){
                    JSONObject changes=p.getJSONObject("changes");
                    if(changes.length()!=1||!changes.has("generation_prompt")||changes.optString("generation_prompt").length()>8000)
                        throw new IllegalArgumentException("统一提示词格式无效或超过 8000 字");
                }
                JSONObject project=generation.update(p.getString("id"),p.getJSONObject("changes"));
                emit("generationProject",NovelGeneration.viewOf(project));
                if("settings".equals(p.optString("source")))emit("generationSettingsSaved",new JSONObject()
                    .put("id",project.getString("id")).put("saved_at",System.currentTimeMillis()));
                if("unifiedPrompt".equals(p.optString("source")))emit("generationUnifiedPromptSaved",new JSONObject()
                    .put("id",project.getString("id")).put("saved_at",System.currentTimeMillis()));
                break;
            }
            case "generationFinish":generation.finish(p.getString("id"));emit("generationList",generation.list());break;
            case "generationContinue":generation.continueAfterEnding(p.getString("id"));break;
            case "generationDelete":generation.delete(p.getString("id"));emit("generationList",generation.list());break;
            case "generationStart":generation.run(p.getString("id"),p.optBoolean("auto_continue",false));emit("generationList",generation.list());updateStatusNotification();break;
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
            case "vectorConfigGet":emit("vectorConfig",vectorMemory.config(archiveId).put("source",vectorMemory.sourceConfig(archiveId)));break;
            case "vectorConfigSave":{
                boolean enabled=p.optBoolean("enabled",false);String provider=p.optString("provider","siliconflow"),model=p.optString("model","BAAI/bge-m3");
                vectorMemory.saveSourceConfig(archiveId,enabled,provider,model);
                emit("vectorConfig",vectorMemory.saveConfig(archiveId,enabled,provider,model).put("source",vectorMemory.sourceConfig(archiveId)));break;
            }
            case "vectorBuild":startVectorJob(p);break;
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
            case "epubImages":emit("epubImages",new JSONObject().put("images",store.epubImages(p.getLong("archive_id"))));break;
            case "epubImage":emit("epubImage",store.epubImage(p.getLong("image_id")));break;
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
                    JSONObject result=chatEngine.respond(archiveId,p.optString("mode","qa"),p.optLong("profile_id",0),p.optLong("session_id",0),p.optString("text",""),p.optLong("assistant_id",0),validatedChatImage(p.optString("image_url","")));
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

    private synchronized void startVectorJob(JSONObject p) throws Exception {
        if(jobRunning||detachedWorker!=null&&detachedWorker!=this&&detachedWorker.jobRunning)throw new IllegalStateException("已有任务在运行；请先等待或暂停");
        long archiveId=p.getLong("archive_id");String provider=p.optString("provider","siliconflow"),model=p.optString("model","BAAI/bge-m3");
        if(!api.config(provider).getBoolean("has_key"))throw new IllegalStateException("请先在设置中保存在线嵌入渠道的 API Key");
        vectorMemory.saveConfig(archiveId,true,provider,model);vectorMemory.saveSourceConfig(archiveId,true,provider,model);
        jobRunning=true;cancelJob.set(false);jobDetail="准备在线向量化章节总结";
        getPreferences(MODE_PRIVATE).edit().putString("last_job_payload",p.toString()).putString("last_job_status","running").putString("last_job_detail",jobDetail).apply();
        beginTask();updateStatusNotification();emitLastJob();
        io.execute(()->{
            boolean failed=false;
            try{buildBothVectors(archiveId,provider,model);}
            catch(Exception ex){failed=true;error(ex);}
            finally{
                jobRunning=false;jobDetail="";
                getPreferences(MODE_PRIVATE).edit().putString("last_job_status",cancelJob.get()?"paused":failed?"failed":"completed").apply();
                try{emit("vectorConfig",vectorMemory.config(archiveId).put("source",vectorMemory.sourceConfig(archiveId)));emit("jobDone",new JSONObject().put("archive_id",archiveId));}catch(Exception ignored){}
                emitLastJob();updateStatusNotification();endTask();
            }
        });
    }
    private void emitVectorProgress(int done,int total,String detail){
        try{jobDetail=detail;getPreferences(MODE_PRIVATE).edit().putString("last_job_detail",detail).apply();
            emit("vectorProgress",new JSONObject().put("done",done).put("total",total).put("detail",detail));emitLastJob();updateStatusNotification();}
        catch(Exception ignored){}
    }
    private void buildBothVectors(long archiveId,String provider,String model) throws Exception {
        String failures="";
        try{vectorMemory.build(archiveId,provider,model,cancelJob,(done,total,detail)->emitVectorProgress(done,total,"章节记忆 · "+detail));}
        catch(Exception ex){failures="章节记忆："+ex.getMessage();}
        if(!cancelJob.get())try{vectorMemory.buildSource(archiveId,provider,model,cancelJob,(done,total,detail)->emitVectorProgress(done,total,detail));}
        catch(Exception ex){failures+=(failures.isEmpty()?"":"；")+"原文："+ex.getMessage();}
        if(!failures.isEmpty())throw new IllegalStateException("向量化部分失败，已完成部分保留。"+failures);
    }

    private synchronized void startJob(JSONObject p) throws Exception {
        if(jobRunning||detachedWorker!=null&&detachedWorker!=this&&detachedWorker.jobRunning)throw new IllegalStateException("已有任务在运行；请先等待或暂停");
        String mode=p.optString("mode","summary");long archiveId=p.getLong("archive_id");
        String keyword=p.optString("keyword","").trim();boolean missingOnly=p.optBoolean("missing_only",true);
        List<Long> ids;
        if(p.optLong("chapter_id",0)>0){ids=new ArrayList<>();ids.add(p.getLong("chapter_id"));}
        else ids=store.targetIds(archiveId,keyword,mode,missingOnly,p.optJSONObject("template_filter"));
        boolean vectorize="summary".equals(mode)&&p.optBoolean("vectorize",false);
        if(ids.isEmpty()&&!vectorize){emit("notice","没有符合条件的待处理章节");return;}
        String vectorProvider=p.optString("vector_provider","siliconflow"),vectorModel=p.optString("vector_model","BAAI/bge-m3");
        if(vectorize&&!api.config(vectorProvider).getBoolean("has_key"))throw new IllegalStateException("请先在设置中保存在线嵌入渠道的 API Key，再开始总结与向量化");
        if(vectorize){vectorMemory.saveConfig(archiveId,true,vectorProvider,vectorModel);vectorMemory.saveSourceConfig(archiveId,true,vectorProvider,vectorModel);}
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
        beginTask();io.execute(()->{
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
                if(vectorize&&!cancelJob.get())try{
                    buildBothVectors(archiveId,vectorProvider,vectorModel);
                    emit("vectorConfig",vectorMemory.config(archiveId).put("source",vectorMemory.sourceConfig(archiveId)));
                }catch(Exception ex){failed++;error(ex);}
            }catch(Exception ex){error(ex);}finally{
                if(!"rewrite".equals(mode))try{store.rebuildOverview(archiveId);}catch(Exception ex){error(ex);}
                jobRunning=false;jobDetail="";getPreferences(MODE_PRIVATE).edit().putString("last_job_status",cancelJob.get()?"paused":failed>0?"failed":"completed").apply();
                if(ids.isEmpty()&&vectorize)emitVectorProgress(0,0,cancelJob.get()?"向量化已暂停，可续建":failed>0?"向量化失败；已保存部分可续建":"向量化已结束");
                else emitProgress(mode,done,ids.size(),failed,cancelJob.get()?"已暂停，可续建":"已结束");
                emitLastJob();updateStatusNotification();
                try{emit("jobDone",new JSONObject().put("archive_id",archiveId));}catch(Exception ignored){}
                endTask();
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
        if("vectorBuild".equals(payload.optString("mode")))startVectorJob(payload);else startJob(payload);
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
    private void generationImportPicker(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,IMPORT_GENERATION);}catch(Exception ex){error(ex);}}
    private void generationExportPicker(String name){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,name);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_GENERATION);}catch(Exception ex){error(ex);}}
    private void writingBooksPicker(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","text/markdown","application/epub+zip","application/octet-stream"});i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,IMPORT_WRITING_BOOKS);}catch(Exception ex){error(ex);emit("generationWritingCancelled",new JSONObject());}}
    private void writingProfileImportPicker(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,IMPORT_WRITING_PROFILE);}catch(Exception ex){error(ex);}}
    private void writingProfileExportPicker(String name){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,name);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_WRITING_PROFILE);}catch(Exception ex){error(ex);}}
    private static String decodeWritingBook(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();byte[] part=new byte[8192];int count;
        while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>20_000_000)throw new IllegalArgumentException("单本 TXT/Markdown 参考书超过 20 MB");}
        byte[] bytes=buffer.toByteArray();java.nio.charset.Charset charset=java.nio.charset.StandardCharsets.UTF_8;
        try{charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));}
        catch(java.nio.charset.CharacterCodingException error){charset=java.nio.charset.Charset.forName("GB18030");}
        String text=new String(bytes,charset);return text.startsWith("\uFEFF")?text.substring(1):text;
    }
    private void settingsImportPicker(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,IMPORT_SETTINGS);}catch(Exception ex){error(ex);}}
    private void settingsExportPicker(){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"帕朵一号设置.pardo-settings.json");i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,EXPORT_SETTINGS);}catch(Exception ex){error(ex);}}
    private void chatPresetImportPicker(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,IMPORT_CHAT_PRESET);}catch(Exception ex){error(ex);}}
    private void chatPresetExportPicker(){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"帕朵一号-对话预设.pardo-chat-preset.json");startActivityForResult(i,EXPORT_CHAT_PRESET);}catch(Exception ex){error(ex);}}
    private JSONObject exportSettingsBundle(boolean includeKeys,JSONObject appearance) throws Exception {
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        JSONObject globals=chatEngine.globals();
        globals.put("username",globals.optString("user_name","用户"));
        if(includeKeys)globals.put("brave_search_key",chatEngine.exportSearchKey());
        globals.put("summary_max_tokens",prefs.getInt("summary_max_tokens",4096));
        globals.put("summary_input_chars",prefs.getInt("summary_input_chars",6000));
        globals.put("rewrite_max_tokens",prefs.getInt("rewrite_max_tokens",8192));
        globals.put("rewrite_continue_calls",prefs.getInt("rewrite_continuations",1));
        globals.put("rewrite_budget_tokens",prefs.getInt("rewrite_budget",0));
        globals.put("special_tag_expand_prompt",prefs.getString("special_expand_prompt",""));
        globals.put("rewrite_expand_prompt",prefs.getString("rewrite_expand_prompt",""));
        globals.put("generation_expand_prompts",generationExpandPrompts());
        globals.put("generation_expand_limits",generationExpandLimits());
        return new JSONObject().put("format","pardo-settings-bundle").put("version",1)
            .put("contains_secrets",includeKeys).put("selected_provider",api.selected())
            .put("providers",api.exportProfiles(includeKeys)).put("globals",globals)
            .put("appearance",appearance==null?new JSONObject():appearance);
    }
    private JSONObject importSettingsBundle(JSONObject bundle,boolean allowKeys) throws Exception {
        if(!"pardo-settings-bundle".equals(bundle.optString("format"))||bundle.optInt("version")!=1)
            throw new IllegalArgumentException("不是帕朵一号设置配置文件 v1");
        JSONObject providers=bundle.optJSONObject("providers"),globals=bundle.optJSONObject("globals");
        if(providers==null||globals==null)throw new IllegalArgumentException("设置配置缺少渠道或全局配置");
        if((bundle.optBoolean("contains_secrets")||globals.has("brave_search_key"))&&!allowKeys)
            throw new IllegalArgumentException("文件包含密钥；请明确勾选允许导入密钥");
        if(!allowKeys)for(java.util.Iterator<String> keys=providers.keys();keys.hasNext();){
            JSONObject row=providers.optJSONObject(keys.next());
            if(row!=null&&row.has("api_key"))throw new IllegalArgumentException("文件包含 API Key；请明确勾选允许导入密钥");
        }
        for(String key:new String[]{"summary_max_tokens","summary_input_chars","rewrite_max_tokens","rewrite_continue_calls","rewrite_budget_tokens"})
            if(globals.has(key)&&!globals.optString(key,"").trim().isEmpty()&&(globals.optInt(key,-1)<0||globals.optInt(key,-1)>1000000))
                throw new IllegalArgumentException("设置数值超出范围："+key);
        for(String key:new String[]{"special_tag_expand_prompt","rewrite_expand_prompt","chat_prompt","roleplay_prompt","user_name","username"})
            if(globals.optString(key,"").length()>50000)throw new IllegalArgumentException("设置提示词过长："+key);
        for(java.util.Iterator<String> keys=providers.keys();keys.hasNext();){
            String provider=keys.next(),name=provider.toLowerCase(java.util.Locale.ROOT);
            if(!java.util.Arrays.asList("lmstudio","siliconflow","deepseek","openrouter","gemini","glm").contains(name)||providers.optJSONObject(provider)==null)
                throw new IllegalArgumentException("未知的 API 渠道配置："+provider);
            JSONObject row=providers.getJSONObject(provider);
            for(String key:new String[]{"base_url","chat_model","model","api_key"})if(row.optString(key,"").length()>50000)
                throw new IllegalArgumentException("渠道设置过长："+provider+"."+key);
        }
        String selected=bundle.optString("selected_provider","");
        if(!selected.isEmpty()&&!java.util.Arrays.asList("lmstudio","siliconflow","deepseek","openrouter","gemini","glm").contains(selected))
            throw new IllegalArgumentException("未知的默认 API 渠道");
        for(String key:new String[]{"generation_expand_prompts","generation_expand_limits"})if(globals.optJSONObject(key)!=null&&globals.getJSONObject(key).toString().length()>100000)
            throw new IllegalArgumentException("设置内容过长："+key);
        int count=api.importProfiles(providers,allowKeys);
        if(!selected.isEmpty())api.select(selected);
        JSONObject chat=new JSONObject();
        for(String key:new String[]{"chat_prompt","roleplay_prompt","user_name","web_search_enabled"})
            if(globals.has(key))chat.put(key,globals.get(key));
        if(!chat.has("user_name")&&globals.has("username"))chat.put("user_name",globals.getString("username"));
        if(allowKeys&&globals.has("brave_search_key"))chat.put("brave_search_key",globals.getString("brave_search_key"));
        chatEngine.saveGlobals(chat);
        android.content.SharedPreferences.Editor edit=getPreferences(MODE_PRIVATE).edit();
        for(String key:new String[]{"summary_max_tokens","summary_input_chars","rewrite_max_tokens","rewrite_continue_calls","rewrite_budget_tokens"}){
            if(!globals.has(key)||globals.optString(key,"").trim().isEmpty())continue;
            int value=globals.optInt(key,-1);
            if(value<0||value>1000000)throw new IllegalArgumentException("设置数值超出范围："+key);
            edit.putInt("rewrite_continue_calls".equals(key)?"rewrite_continuations":"rewrite_budget_tokens".equals(key)?"rewrite_budget":key,value);
        }
        for(String key:new String[]{"special_tag_expand_prompt","rewrite_expand_prompt"})
            if(globals.has(key)){String value=globals.optString(key,"");if(value.length()>50000)throw new IllegalArgumentException("提示词过长");edit.putString("special_tag_expand_prompt".equals(key)?"special_expand_prompt":key,value);}
        for(String key:new String[]{"generation_expand_prompts","generation_expand_limits"})
            if(globals.optJSONObject(key)!=null)edit.putString(key,globals.getJSONObject(key).toString());
        edit.apply();
        return new JSONObject().put("providers",count).put("appearance",bundle.optJSONObject("appearance"));
    }
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
    private static String validatedChatImage(String value){
        if(value==null||value.isEmpty())return "";
        if(value.length()>5_500_000)throw new IllegalArgumentException("图片超过 4 MB");
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^data:image/(jpeg|png|webp|gif);base64,([A-Za-z0-9+/]+={0,2})$").matcher(value);
        if(!match.matches())throw new IllegalArgumentException("仅支持 JPEG、PNG、WebP、GIF 图片");
        byte[] bytes=android.util.Base64.decode(match.group(2),android.util.Base64.DEFAULT);
        if(bytes.length==0||bytes.length>4_000_000)throw new IllegalArgumentException("图片超过 4 MB");
        String kind=match.group(1);
        boolean okay="jpeg".equals(kind)&&bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255
            ||"png".equals(kind)&&bytes.length>=8&&(bytes[0]&255)==137&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'
            ||"gif".equals(kind)&&bytes.length>=6&&bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F'
            ||"webp".equals(kind)&&bytes.length>=12&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='F'&&bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P';
        if(!okay)throw new IllegalArgumentException("图片内容与格式不匹配");
        return value;
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK_BACKGROUND){
            if(backgroundFileCallback!=null){backgroundFileCallback.onReceiveValue(resultCode==RESULT_OK&&data!=null&&data.getData()!=null?new Uri[]{data.getData()}:null);backgroundFileCallback=null;}
            return;
        }
        if(requestCode==IMPORT_WRITING_BOOKS){
            if(resultCode!=RESULT_OK||data==null){emit("generationWritingCancelled",new JSONObject());return;}
            ArrayList<Uri> selected=new ArrayList<>();ClipData clip=data.getClipData();
            if(clip!=null)for(int i=0;i<clip.getItemCount();i++)selected.add(clip.getItemAt(i).getUri());
            else if(data.getData()!=null)selected.add(data.getData());
            if(selected.isEmpty()){emit("generationWritingCancelled",new JSONObject());return;}
            if(selected.size()>5){error(new IllegalArgumentException("一次最多选择 5 本参考书"));return;}
            async(()->{
                emit("generationWritingProgress",new JSONObject().put("detail","正在本地抽样参考书…"));
                JSONArray books=new JSONArray();
                for(Uri selectedUri:selected){String name=filename(selectedUri),lower=name.toLowerCase(java.util.Locale.ROOT);String prose;
                    try(InputStream input=getContentResolver().openInputStream(selectedUri)){
                        if(lower.endsWith(".epub"))prose=EpubImport.extract(input);
                        else if(lower.endsWith(".txt")||lower.endsWith(".md"))prose=decodeWritingBook(input);
                        else throw new IllegalArgumentException("参考书仅支持 TXT、Markdown、EPUB："+name);
                    }
                    books.put(new JSONObject().put("name",name).put("text",WritingPromptProfiles.sample(prose)));
                }
                emit("generationWritingProgress",new JSONObject().put("detail","已抽样 "+books.length()+" 本；正在调用模型提炼…"));
                emit("generationWritingSynthesized",WritingPromptProfiles.synthesize(books,api));
            });return;
        }
        if(resultCode!=RESULT_OK||data==null||data.getData()==null){
            if(requestCode==EXPORT_KB||requestCode==EXPORT_REWRITES||requestCode==EXPORT_CARD||requestCode==EXPORT_TEMPLATE||requestCode==EXPORT_GENERATION||requestCode==EXPORT_SETTINGS||requestCode==EXPORT_CHAT_PRESET||requestCode==EXPORT_WRITING_PROFILE)emit("exportCancelled","已取消导出，未创建文件");
            return;
        }
        Uri uri=data.getData();
        if(requestCode==IMPORT_WRITING_PROFILE){async(()->{
            byte[] bytes;try(InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>40_000)throw new IllegalArgumentException("提示词配置超过 40 KB");}bytes=buffer.toByteArray();
            }
            writingProfiles.save(new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            emit("generationWritingProfiles",writingProfiles.list());emit("notice","写作提示词配置已导入");
        });}
        else if(requestCode==EXPORT_WRITING_PROFILE){String id=pendingWritingProfileId;async(()->{
            JSONObject portable=WritingPromptProfiles.portable(writingProfiles.get(id));
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){output.write(portable.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==IMPORT_CHAT_PRESET){long archiveId=pendingChatPresetArchive;async(()->{
            byte[] bytes;try(InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>500_000)throw new IllegalArgumentException("对话预设超过 500 KB");}bytes=buffer.toByteArray();
            }
            JSONObject bundle=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if(!"pardo-chat-preset".equals(bundle.optString("format"))||bundle.optInt("version")!=1||bundle.optJSONObject("preset")==null)throw new IllegalArgumentException("不是帕朵一号对话预设文件");
            chatData.savePreset(archiveId,bundle.getJSONObject("preset"));emit("notice","对话预设已导入所选知识库");
        });}
        else if(requestCode==EXPORT_CHAT_PRESET){long archiveId=pendingChatPresetArchive;async(()->{
            JSONObject bundle=new JSONObject().put("format","pardo-chat-preset").put("version",1).put("preset",chatData.preset(archiveId));
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){output.write(bundle.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==IMPORT_SETTINGS){boolean allowKeys=pendingSettingsSecrets;async(()->{
            byte[] bytes;
            try(InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>2_000_000)throw new IllegalArgumentException("设置文件超过 2 MB");}bytes=buffer.toByteArray();
            }
            emit("transferSettingsImported",importSettingsBundle(new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),allowKeys));
            emit("notice","设置配置已导入；原知识库与生成项目未修改");
        });}
        else if(requestCode==EXPORT_SETTINGS){boolean includeKeys=pendingSettingsSecrets;JSONObject appearance=pendingAppearance;async(()->{
            JSONObject bundle=exportSettingsBundle(includeKeys,appearance);
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){output.write(bundle.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==IMPORT_GENERATION){async(()->{
            byte[] bytes;
            try(InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                byte[] part=new byte[8192];int count;while((count=input.read(part))!=-1){buffer.write(part,0,count);if(buffer.size()>40_000_000)throw new IllegalArgumentException("生成项目文件超过 40 MB");}bytes=buffer.toByteArray();
            }
            JSONObject project=generation.importProject(bytes);
            emit("generationSelected",NovelGeneration.viewOf(project));emit("generationList",generation.list());emit("notice","生成项目已导入；原存档未修改");
        });}
        else if(requestCode==EXPORT_GENERATION){String id=pendingGenerationId;async(()->{
            JSONObject project=generation.read(id);
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")){output.write(project.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            emit("exported",new JSONObject().put("filename",filename(uri)).put("location","已保存到你在系统文件窗口选择的位置"));
        });}
        else if(requestCode==IMPORT_FILE){async(()->{
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
            profile.remove("id");
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
            card.remove("id");
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
