import base64
import unittest
from unittest.mock import patch

import app


class ChatImageTests(unittest.TestCase):
    def test_valid_png_and_invalid_mime_or_signature(self):
        png = "data:image/png;base64," + base64.b64encode(b"\x89PNG\r\n\x1a\nimage").decode()
        self.assertEqual(app.validated_chat_image(png), png)
        with self.assertRaisesRegex(ValueError, "不匹配"):
            app.validated_chat_image(png.replace("image/png", "image/jpeg"))
        with self.assertRaisesRegex(ValueError, "仅支持"):
            app.validated_chat_image("data:text/plain;base64,YQ==")

    def test_image_token_estimate_does_not_count_base64_as_text(self):
        image = "data:image/png;base64," + "A" * 100000
        tokens = app.estimate_messages_tokens([{"role": "user", "content": [
            {"type": "text", "text": "描述图片"}, {"type": "image_url", "image_url": {"url": image}}
        ]}])
        self.assertLess(tokens, 3000)

    def test_multimodal_chat_request_keeps_image_block(self):
        image = "data:image/png;base64," + base64.b64encode(b"\x89PNG\r\n\x1a\nimage").decode()
        message = {"role": "user", "content": [{"type": "text", "text": "描述"},
                                             {"type": "image_url", "image_url": {"url": image}}]}
        config = {"provider": "deepseek", "base_url": "https://api.deepseek.com",
                  "chat_model": "deepseek-flash", "max_tokens": "300", "_single_request": True}
        response = {"choices": [{"finish_reason": "stop", "message": {"content": "测试回答"}}]}
        with patch.object(app, "request_api_json", return_value=response) as call:
            self.assertEqual(app.call_chat_api([message], config), "测试回答")
        self.assertEqual(call.call_args.kwargs["body"]["messages"][0]["content"][1]["image_url"]["url"], image)


if __name__ == "__main__":
    unittest.main()
