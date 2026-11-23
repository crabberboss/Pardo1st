package com.novelmemory.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ApiTest {
    @Test public void geminiAndGlmHaveIndependentProfiles() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("api_profiles",Context.MODE_PRIVATE).edit().clear().commit();
        Api api=new Api(context);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai",api.config("gemini").getString("base_url"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4",api.config("glm").getString("base_url"));
        api.save("gemini",Api.defaultUrl("gemini"),"gemini-test","",false);
        api.save("glm",Api.defaultUrl("glm"),"glm-test","",false);
        assertEquals("gemini-test",api.config("gemini").getString("model"));
        assertEquals("glm-test",api.config("glm").getString("model"));
    }
    @Test public void independentProvidersAndChatEndpoint() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("api_profiles",Context.MODE_PRIVATE).edit().clear().commit();
        AtomicReference<String> body=new AtomicReference<>();
        ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{
            try(Socket socket=server.accept()){
                java.io.InputStream input=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();
                int b;while((b=input.read())!=-1){headers.write(b);byte[] a=headers.toByteArray();int n=a.length;if(n>=4&&a[n-4]==13&&a[n-3]==10&&a[n-2]==13&&a[n-1]==10)break;}
                String head=headers.toString("UTF-8");int length=0;
                for(String line:head.split("\\r\\n"))if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                byte[] request=new byte[length];int pos=0;while(pos<length){int read=input.read(request,pos,length-pos);if(read<0)break;pos+=read;}
                body.set(new String(request,StandardCharsets.UTF_8));
                byte[] response="{\"choices\":[{\"message\":{\"content\":\"测试成功\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
                java.io.OutputStream output=socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(response);output.flush();
            }catch(Exception ex){throw new RuntimeException(ex);}
        });responder.start();
        try {
            Api api=new Api(context);
            String url="http://127.0.0.1:"+server.getLocalPort()+"/v1";
            api.save("deepseek",url,"model-a","",false);
            api.save("siliconflow","https://api.siliconflow.cn/v1","model-b","",false);
            api.save("lmstudio","http://192.168.1.5:1234/v1","model-c","",false);
            assertEquals("model-a",api.config("deepseek").getString("model"));
            assertEquals("model-b",api.config("siliconflow").getString("model"));
            assertEquals("model-c",api.config("lmstudio").getString("model"));
            api.select("deepseek");
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","你好"));
            assertEquals("测试成功",api.chat(messages,128,10));
            JSONObject sent=new JSONObject(body.get());
            assertEquals("model-a",sent.getString("model"));
            assertEquals("你好",sent.getJSONArray("messages").getJSONObject(0).getString("content"));
            assertEquals(128,sent.getInt("max_tokens"));
        } finally {server.close();responder.join(1000);}
    }

    @Test public void modelListAndTestClassifyAuthAndMissingModel() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("api_profiles",Context.MODE_PRIVATE).edit().clear().commit();
        ServerSocket server=new ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"));
        AtomicBoolean running=new AtomicBoolean(true);
        Thread responder=new Thread(()->{
            while(running.get()){
                try(Socket socket=server.accept()){
                    java.io.InputStream input=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();
                    int b;while((b=input.read())!=-1){headers.write(b);byte[] a=headers.toByteArray();int n=a.length;if(n>=4&&a[n-4]==13&&a[n-3]==10&&a[n-2]==13&&a[n-1]==10)break;}
                    String head=headers.toString("UTF-8");int length=0;String auth="";
                    for(String line:head.split("\\r\\n")){
                        if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                        if(line.toLowerCase().startsWith("authorization:"))auth=line.substring(14).trim();
                    }
                    byte[] request=new byte[length];int pos=0;while(pos<length){int read=input.read(request,pos,length-pos);if(read<0)break;pos+=read;}
                    String body=new String(request,StandardCharsets.UTF_8);
                    boolean models=head.startsWith("GET ");
                    int code=200;String json;
                    if(auth.contains("badkey")){code=401;json="{\"error\":{\"message\":\"Invalid API key\"}}";}
                    else if(models)json="{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}";
                    else if(body.contains("missing")){code=404;json="{\"error\":{\"message\":\"model not found\"}}";}
                    else json="{\"choices\":[{\"message\":{\"content\":\"连接成功\"},\"finish_reason\":\"stop\"}]}";
                    byte[] response=json.getBytes(StandardCharsets.UTF_8);
                    String status=code==200?"200 OK":(code==401?"401 Unauthorized":"404 Not Found");
                    java.io.OutputStream output=socket.getOutputStream();
                    output.write(("HTTP/1.1 "+status+"\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(response);output.flush();
                }catch(Exception ex){break;}
            }
        });responder.start();
        try {
            Api api=new Api(context);
            String url="http://127.0.0.1:"+server.getLocalPort()+"/v1";
            JSONObject ok=api.test("openrouter",url,"model-a","sk-goodkey123456",false);
            assertTrue(ok.getBoolean("ok"));
            assertEquals(200,ok.getInt("http_status"));
            assertEquals(2,ok.getInt("model_count"));
            assertEquals("连接成功",ok.getString("answer"));
            JSONObject auth=api.test("openrouter",url,"model-a","sk-badkey123456",false);
            assertFalse(auth.getBoolean("ok"));
            assertEquals("auth",auth.getString("error_kind"));
            assertEquals(401,auth.getInt("http_status"));
            JSONObject missing=api.test("openrouter",url,"missing","sk-goodkey123456",false);
            assertFalse(missing.getBoolean("ok"));
            assertEquals("model_not_found",missing.getString("error_kind"));
            assertEquals(404,missing.getInt("http_status"));
            JSONObject models=api.models("openrouter",url,"model-a","sk-goodkey123456",false);
            assertTrue(models.getBoolean("ok"));
            assertEquals(2,models.getJSONArray("models").length());
            JSONObject missingKey=api.test("deepseek","https://api.deepseek.com","deepseek-v4-flash","",false);
            assertFalse(missingKey.getBoolean("ok"));
            assertEquals("missing_key",missingKey.getString("error_kind"));
        } finally {running.set(false);server.close();responder.join(1000);}
    }
}
