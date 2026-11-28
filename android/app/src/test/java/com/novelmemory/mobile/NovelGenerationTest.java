package com.novelmemory.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class NovelGenerationTest {
    private NovelGeneration store(Context context){return new NovelGeneration(context,new Api(context),(type,data)->{});}

    @Test public void projectIsIndependentAndPersistsPendingPlan() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject first=generation.create(new JSONObject().put("name","第一本").put("protagonist_name","甲").put("outline","主角寻找失踪的人"));
        JSONObject second=generation.create(new JSONObject().put("name","第二本").put("protagonist_name","乙").put("outline","另一故事"));
        assertNotEquals(first.getString("id"),second.getString("id"));
        JSONObject plan=new JSONObject().put("number",1).put("title","开端")
            .put("outline","主角出发").put("cast",new JSONArray().put("主角"));
        generation.update(first.getString("id"),new JSONObject().put("pending",plan));
        assertEquals("write",store(context).read(first.getString("id")).getString("stage"));
        assertFalse(generation.read(second.getString("id")).has("pending"));
        generation.delete(first.getString("id"));
        assertEquals("第二本",generation.read(second.getString("id")).getString("name"));
        generation.delete(second.getString("id"));
    }

    @Test public void unifiedGenerationPromptIsPerProjectAndExportable() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject first=generation.create(new JSONObject().put("name","提示词甲").put("protagonist_name","甲"));
        JSONObject second=generation.create(new JSONObject().put("name","提示词乙").put("protagonist_name","乙"));
        try{
            generation.update(first.getString("id"),new JSONObject().put("generation_prompt","每章保持第三人称叙述"));
            assertEquals("每章保持第三人称叙述",generation.view(first.getString("id")).getString("generation_prompt"));
            assertEquals("",generation.view(second.getString("id")).getString("generation_prompt"));
            assertEquals("每章保持第三人称叙述",generation.read(first.getString("id")).getString("generation_prompt"));
        }finally{
            generation.delete(first.getString("id"));generation.delete(second.getString("id"));
        }
    }
    @Test public void writingPromptConfigIsPortableAndOmitsBookText() throws Exception {
        Context context=RuntimeEnvironment.getApplication();WritingPromptProfiles profiles=new WritingPromptProfiles(context);
        JSONObject saved=profiles.save(new JSONObject().put("format","pardo-writing-prompt").put("version",1)
            .put("name","文风配置").put("style_prompt","多用短句").put("unified_prompt","保持章节连贯")
            .put("source_names",new JSONArray().put("样书.epub")).put("raw_book","不能保存的正文"));
        JSONObject loaded=profiles.get(saved.getString("id"));
        assertEquals("保持章节连贯",loaded.getString("unified_prompt"));
        assertFalse(loaded.has("raw_book"));
        assertFalse(WritingPromptProfiles.portable(loaded).has("id"));
        assertEquals(2340,WritingPromptProfiles.sample("甲乙丙丁".repeat(3000)).replace("\n\n【抽样片段】\n","").length());
    }

    @Test public void viewOmitsLongProseAndParserAcceptsFencedJson() throws Exception {
        JSONObject p=new JSONObject().put("chapters",new JSONArray().put(new JSONObject()
            .put("number",1).put("title","章一").put("content","正文内容")));
        JSONObject view=NovelGeneration.viewOf(p);
        assertEquals(4,view.getJSONArray("chapters").getJSONObject(0).getInt("chars"));
        assertFalse(view.getJSONArray("chapters").getJSONObject(0).has("content"));
        assertEquals("正文内容",p.getJSONArray("chapters").getJSONObject(0).getString("content"));
        assertEquals("甲",NovelGeneration.object("```json\n{\"name\":\"甲\"}\n```").getString("name"));
    }
    @Test public void strictStateRequiresProseEvidenceAndRejectsNegativeItems() throws Exception {
        JSONObject project=new JSONObject().put("draft","主角从箱中取得一枚银币。")
            .put("strict_state",true);
        JSONObject item=new JSONObject().put("name","银币").put("quantity",1)
            .put("evidence","取得一枚银币");
        JSONObject character=new JSONObject().put("name","主角").put("items",new JSONArray().put(item));
        JSONObject changes=new JSONObject().put("characters",new JSONArray().put(character));
        NovelGeneration.validateStateChanges(project,changes);
        item.put("evidence","模型猜测");
        try{NovelGeneration.validateStateChanges(project,changes);fail("缺少正文依据必须失败");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("正文原句依据"));}
        item.put("evidence","取得一枚银币").put("quantity",-1);
        try{NovelGeneration.validateStateChanges(project,changes);fail("负数物品必须失败");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("不能为负"));}
    }
    @Test public void autoLoopPlansWritesExtractsAndFinishesThroughLocalApi() throws Exception {
        Context context=RuntimeEnvironment.getApplication();AtomicInteger calls=new AtomicInteger();
        ServerSocket server=new ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{try{
            for(int request=0;request<8;request++)try(Socket socket=server.accept()){
                java.io.InputStream input=socket.getInputStream();java.io.ByteArrayOutputStream header=new java.io.ByteArrayOutputStream();
                int byteValue;String head="";while((byteValue=input.read())>=0){header.write(byteValue);
                    if(header.size()>=4){byte[] h=header.toByteArray();int z=h.length;if(h[z-4]==13&&h[z-3]==10&&h[z-2]==13&&h[z-1]==10)break;}}
                head=header.toString("US-ASCII");int length=0;
                for(String line:head.split("\\r\\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                byte[] buffer=new byte[4096];for(int left=length;left>0;){int read=input.read(buffer,0,Math.min(left,buffer.length));if(read<0)break;left-=read;}
                int index=calls.incrementAndGet();String answer;
                if(index==1)answer="";
                else if(index==2||index==5)answer="{\"end\":false,\"title\":\""+(index==2?"开端":"续章")+"\",\"outline\":\"主角出发\",\"cast\":[\"主角\"],\"keywords\":[\"出发\"],\"special_tags\":[]}";
                else if(index==3||index==6)answer="正文".repeat(250);
                else if(index==4||index==7)answer="{\"summary\":\"主角出发。\",\"characters\":[{\"name\":\"主角\",\"state\":\"已出发\",\"appeared\":true,\"died\":false}],\"threads\":[],\"world_state\":[]}";
                else answer="{\"end\":true}";
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop")
                    .put("message",new JSONObject().put("content",answer).put("reasoning_content",index==1?"先思考一下":""))))
                    .put("usage",new JSONObject().put("prompt_tokens",50).put("completion_tokens",80))
                    .toString().getBytes(StandardCharsets.UTF_8);
                java.io.OutputStream output=socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);output.flush();
            }
        }catch(Exception ignored){}});responder.setDaemon(true);responder.start();
        try{
            Api api=new Api(context);api.save("lmstudio","http://127.0.0.1:"+server.getLocalPort()+"/v1","test-model","",false);api.select("lmstudio");
            NovelGeneration generation=new NovelGeneration(context,api,(type,data)->{});
            JSONObject p=generation.create(new JSONObject().put("name","自动闭环测试").put("protagonist_name","主角").put("outline","主角出发后结束故事"));
            p.put("token_budget",1);generation.save(p); // Old saves must not block any stage.
            generation.run(p.getString("id"));JSONObject single=generation.read(p.getString("id"));
            assertEquals("paused",single.getString("status"));assertEquals(1,single.getJSONArray("chapters").length());
            assertFalse(single.getBoolean("auto_continue"));assertEquals(4,calls.get());
            generation.run(p.getString("id"),true);JSONObject finished=generation.read(p.getString("id"));
            assertEquals("completed",finished.getString("status"));assertEquals(2,finished.getJSONArray("chapters").length());
            assertTrue(finished.getBoolean("auto_continue"));
            assertEquals("主角",finished.getJSONArray("characters").getJSONObject(0).getString("name"));
            assertEquals(8,calls.get());assertEquals(400,finished.getInt("input_tokens"));
            assertEquals(640,finished.getInt("output_tokens_used"));generation.delete(p.getString("id"));
        }finally{server.close();responder.join(1000);}
    }
    @Test public void minimalProjectCanStartWithoutOutline() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject p=generation.create(new JSONObject().put("name","仅两项").put("protagonist_name","小明"));
        String id=p.getString("id");assertEquals("ready",p.getString("status"));
        assertEquals("小明",p.getJSONArray("characters").getJSONObject(0).getString("name"));
        assertEquals("",generation.read(id).getString("outline"));
        generation.update(id,new JSONObject().put("outline","小明踏上旅程").put("protagonist_name","小红"));
        assertEquals("ready",generation.read(id).getString("status"));
        assertEquals("小红",generation.read(id).getJSONArray("characters").getJSONObject(0).getString("name"));
        generation.delete(id);
    }
    @Test public void projectBudgetIsIgnoredForNewAndLegacySaves() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject p=generation.create(new JSONObject().put("name","无预算测试").put("protagonist_name","甲").put("token_budget",10000));
        String id=p.getString("id");assertFalse(p.has("token_budget"));
        p.put("token_budget",10000);generation.save(p);
        JSONObject updated=generation.update(id,new JSONObject().put("token_budget",20000));
        assertFalse(updated.has("token_budget"));
        generation.delete(id);
    }
    @Test public void wholeBookDirectionIsOnlyWeakPlanningContext() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject p=generation.create(new JSONObject().put("name","方向测试").put("protagonist_name","甲")
            .put("outline","绝不能发送给正文模型的全书方向秘密"));
        assertTrue(NovelGeneration.context(p,false).contains("全书方向参考（可选、低优先级"));
        assertTrue(NovelGeneration.context(p,false).contains("绝不能发送给正文模型的全书方向秘密"));
        assertFalse(NovelGeneration.context(p,true).contains("绝不能发送给正文模型的全书方向秘密"));
        generation.delete(p.getString("id"));
    }
    @Test public void completedChapterCanBeEditedAndRestoredWithoutTouchingOtherChapters() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject p=generation.create(new JSONObject().put("name","编辑测试").put("protagonist_name","甲"));
        String id=p.getString("id");p.getJSONArray("chapters").put(new JSONObject().put("number",1).put("title","旧标题")
            .put("outline","旧章纲").put("summary","旧梗概").put("content","旧正文").put("accepted_at",123));
        p.getJSONArray("chapters").put(new JSONObject().put("number",2).put("title","后续章")
            .put("outline","后续章纲").put("summary","后续梗概").put("content","后续正文").put("accepted_at",124));
        generation.save(p);
        JSONObject edited=generation.saveCompletedChapter(id,1,123,new JSONObject().put("title","新标题")
            .put("outline","新章纲").put("summary","新梗概").put("content","新正文"));
        JSONObject chapter=edited.getJSONArray("chapters").getJSONObject(0);
        assertEquals("新正文",chapter.getString("content"));assertEquals("新章纲",chapter.getString("outline"));
        assertEquals(1,edited.getInt("memory_stale_from"));assertTrue(chapter.getBoolean("memory_stale"));
        assertEquals("旧正文",chapter.getJSONArray("revision_history").getJSONObject(0).getString("content"));
        assertEquals("后续正文",edited.getJSONArray("chapters").getJSONObject(1).getString("content"));
        try{generation.saveCompletedChapter(id,1,123,new JSONObject().put("content","过时修改"));fail("旧版本修改必须被拒绝");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("重新打开"));}
        JSONObject restored=generation.restoreCompletedChapter(id,1,chapter.getLong("updated_at"));
        JSONObject old=restored.getJSONArray("chapters").getJSONObject(0);
        assertEquals("旧正文",old.getString("content"));assertEquals("旧章纲",old.getString("outline"));
        assertEquals("新正文",old.getJSONArray("revision_history").getJSONObject(0).getString("content"));
        generation.delete(id);
    }
    @Test public void desktopProjectImportsAsIndependentAndroidSave() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NovelGeneration generation=store(context);
        JSONObject original=generation.create(new JSONObject().put("name","桌面往返").put("protagonist_name","甲"));
        String oldId=original.getString("id");original.getJSONArray("chapters").put(new JSONObject()
            .put("number",1).put("title","第一章").put("outline","桌面修改章纲")
            .put("content","桌面修改正文").put("accepted_at",123));generation.save(original);
        JSONObject imported=generation.importProject(original.toString().getBytes(StandardCharsets.UTF_8));
        String newId=imported.getString("id");assertNotEquals(oldId,newId);
        assertEquals("桌面修改正文",generation.chapter(newId,1).getString("content"));
        assertEquals("桌面修改正文",generation.chapter(oldId,1).getString("content"));
        assertEquals("paused",generation.read(newId).getString("status"));
        generation.delete(newId);generation.delete(oldId);
    }
    @Test public void regenerateUsesOnlyEarlierContextAndPreservesAcceptedDraftOnFailure() throws Exception {
        Context context=RuntimeEnvironment.getApplication();AtomicReference<String> firstRequest=new AtomicReference<>();
        ServerSocket server=new ServerSocket(0,2,java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{try{
            for(int index=0;index<2;index++)try(Socket socket=server.accept()){
                java.io.InputStream input=socket.getInputStream();java.io.ByteArrayOutputStream header=new java.io.ByteArrayOutputStream();
                int value;while((value=input.read())>=0){header.write(value);byte[] h=header.toByteArray();int n=h.length;
                    if(n>=4&&h[n-4]==13&&h[n-3]==10&&h[n-2]==13&&h[n-1]==10)break;}
                int length=0;for(String line:header.toString("US-ASCII").split("\\r\\n"))
                    if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                byte[] body=new byte[length];for(int offset=0;offset<length;){int n=input.read(body,offset,length-offset);if(n<0)break;offset+=n;}
                if(index==0)firstRequest.set(new String(body,StandardCharsets.UTF_8));
                String content=index==0?"新稿".repeat(250):"短";
                byte[] reply=new JSONObject().put("choices",new JSONArray().put(new JSONObject()
                    .put("finish_reason",index==0?"stop":"length").put("message",new JSONObject().put("content",content))))
                    .put("usage",new JSONObject().put("prompt_tokens",20).put("completion_tokens",30))
                    .toString().getBytes(StandardCharsets.UTF_8);
                java.io.OutputStream output=socket.getOutputStream();output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(reply);output.flush();
            }
        }catch(Exception ignored){}});responder.setDaemon(true);responder.start();
        try{
            Api api=new Api(context);api.save("lmstudio","http://127.0.0.1:"+server.getLocalPort()+"/v1","test-model","",false);api.select("lmstudio");
            NovelGeneration generation=new NovelGeneration(context,api,(type,data)->{});
            JSONObject p=generation.create(new JSONObject().put("name","重生成测试").put("protagonist_name","甲"));String id=p.getString("id");
            p.put("status","paused");p.getJSONArray("chapters").put(new JSONObject().put("number",1).put("title","第一章")
                .put("outline","先到城镇").put("summary","旧梗概").put("content","原稿").put("accepted_at",123));
            p.getJSONArray("chapters").put(new JSONObject().put("number",2).put("title","第二章")
                .put("outline","后续秘密").put("summary","后续秘密").put("content","后续秘密").put("accepted_at",124));
            p.getJSONArray("characters").getJSONObject(0).put("state","后续秘密");generation.save(p);
            generation.regenerateCompletedChapter(id,1,123);JSONObject changed=generation.read(id);
            assertFalse(firstRequest.get().contains("后续秘密"));assertEquals("新稿".repeat(250),generation.chapter(id,1).getString("content"));
            assertEquals("原稿",generation.chapter(id,1).getJSONArray("revision_history").getJSONObject(0).getString("content"));
            assertEquals("后续秘密",generation.chapter(id,2).getString("content"));assertEquals(1,changed.getInt("memory_stale_from"));
            long revision=generation.chapter(id,1).getLong("updated_at");
            try{generation.regenerateCompletedChapter(id,1,revision);fail("截断的替换稿必须失败");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("截断"));}
            assertEquals("新稿".repeat(250),generation.chapter(id,1).getString("content"));
            assertEquals("paused",generation.read(id).getString("status"));generation.delete(id);
        }finally{server.close();responder.join(1000);}
    }
}
