package com.novelmemory.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TavernCardTest {
    private static byte[] chunk(String type, byte[] payload) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{(byte)(payload.length>>>24),(byte)(payload.length>>>16),(byte)(payload.length>>>8),(byte)payload.length});
        out.write(type.getBytes(StandardCharsets.US_ASCII));out.write(payload);
        CRC32 crc=new CRC32();crc.update(type.getBytes(StandardCharsets.US_ASCII));crc.update(payload);
        long value=crc.getValue();
        out.write(new byte[]{(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value});
        return out.toByteArray();
    }

    @Test public void jsonV2CardKeepsUnknownFieldsInExtensions() throws Exception {
        JSONObject card=new JSONObject().put("spec","chara_card_v2").put("spec_version","2.0")
                .put("data",new JSONObject().put("name","爱丽丝").put("description","图书管理员")
                        .put("first_mes","你来了。").put("mes_example","{{char}}：请坐。")
                        .put("character_book",new JSONObject().put("entries",new org.json.JSONArray()))
                        .put("custom_field","保留我"));
        JSONObject parsed=TavernCard.parse(card.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject profile=parsed.getJSONObject("profile");
        assertEquals("爱丽丝",profile.getString("character_name"));
        assertEquals("你来了。",profile.getString("first_message"));
        assertTrue(parsed.getString("spec").contains("V2"));
        JSONObject extensions=profile.getJSONObject("extensions");
        assertEquals("保留我",extensions.getJSONObject("tavern_unmapped").getString("custom_field"));
        assertTrue(parsed.getString("preview").contains("角色书"));
    }

    @Test public void pngTextChunkIsReadAndJsonWithoutMetadataIsRejected() throws Exception {
        JSONObject card=new JSONObject().put("spec","chara_card_v3").put("spec_version","3.0")
                .put("data",new JSONObject().put("name","高文").put("first_mes","开始吧。")
                        .put("alternate_greetings",new org.json.JSONArray().put("另一个开场")));
        String encoded=Base64.encodeToString(card.toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        ByteArrayOutputStream png=new ByteArrayOutputStream();
        png.write(new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
        png.write(chunk("tEXt",("chara\0"+encoded).getBytes(StandardCharsets.ISO_8859_1)));
        png.write(chunk("IEND",new byte[0]));
        JSONObject parsed=TavernCard.parse(png.toByteArray());
        assertEquals("PNG",parsed.getString("source"));
        assertEquals("高文",parsed.getJSONObject("profile").getString("character_name"));
        assertTrue(parsed.getString("spec").contains("V3"));
        boolean failed=false;
        try{TavernCard.parse(new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});}
        catch(IllegalArgumentException expected){failed=true;}
        assertTrue(failed);
    }
}
