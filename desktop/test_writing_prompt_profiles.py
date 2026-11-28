import json
import tempfile
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from unittest.mock import patch

import app
import writing_prompt_profiles as writing


class WritingPromptTests(unittest.TestCase):
    def test_sampling_and_single_call_for_multiple_books(self):
        calls = []
        def model(messages, tokens):
            calls.append((messages, tokens))
            return json.dumps({"style_prompt": "使用克制的第三人称叙事。", "unified_prompt": "每章推进明确事件并保持人物一致。"}, ensure_ascii=False)
        result = writing.synthesize([{"name": "甲.txt", "text": "甲乙丙丁" * 2000},
                                     {"name": "乙.md", "text": "春夏秋冬" * 2000}], model)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][1], 2400)
        self.assertLess(len(calls[0][0][1]["content"]), 6000)
        self.assertEqual(result["source_names"], ["甲.txt", "乙.md"])

    def test_portable_profile_has_no_book_text_and_can_reload(self):
        with tempfile.TemporaryDirectory() as root:
            saved = writing.save(root, {"format": writing.FORMAT, "version": 1,
                "name": "参考配置", "style_prompt": "短句叙事", "unified_prompt": "维持前后连贯",
                "source_names": ["参考书.epub"], "raw_book": "绝不保存"})
            loaded = writing.get(root, saved["id"])
            self.assertNotIn("raw_book", loaded)
            self.assertEqual(len(writing.list_profiles(root)), 1)
            self.assertEqual(loaded["unified_prompt"], "维持前后连贯")

    def test_bad_input_does_not_save(self):
        with self.assertRaises(ValueError):
            writing.synthesize([{"name": "空.txt", "text": "太短"}], lambda *_: "{}")
        with self.assertRaises(ValueError):
            writing.validate_profile({"format": "other", "version": 1})

    def test_http_synthesis_profile_and_apply(self):
        with tempfile.TemporaryDirectory() as root:
            server = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            base = f"http://127.0.0.1:{server.server_port}"
            def post(path, payload):
                request = urllib.request.Request(base + path, data=json.dumps(payload).encode(),
                    headers={"Content-Type": "application/json"}, method="POST")
                with urllib.request.urlopen(request, timeout=10) as response:
                    return json.load(response)
            try:
                with patch.object(app, "data_dir", return_value=root), patch.object(app, "call_generation_model",
                    return_value=json.dumps({"style_prompt": "文风规则", "unified_prompt": "章节规则"}, ensure_ascii=False)):
                    project = post("/api/generation/create", {"name": "测试书", "protagonist_name": "甲"})["project"]
                    result = post("/api/generation/synthesize_writing_prompt", {"books": [
                        {"name": "参考书.txt", "sample": "春夏秋冬" * 100}]})["result"]
                    saved = post("/api/generation/writing_profile_save", {"profile": {
                        "format": writing.FORMAT, "version": 1, "name": "测试配置",
                        **{key: result[key] for key in ("style_prompt", "unified_prompt", "source_names")}}})["profile"]
                    applied = post("/api/generation/writing_profile_apply", {
                        "profile_id": saved["id"], "project_id": project["id"]})["project"]
                    self.assertEqual(applied["style"], "文风规则")
                    self.assertEqual(applied["generation_prompt"], "章节规则")
                    with urllib.request.urlopen(base + "/api/generation/writing_profiles") as response:
                        self.assertEqual(len(json.load(response)["profiles"]), 1)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
