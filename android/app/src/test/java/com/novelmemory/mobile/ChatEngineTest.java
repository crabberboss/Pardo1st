package com.novelmemory.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ChatEngineTest {
    @Test public void roleplayUsesCutoffAndAdvancesOnlyOneChapter() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("novel_memory_mobile.db");
        Store store=new Store(context);ChatData data=new ChatData(store);Api api=new Api(context);
        AtomicReference<String> request=new AtomicReference<>("");
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{
            try(Socket socket=server.accept()){
                InputStream input=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();
                while(!header.toString("UTF-8").endsWith("\r\n\r\n")){int value=input.read();if(value<0)throw new IllegalStateException("Request ended before headers");header.write(value);}
                String headers=header.toString("UTF-8");int length=0;
                for(String line:headers.split("\r\n"))if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                request.set(new String(input.readNBytes(length),StandardCharsets.UTF_8));
                byte[] answer="{\"choices\":[{\"message\":{\"content\":\"甲看向门口。<!--NM_PROGRESS:1-->\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
                OutputStream output=socket.getOutputStream();output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+answer.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(answer);output.flush();
            }catch(Exception ex){throw new RuntimeException(ex);}
        });responder.start();
        try {
            api.save("lmstudio","http://127.0.0.1:"+server.getLocalPort()+"/v1","test-model","",true);api.select("lmstudio");
            String source="第一章 开端\n甲进入院子。\n第二章 相遇\n甲见到了乙。\n第三章 真相\n乙其实来自未来。";
            long archive=store.importText("剧情.txt",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
            long profile=data.saveProfile(archive,new JSONObject().put("name","甲").put("character_name","甲")
                    .put("timeline_start_cutoff",1).put("plot_mode","跟随原著推进").put("plot_step",1)).getLong("id");
            long session=data.createSession(archive,"roleplay",profile,"第一条线").getLong("id");
            ChatEngine engine=new ChatEngine(context,store,data,api);
            String image="data:image/png;base64,iVBORw0KGgo=";
            JSONObject result=engine.respond(archive,"roleplay",profile,session,"你看见什么？",0,image);
            assertEquals(2,result.getJSONObject("session").getInt("cutoff"));
            assertEquals("甲看向门口。",result.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertTrue(request.get().contains("第二章 相遇"));
            JSONObject sent=new JSONObject(request.get());
            org.json.JSONArray parts=sent.getJSONArray("messages").getJSONObject(sent.getJSONArray("messages").length()-1).getJSONArray("content");
            assertEquals("image_url",parts.getJSONObject(1).getString("type"));
            assertEquals(image,parts.getJSONObject(1).getJSONObject("image_url").getString("url"));
            assertFalse(request.get().contains("乙其实来自未来"));
        }finally{server.close();responder.join(5000);store.close();}
    }
}
