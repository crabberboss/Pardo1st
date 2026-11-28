package com.novelmemory.mobile;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent novel-generation projects. Controlled plot planning is a reserved, inactive interface. */
final class NovelGeneration {
    interface Events { void event(String type, JSONObject data); }
    private final File root;
    private final Api api;
    private final Events events;
    private final AtomicBoolean running=new AtomicBoolean(false),stop=new AtomicBoolean(false);
    private volatile String runningId="";

    NovelGeneration(Context context,Api api,Events events){
        root=new File(context.getFilesDir(),"novel_generation_v1");
        if(!root.exists()&&!root.mkdirs())throw new IllegalStateException("无法创建小说生成存档目录");
        this.api=api;this.events=events;
        File[] previous=root.listFiles((dir,name)->name.matches("[0-9a-f-]{36}\\.json"));
        if(previous!=null)for(File f:previous)try{
            JSONObject p=read(f.getName().substring(0,36));
            if("running".equals(p.optString("status"))){p.put("status","interrupted").put("detail","上次运行中断；可从检查点继续");save(p);}
        }catch(Exception ignored){}
    }
    // Future ControlledPlotPlanner interface belongs here. It is NOT enabled or called in v1.
    // It must first define a versioned contract and source-of-truth/conflict rules with the other project.
    private File file(String id){
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("无效生成项目 ID");
        return new File(root,id+".json");
    }
    synchronized JSONObject read(String id) throws Exception {
        File f=file(id);if(!f.isFile())throw new IllegalArgumentException("生成项目不存在");
        JSONObject project=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
        if(!project.has("protagonist_name")){
            JSONArray roles=project.optJSONArray("characters");String inferred=roles!=null&&roles.length()>0?roles.optJSONObject(0).optString("name"):"";
            project.put("protagonist_name",inferred.isEmpty()?"主角":inferred);
        }
        return project;
    }
    synchronized JSONObject view(String id) throws Exception {return viewOf(read(id));}
    static JSONObject viewOf(JSONObject p) throws Exception {
        JSONObject copy=new JSONObject(p.toString());JSONArray chapters=copy.getJSONArray("chapters");
        for(int i=0;i<chapters.length();i++){
            JSONObject chapter=chapters.getJSONObject(i),brief=new JSONObject(chapter.toString());
            brief.put("chars",chapter.optString("content").length());brief.remove("content");brief.remove("revision_history");chapters.put(i,brief);
        }
        if(copy.has("draft")){copy.put("draft_chars",copy.optString("draft").length());copy.remove("draft");}
        return copy;
    }
    synchronized JSONObject chapter(String id,int number) throws Exception {
        JSONArray chapters=read(id).getJSONArray("chapters");
        if(number<1||number>chapters.length())throw new IllegalArgumentException("章节不存在");
        return chapters.getJSONObject(number-1);
    }
    synchronized JSONObject importProject(byte[] bytes) throws Exception {
        if(bytes.length>40_000_000)throw new IllegalArgumentException("生成项目文件超过 40 MB");
        JSONObject p=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!"pardo-novel-generation".equals(p.optString("format"))||p.optInt("version")!=1)
            throw new IllegalArgumentException("不是兼容的帕朵一号小说生成项目 JSON");
        JSONArray chapters=p.optJSONArray("chapters");
        if(chapters==null||chapters.length()>10000)throw new IllegalArgumentException("生成项目章节格式无效");
        for(int i=0;i<chapters.length();i++)if(chapters.optJSONObject(i)==null||chapters.getJSONObject(i).optInt("number")!=i+1)
            throw new IllegalArgumentException("第 "+(i+1)+" 章的序号或格式有误");
        p.put("id",UUID.randomUUID().toString()).put("status","paused")
            .put("detail","已导入生成项目；原存档未修改").put("updated_at",System.currentTimeMillis());
        save(p);return p;
    }
    private static JSONObject completedChapter(JSONObject p,int number) throws Exception {
        JSONArray chapters=p.getJSONArray("chapters");
        if(number<1||number>chapters.length())throw new IllegalArgumentException("已完成章节不存在");
        return chapters.getJSONObject(number-1);
    }
    private static long revision(JSONObject chapter){return chapter.optLong("updated_at",chapter.optLong("accepted_at",0));}
    private static void checkRevision(JSONObject chapter,long expected){
        if(expected!=revision(chapter))throw new IllegalStateException("本章已在其他操作中改变，请重新打开章节后再修改");
    }
    private static void rememberRevision(JSONObject chapter) throws Exception {
        JSONArray old=chapter.optJSONArray("revision_history"),history=new JSONArray();
        if(old!=null)for(int i=Math.max(0,old.length()-2);i<old.length();i++)history.put(old.getJSONObject(i));
        JSONObject snapshot=new JSONObject();
        for(String field:new String[]{"title","outline","summary","content","cast","keywords","special_tags","actual_cast","updated_at","accepted_at"})
            if(chapter.has(field))snapshot.put(field,chapter.get(field));
        history.put(snapshot);chapter.put("revision_history",history);
    }
    private static void markMemoryStale(JSONObject p,JSONObject chapter,int number) throws Exception {
        chapter.put("memory_stale",true);
        int previous=p.optInt("memory_stale_from",0);
        if(previous==0||number<previous)p.put("memory_stale_from",number);
    }
    synchronized JSONObject saveCompletedChapter(String id,int number,long expected,JSONObject changes) throws Exception {
        if(running.get())throw new IllegalStateException("请先等待当前生成或重生成任务完成");
        JSONObject p=read(id),chapter=completedChapter(p,number);checkRevision(chapter,expected);
        String title=changes.optString("title",chapter.optString("title")).trim();
        String outline=changes.optString("outline",chapter.optString("outline")).trim();
        String content=changes.optString("content",chapter.optString("content")).trim();
        String summary=changes.optString("summary",chapter.optString("summary")).trim();
        if(title.isEmpty()||outline.isEmpty()||content.isEmpty())throw new IllegalArgumentException("标题、章纲和正文不能为空");
        if(title.length()>300||outline.length()>20000||content.length()>150000||summary.length()>1000)throw new IllegalArgumentException("本章编辑内容超出长度限制");
        boolean proseChanged=!content.equals(chapter.optString("content"));
        boolean changed=proseChanged||!title.equals(chapter.optString("title"))||!outline.equals(chapter.optString("outline"))||!summary.equals(chapter.optString("summary"));
        if(!changed)return p;
        rememberRevision(chapter);
        chapter.put("title",title).put("outline",outline).put("content",content).put("summary",summary)
            .put("updated_at",Math.max(System.currentTimeMillis(),expected+1));
        if(proseChanged)markMemoryStale(p,chapter,number);
        publish(p,"第 "+number+" 章修改已保存"+(proseChanged?"；结构化记忆需复核":""));
        return p;
    }
    synchronized JSONObject restoreCompletedChapter(String id,int number,long expected) throws Exception {
        if(running.get())throw new IllegalStateException("请先等待当前生成或重生成任务完成");
        JSONObject p=read(id),chapter=completedChapter(p,number);checkRevision(chapter,expected);
        JSONArray history=chapter.optJSONArray("revision_history");
        if(history==null||history.length()==0)throw new IllegalStateException("本章没有可恢复的上一版");
        JSONObject previous=history.getJSONObject(history.length()-1);JSONArray remaining=new JSONArray();
        for(int i=0;i<history.length()-1;i++)remaining.put(history.getJSONObject(i));
        JSONObject currentVersion=new JSONObject();
        for(String field:new String[]{"title","outline","summary","content","cast","keywords","special_tags","actual_cast","updated_at","accepted_at"})
            if(chapter.has(field))currentVersion.put(field,chapter.get(field));
        remaining.put(currentVersion);
        for(String field:new String[]{"title","outline","summary","content","cast","keywords","special_tags","actual_cast"})
            if(previous.has(field))chapter.put(field,previous.get(field));else chapter.remove(field);
        chapter.put("revision_history",remaining).put("updated_at",Math.max(System.currentTimeMillis(),expected+1));
        markMemoryStale(p,chapter,number);
        publish(p,"第 "+number+" 章已恢复上一版；结构化记忆仍需复核");
        return p;
    }
    synchronized void save(JSONObject project) throws Exception {
        File f=file(project.getString("id"));AtomicFile atomic=new AtomicFile(f);FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(project.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}
        catch(Exception ex){if(out!=null)atomic.failWrite(out);throw ex;}
    }
    synchronized JSONArray list() throws Exception {
        JSONArray result=new JSONArray();File[] files=root.listFiles((dir,name)->name.matches("[0-9a-f-]{36}\\.json"));if(files==null)return result;
        java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File f:files)try{JSONObject p=read(f.getName().substring(0,36));result.put(new JSONObject().put("id",p.getString("id"))
                .put("name",p.optString("name")).put("status",p.optString("status"))
                .put("chapters",p.getJSONArray("chapters").length()).put("updated_at",p.optLong("updated_at")));}catch(Exception ignored){}
        return result;
    }
    synchronized JSONObject create(JSONObject input) throws Exception {
        String name=input.optString("name","").trim(),protagonistName=input.optString("protagonist_name","").trim();
        if(name.isEmpty()||protagonistName.isEmpty())throw new IllegalArgumentException("小说名和主角名字均须填写");
        String outline=input.optString("outline","").trim();
        JSONObject p=new JSONObject().put("format","pardo-novel-generation").put("version",1)
            .put("id",UUID.randomUUID().toString()).put("name",name).put("outline",outline)
            .put("world",input.optString("world","")).put("system",input.optString("system",""))
            .put("protagonist_name",protagonistName).put("protagonist",input.optString("protagonist",protagonistName)).put("style",input.optString("style",""))
            .put("generation_prompt",input.optString("generation_prompt",""))
            .put("chapter_chars",clamp(input.optInt("chapter_chars",2200),600,5000))
            .put("output_tokens",clamp(input.optInt("output_tokens",4096),1024,16384))
            .put("max_retries",clamp(input.optInt("max_retries",3),1,10))
            .put("auto_continue",false)
            .put("cast_mode",input.optString("cast_mode","auto"))
            .put("strict_state",input.optBoolean("strict_state",false))
            .put("status","ready").put("stage","plan").put("chapters",new JSONArray())
            .put("characters",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString())
                .put("name",protagonistName).put("state","主角，尚未登场").put("status","active").put("last_chapter",0).put("pinned",true)))
            .put("threads",new JSONArray()).put("world_state",new JSONArray())
            .put("input_tokens",0).put("output_tokens_used",0).put("updated_at",System.currentTimeMillis());
        save(p);return p;
    }
    synchronized JSONObject update(String id,JSONObject changes) throws Exception {
        if(id.equals(runningId))throw new IllegalStateException("请先暂停当前生成任务再编辑");
        JSONObject p=read(id);
        String previousName=p.optString("protagonist_name"),nextName=changes.optString("protagonist_name",previousName).trim();
        if(!nextName.equals(previousName)){
            if(p.getJSONArray("chapters").length()>0)throw new IllegalStateException("已生成正文后不能直接改主角名字，以免角色记忆错位");
            JSONArray roles=p.getJSONArray("characters");
            for(int i=0;i<roles.length();i++)if(previousName.equals(roles.getJSONObject(i).optString("name")))roles.getJSONObject(i).put("name",nextName);
            if(previousName.equals(p.optString("protagonist"))&&!changes.has("protagonist"))p.put("protagonist",nextName);
        }
        for(String key:new String[]{"name","outline","world","system","protagonist_name","protagonist","style","generation_prompt","cast_mode"})
            if(changes.has(key))p.put(key,changes.optString(key,""));
        if(p.optString("name").trim().isEmpty()||p.optString("protagonist_name").trim().isEmpty())throw new IllegalArgumentException("小说名和主角名字不能为空");
        if("setup".equals(p.optString("status")))p.put("status","paused");
        if(changes.has("chapter_chars"))p.put("chapter_chars",clamp(changes.optInt("chapter_chars"),600,5000));
        if(changes.has("output_tokens"))p.put("output_tokens",clamp(changes.optInt("output_tokens"),1024,16384));
        p.remove("token_budget"); // Legacy projects remain readable, but no longer have a lifetime cap.
        if(changes.has("max_retries"))p.put("max_retries",clamp(changes.optInt("max_retries"),1,10));
        if(changes.has("strict_state"))p.put("strict_state",changes.optBoolean("strict_state"));
        if(changes.has("auto_continue"))p.put("auto_continue",changes.optBoolean("auto_continue"));
        if(changes.has("characters"))p.put("characters",changes.getJSONArray("characters"));
        if(changes.has("threads"))p.put("threads",changes.getJSONArray("threads"));
        if(changes.has("world_state"))p.put("world_state",changes.getJSONArray("world_state"));
        if(changes.has("pending")){
            p.put("pending",changes.getJSONObject("pending"));p.put("stage","write");p.put("status","paused");
        }
        p.put("stage_attempts",0).remove("error");p.put("updated_at",System.currentTimeMillis());save(p);return p;
    }
    synchronized void delete(String id) throws Exception {
        if(id.equals(runningId))throw new IllegalStateException("请先暂停当前生成任务再删除");
        File f=file(id);if(!f.isFile())throw new IllegalArgumentException("生成项目不存在");
        if(!f.delete())throw new IllegalStateException("生成项目删除失败");
    }
    synchronized JSONObject finish(String id) throws Exception {
        if(id.equals(runningId))throw new IllegalStateException("请先暂停生成任务");
        JSONObject p=read(id);if(p.getJSONArray("chapters").length()==0)throw new IllegalStateException("尚无正文，不能标记完结");
        p.put("status","completed").put("stage","done");publish(p,"用户确认全书完结");return p;
    }
    synchronized JSONObject continueAfterEnding(String id) throws Exception {
        if(id.equals(runningId))throw new IllegalStateException("当前任务仍在运行");
        JSONObject p=read(id);if(!"ending_review".equals(p.optString("status")))throw new IllegalStateException("项目不处于结局确认阶段");
        p.put("status","paused").put("stage","plan").put("force_continue_once",true);publish(p,"已选择继续写作");return p;
    }
    void pause(){stop.set(true);}
    boolean isRunning(){return running.get();}
    String activeProjectId(){return runningId;}
    private static int clamp(int value,int low,int high){return Math.max(low,Math.min(high,value));}
    private void publish(JSONObject p,String detail) throws Exception {
        p.put("detail",detail).put("updated_at",System.currentTimeMillis());save(p);
        events.event("generationProject",viewOf(p));
    }
    private void account(JSONObject p,Api.ChatResult result,int estimatedInput) throws Exception {
        if(result.inputTokens<=0||result.outputTokens<=0)p.put("usage_estimated",true);
        p.put("input_tokens",p.optInt("input_tokens")+Math.max(1,result.inputTokens>0?result.inputTokens:estimatedInput));
        p.put("output_tokens_used",p.optInt("output_tokens_used")+Math.max(1,result.outputTokens>0?result.outputTokens:(result.content.length()+1)/2));
        save(p);
    }
    private void accountEmpty(JSONObject p,Api.EmptyContentException empty,int estimatedInput) throws Exception {
        if(empty.inputTokens<=0||empty.outputTokens<=0)p.put("usage_estimated",true);
        p.put("input_tokens",p.optInt("input_tokens")+Math.max(1,empty.inputTokens>0?empty.inputTokens:estimatedInput));
        p.put("output_tokens_used",p.optInt("output_tokens_used")+Math.max(1,empty.outputTokens>0?empty.outputTokens:(empty.reasoningChars+1)/2));
        save(p);
    }
    private static JSONArray messages(String system,String user) throws Exception {
        return new JSONArray().put(new JSONObject().put("role","system").put("content",system))
            .put(new JSONObject().put("role","user").put("content",user));
    }
    static JSONObject object(String raw) throws Exception {
        String text=raw.trim();int start=text.indexOf('{'),end=text.lastIndexOf('}');
        if(start<0||end<start)throw new IllegalStateException("模型没有返回 JSON 对象");
        return new JSONObject(text.substring(start,end+1));
    }
    private static String shortText(String text,int max){return text.length()>max?text.substring(0,max):text;}
    static String context(JSONObject p,boolean writer) throws Exception {
        JSONArray chapters=p.getJSONArray("chapters"),recent=new JSONArray();
        for(int i=Math.max(0,chapters.length()-4);i<chapters.length();i++){
            JSONObject c=chapters.getJSONObject(i);recent.put(new JSONObject().put("number",c.optInt("number"))
                .put("title",c.optString("title")).put("summary",shortText(c.optString("summary"),600)));
        }
        JSONArray hot=new JSONArray(),characters=p.getJSONArray("characters");int next=chapters.length()+1;
        for(int i=0;i<characters.length();i++){
            JSONObject c=characters.getJSONObject(i);int heat="dead".equals(c.optString("status"))?0:Math.max(0,100-5*(next-1-c.optInt("last_chapter",0)));
            if(heat>0||c.optBoolean("pinned"))hot.put(new JSONObject().put("name",c.optString("name"))
                .put("status",c.optString("status")).put("heat",heat).put("state",shortText(c.optString("state"),350)));
        }
        JSONArray visibleThreads=new JSONArray(),threads=p.getJSONArray("threads");
        for(int i=0;i<threads.length();i++){JSONObject t=threads.optJSONObject(i);if(t!=null&&(!writer||!"hidden".equals(t.optString("visibility"))))visibleThreads.put(t);}
        return (writer?"本章只依据已确认章纲与当前记忆写作；不得引用全书方向参考。":"以近期剧情、人物状态和未完事件线为主要规划依据。")
            +(p.optInt("memory_stale_from",0)>0?"\n注意：第 "+p.optInt("memory_stale_from")+" 章起曾修改正文；角色、事件和世界状态可能未同步。已保存的章节正文与梗概优先，结构化记忆仅作待核对线索。":"")
            +"\n世界观："+shortText(p.optString("world"),1800)
            +"\n系统规则："+shortText(p.optString("system"),1800)+"\n主角姓名："+p.optString("protagonist_name")+"\n主角："+shortText(p.optString("protagonist"),1200)
            +"\n文风："+shortText(p.optString("style"),700)+"\n全局生成要求："+shortText(p.optString("generation_prompt"),1200)+"\n近期章节："+recent
            +"\n相关角色："+shortText(hot.toString(),3500)+"\n事件线："+shortText(visibleThreads.toString(),3000)
            +"\n动态世界状态："+shortText(p.getJSONArray("world_state").toString(),1600)
            +(writer?"":"\n全书方向参考（可选、低优先级，允许随已生成剧情调整）："+shortText(p.optString("outline"),1000));
    }
    private Api.ChatResult call(JSONObject p,JSONArray prompt,int tokens) throws Exception {
        int estimatedInput=(prompt.toString().length()+1)/2;
        Api.ChatResult result;
        try{result=api.chatDetailed(prompt,tokens,240,null,true);}
        catch(Api.EmptyContentException empty){
            accountEmpty(p,empty,estimatedInput);
            int larger=Math.min(16384,Math.max(tokens+1024,tokens*2));
            if(larger==tokens)throw new IllegalStateException("模型仅返回思考过程，已达 16384 Token 上限；请改用非推理模型或调整模型服务的输出限制",empty);
            try{result=api.chatDetailed(prompt,larger,240,null,true);}
            catch(Api.EmptyContentException second){
                accountEmpty(p,second,estimatedInput);
                throw new IllegalStateException("模型仅返回思考过程，自动提高到 "+larger+" Token 后仍无正式内容；当前模型可能不支持关闭思考，请在 API 设置中选择非推理模型。"+second.getMessage(),second);
            }
        }
        account(p,result,estimatedInput);
        if(result.truncated)throw new IllegalStateException("模型输出截断；本次内容未作为完成稿保存。请提高输出上限或缩短单章目标");
        return result;
    }
    private void plan(JSONObject p) throws Exception {
        int n=p.getJSONArray("chapters").length()+1;
        publish(p,"规划第 "+n+" 章中");
        String system="你是长篇小说章节规划器。只输出 JSON：{\"end\":false,\"title\":\"\",\"outline\":\"\",\"cast\":[\"角色名\"],\"viewpoint\":\"\",\"keywords\":[\"\"],\"special_tags\":[\"\"]}。优先依据已生成章节、角色状态与未完事件线规划下一章；全书方向参考仅作弱提示，允许剧情自然发展，没有参考时也要正常规划。不要提前泄露暗线。当前主要冲突已收束且故事自然结束时才 end=true。";
        boolean force=p.optBoolean("force_continue_once");
        JSONObject proposed=object(call(p,messages(system,(force?"用户要求继续写一章；本次 end 必须为 false。\n":"")+"下一章序号："+n+"\n"+context(p,false)),1400).content);
        if(proposed.optBoolean("end",false)){
            if(n==1||force)throw new IllegalStateException("故事尚需继续，但规划器提前宣告结束");
            boolean open=false;JSONArray threads=p.getJSONArray("threads");
            for(int i=0;i<threads.length();i++)if("active".equals(threads.getJSONObject(i).optString("status")))open=true;
            if(open){p.put("status","ending_review");publish(p,"模型建议完结，但仍有进行中事件线；请确认完结或继续写作");}
            else{p.put("status","completed").put("stage","done");publish(p,"全书已完成，共 "+(n-1)+" 章");}
            return;
        }
        if(proposed.optString("outline").trim().isEmpty())throw new IllegalStateException("章纲为空，规划暂停");
        p.remove("force_continue_once");
        proposed.put("number",n);p.put("pending",proposed).put("stage","write");
        if("manual".equals(p.optString("cast_mode"))){p.put("status","waiting_cast");publish(p,"第 "+n+" 章等待确认出场角色");}
        else publish(p,"第 "+n+" 章章纲已保存，准备生成正文");
    }
    private void write(JSONObject p) throws Exception {
        JSONObject planned=p.getJSONObject("pending");int n=planned.getInt("number");
        publish(p,"生成第 "+n+" 章中");
        String system="你是长篇小说作者。遵守章纲、人物当前状态和世界规则。本章出场角色以章纲 cast 为准；不要无故引入其他关键人物。只输出本章小说正文，不要元说明，不要跳到后续章节；暗线未揭露真相不可直接写出。避免重复段落。";
        String user="本章计划："+planned+"\n目标约 "+p.optInt("chapter_chars")+" 个中文字符。\n参考材料：\n"+context(p,true);
        String content=call(p,messages(system,user),p.optInt("output_tokens")).content.trim();
        if(content.length()<Math.min(400,p.optInt("chapter_chars")/3))throw new IllegalStateException("正文过短，未保存为完成稿");
        if(MainActivity.hasLongRepeatedParagraph(content))throw new IllegalStateException("检测到重复段落，未保存为完成稿");
        p.put("draft",content).put("stage","extract");publish(p,"第 "+n+" 章正文已暂存，提取记忆中");
    }
    private static String regenerationContext(JSONObject p,int number) throws Exception {
        JSONArray chapters=p.getJSONArray("chapters"),recent=new JSONArray();
        for(int i=Math.max(0,number-5);i<number-1;i++){
            JSONObject previous=chapters.getJSONObject(i);
            recent.put(new JSONObject().put("number",i+1).put("title",previous.optString("title"))
                .put("summary",shortText(previous.optString("summary"),500)));
        }
        String preceding="";
        if(number>1){String content=chapters.getJSONObject(number-2).optString("content");preceding=content.substring(Math.max(0,content.length()-1800));}
        return "世界观："+shortText(p.optString("world"),1800)
            +"\n系统规则："+shortText(p.optString("system"),1800)
            +"\n主角："+p.optString("protagonist_name")+"；"+shortText(p.optString("protagonist"),1200)
            +"\n文风："+shortText(p.optString("style"),700)
            +"\n全局生成要求："+shortText(p.optString("generation_prompt"),1200)
            +"\n仅限本章之前的剧情梗概："+recent
            +"\n上一章结尾："+preceding;
    }
    void regenerateCompletedChapter(String id,int number,long expected) throws Exception {
        if(!running.compareAndSet(false,true))throw new IllegalStateException("已有小说生成任务在运行");
        runningId=id;stop.set(false);JSONObject p=null;String originalStatus="";
        try{
            p=read(id);JSONObject chapter=completedChapter(p,number);checkRevision(chapter,expected);
            originalStatus=p.optString("status","paused");
            p.put("status","regenerating");publish(p,"根据章纲重新生成第 "+number+" 章正文中；原稿暂不覆盖");
            JSONObject plan=new JSONObject().put("number",number).put("title",chapter.optString("title"))
                .put("outline",chapter.optString("outline")).put("cast",chapter.optJSONArray("cast"))
                .put("viewpoint",chapter.optString("viewpoint"))
                .put("keywords",chapter.optJSONArray("keywords"))
                .put("special_tags",chapter.optJSONArray("special_tags"));
            String system="你是长篇小说作者。只依据本章章纲和此前剧情重新生成完整的第 "+number+" 章正文。不得使用本章旧稿或后续章节设定，不要改变已确认的出场角色。只输出小说正文，不要说明，不要重复段落。";
            String user="本章计划："+plan+"\n目标约 "+p.optInt("chapter_chars",2200)+" 个中文字符。\n此前材料：\n"+regenerationContext(p,number);
            String replacement=call(p,messages(system,user),p.optInt("output_tokens",4096)).content.trim();
            if(replacement.length()<Math.min(400,p.optInt("chapter_chars",2200)/3))throw new IllegalStateException("重生成正文过短；旧稿未覆盖");
            if(MainActivity.hasLongRepeatedParagraph(replacement))throw new IllegalStateException("重生成正文有重复段落；旧稿未覆盖");
            if(stop.get())throw new IllegalStateException("已暂停；旧稿未覆盖");
            rememberRevision(chapter);
            chapter.put("content",replacement).put("summary","").put("updated_at",Math.max(System.currentTimeMillis(),expected+1))
                .put("last_regenerated_at",System.currentTimeMillis());
            markMemoryStale(p,chapter,number);
            p.put("status",originalStatus).remove("error");publish(p,"第 "+number+" 章已重新生成；请核对梗概及结构化记忆");
        }catch(Exception ex){
            if(p!=null)try{p.put("status",originalStatus);publish(p,"第 "+number+" 章重生成失败；原稿未覆盖："+ex.getMessage());}catch(Exception ignored){}
            throw ex;
        }finally{runningId="";running.set(false);}
    }
    private static void merge(JSONArray target,JSONArray updates,String type,int number) throws Exception {
        for(int i=0;i<updates.length();i++){
            JSONObject row=updates.optJSONObject(i);if(row==null)continue;String name=row.optString("name","").trim();if(name.isEmpty())continue;
            JSONObject item=null;
            for(int j=0;j<target.length();j++)if(name.equals(target.getJSONObject(j).optString("name"))){item=target.getJSONObject(j);break;}
            if(item==null){item=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name);target.put(item);}
            if("character".equals(type)){
                item.put("state",shortText(row.optString("state",item.optString("state")),700));
                if(row.optBoolean("appeared",false))item.put("last_chapter",number);
                if(row.optBoolean("died",false))item.put("status","dead");
                else if(!item.has("status"))item.put("status","active");
                for(String field:new String[]{"abilities","stats","items"})if(row.optJSONArray(field)!=null){
                    JSONArray existing=item.optJSONArray(field);if(existing==null)existing=new JSONArray();
                    JSONArray changes=row.getJSONArray(field);
                    for(int x=0;x<changes.length();x++){
                        JSONObject change=changes.optJSONObject(x);if(change==null||change.optString("name").trim().isEmpty())continue;
                        int position=-1;for(int y=0;y<existing.length();y++)if(change.optString("name").equals(existing.getJSONObject(y).optString("name"))){position=y;break;}
                        JSONObject value=new JSONObject(change.toString()).put("source_chapter",number);
                        if(position>=0)existing.put(position,value);else existing.put(value);
                    }
                    item.put(field,existing);
                }
            }else{
                item.put("state",shortText(row.optString("state",item.optString("state")),700));
                if("thread".equals(type)){
                    item.put("status",row.optString("status",item.optString("status","active")));
                    item.put("visibility",row.optString("visibility",item.optString("visibility","public")));
                    JSONArray history=item.optJSONArray("history");if(history==null)history=new JSONArray();
                    history.put(new JSONObject().put("chapter",number).put("progress",shortText(row.optString("state"),160)));
                    item.put("history",history);
                }
            }
            item.put("source_chapter",number);
        }
    }
    private void extract(JSONObject p) throws Exception {
        JSONObject planned=p.getJSONObject("pending");int n=planned.getInt("number");
        publish(p,"校验与更新第 "+n+" 章记忆中");
        String system="仅依据正文明确写出的内容，提取结构化增量；不要猜测未出现事实。只输出 JSON：{\"summary\":\"不超过80字\",\"characters\":[{\"name\":\"\",\"state\":\"目标/位置简述\",\"appeared\":true,\"died\":false,\"abilities\":[{\"name\":\"技能\",\"state\":\"当前等级或效果\",\"evidence\":\"正文短引文\"}],\"stats\":[{\"name\":\"数值名\",\"value\":1,\"unit\":\"\",\"evidence\":\"正文短引文\"}],\"items\":[{\"name\":\"物品\",\"quantity\":1,\"evidence\":\"正文短引文\"}]}],\"threads\":[{\"name\":\"事件线名称\",\"state\":\"本章进展\",\"status\":\"active|paused|resolved\",\"visibility\":\"public|hidden\"}],\"world_state\":[{\"name\":\"状态名\",\"state\":\"最新明确状态\"}]}。没有明确变化的字段使用空数组；数值和物品为章节结束时的当前值，不能猜测。死亡仅在正文明确确认时标记。";
        JSONObject changes=object(call(p,messages(system,"第 "+n+" 章正文：\n"+p.getString("draft")
            +"\n已有相关状态：\n"+shortText(p.getJSONArray("characters").toString(),2000)
            +"\n"+shortText(p.getJSONArray("threads").toString(),1500)),1800).content);
        String summary=changes.optString("summary","").trim();if(summary.isEmpty())throw new IllegalStateException("记忆提取缺少章节梗概；正文草稿已保存，可续建");
        validateStateChanges(p,changes);
        JSONObject chapter=new JSONObject(planned.toString()).put("content",p.getString("draft"))
            .put("summary",shortText(summary,160)).put("accepted_at",System.currentTimeMillis());
        JSONArray actualCast=new JSONArray(),detected=changes.optJSONArray("characters");
        if(detected!=null)for(int i=0;i<detected.length();i++){
            JSONObject character=detected.optJSONObject(i);
            if(character!=null&&character.optBoolean("appeared")&&!character.optString("name").isEmpty())actualCast.put(character.optString("name"));
        }
        chapter.put("actual_cast",actualCast.length()>0?actualCast:planned.optJSONArray("cast"));
        JSONArray chapters=p.getJSONArray("chapters");chapters.put(chapter);
        merge(p.getJSONArray("characters"),changes.optJSONArray("characters")==null?new JSONArray():changes.getJSONArray("characters"),"character",n);
        merge(p.getJSONArray("threads"),changes.optJSONArray("threads")==null?new JSONArray():changes.getJSONArray("threads"),"thread",n);
        merge(p.getJSONArray("world_state"),changes.optJSONArray("world_state")==null?new JSONArray():changes.getJSONArray("world_state"),"world",n);
        p.remove("draft");p.remove("pending");p.put("stage","plan");p.put("status","running");
        publish(p,"第 "+n+" 章已保存；准备规划下一章");
    }
    static void validateStateChanges(JSONObject project,JSONObject changes) throws Exception {
        JSONArray characters=changes.optJSONArray("characters");if(characters==null)return;
        String prose=project.optString("draft");boolean strict=project.optBoolean("strict_state");
        for(int i=0;i<characters.length();i++){
            JSONObject character=characters.optJSONObject(i);if(character==null)continue;
            for(String field:new String[]{"abilities","stats","items"}){
                JSONArray rows=character.optJSONArray(field);if(rows==null)continue;
                for(int j=0;j<rows.length();j++){
                    JSONObject row=rows.optJSONObject(j);if(row==null)continue;
                    if("items".equals(field)&&row.has("quantity")&&row.optDouble("quantity",0)<0)throw new IllegalStateException("物品数量不能为负："+row.optString("name"));
                    if(strict){String evidence=row.optString("evidence","").trim();
                        if(evidence.length()<4||!prose.contains(evidence))throw new IllegalStateException("严格记账："+row.optString("name")+" 缺少正文原句依据；草稿保留待复核");}
                }
            }
        }
    }
    void run(String id) throws Exception {run(id,false);}
    void run(String id,boolean autoContinue) throws Exception {
        if(!running.compareAndSet(false,true))throw new IllegalStateException("已有小说生成任务在运行");
        runningId=id;stop.set(false);
        try{
            JSONObject p=read(id);if("completed".equals(p.optString("status")))throw new IllegalStateException("此项目已经完结");
            if("waiting_cast".equals(p.optString("status")))throw new IllegalStateException("请先确认本章出场角色并保存章纲");
            int chaptersBefore=p.getJSONArray("chapters").length();
            p.put("auto_continue",autoContinue).put("status","running");publish(p,"准备继续生成");
            for(int steps=0;steps<3000&&!stop.get();steps++){
                String stage=p.optString("stage","plan");
                try{
                    if("plan".equals(stage))plan(p);
                    else if("write".equals(stage))write(p);
                    else if("extract".equals(stage))extract(p);
                    else break;
                    p.put("stage_attempts",0);p.remove("error");save(p);
                }catch(Exception stageError){
                    int attempts=p.optInt("stage_attempts",0)+1;p.put("stage_attempts",attempts);
                    String reason=stageError.getMessage()==null?stageError.toString():stageError.getMessage();
                    p.put("error",reason);publish(p,"第 "+(p.getJSONArray("chapters").length()+1)+" 章 "+stage+" 失败 "+attempts+"/"+p.optInt("max_retries",3)+"："+reason);
                    boolean permanent=reason.contains("截断")||reason.contains("HTTP 400")||reason.contains("HTTP 401")||reason.contains("HTTP 403")||reason.contains("模型名称")||reason.contains("已达 16384 Token 上限")||reason.contains("仍无正式内容");
                    if(permanent||attempts>=p.optInt("max_retries",3))throw stageError;
                    if(stop.get())break;
                    Thread.sleep(Math.min(5000,attempts*1000));continue;
                }
                if("waiting_cast".equals(p.optString("status"))||"completed".equals(p.optString("status"))||"ending_review".equals(p.optString("status")))break;
                if(!autoContinue&&p.getJSONArray("chapters").length()>chaptersBefore){
                    p.put("status","paused");publish(p,"第 "+p.getJSONArray("chapters").length()+" 章已完成；单章模式已停止");break;
                }
            }
            if(stop.get()){p.put("status","paused");publish(p,"已暂停；下次从已保存阶段继续");}
        }catch(Exception ex){
            JSONObject p=read(id);
            if(!"completed".equals(p.optString("status"))&&!"waiting_cast".equals(p.optString("status"))&&!"setup".equals(p.optString("status"))){
                p.put("status","failed").put("error",ex.getMessage()==null?ex.toString():ex.getMessage());
                publish(p,"已暂停："+p.optString("error"));
            }
            throw ex;
        }finally{runningId="";running.set(false);}
    }
}
