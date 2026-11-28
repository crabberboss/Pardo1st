import base64
import json
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from unittest.mock import patch

import app
import generation_desktop_ui
import generation_revision as revision


def sample_project():
    return {"format": "pardo-novel-generation", "version": 1,
            "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name": "跨端小说", "status": "paused",
            "world": "初始世界", "protagonist_name": "主角", "chapter_chars": 600,
            "output_tokens": 2048, "chapters": [
                {"number": 1, "title": "开端", "outline": "旧章纲", "summary": "旧梗概",
                 "content": "开端正文", "accepted_at": 101},
                {"number": 2, "title": "后事", "outline": "后续章纲", "summary": "未来秘密",
                 "content": "未来正文", "accepted_at": 102}],
            "characters": [], "threads": [], "world_state": []}


class GenerationRevisionTests(unittest.TestCase):
    def test_generation_layout_keeps_create_button_readable(self):
        self.assertIn('class="generation-create"', app.UI_HTML)
        self.assertIn('for="deskGenNewName"', app.UI_HTML)
        self.assertIn('for="deskGenNewProtagonist"', app.UI_HTML)
        self.assertIn('.generation-create button{height:46px;min-width:142px', generation_desktop_ui.CSS)
        self.assertIn('@media(max-width:680px)', generation_desktop_ui.CSS)
        self.assertIn('.generation-pane.card{min-height:0;margin:0;padding:24px 8px 28px;border:0', generation_desktop_ui.CSS)
        self.assertIn('.generation-stages button.active{border-bottom-color:var(--accent);background:transparent', generation_desktop_ui.CSS)

    def test_flow_navigation_and_memory_projection(self):
        for stage in ("Setup", "Plan", "Run", "Memory", "Draft"):
            self.assertIn('id="deskGenStage' + stage + '"', generation_desktop_ui.JS)
        self.assertIn("function deskGenStage(stage)", generation_desktop_ui.JS)
        self.assertIn("deskGenRenderMemory()", generation_desktop_ui.JS)
        project = sample_project()
        project["characters"] = [
            {"name": "主角", "last_chapter": 2, "state": "正在远行", "status": "active"},
            {"name": "故人", "last_chapter": 1, "status": "dead"},
        ]
        project["threads"] = [{"name": "寻找线索", "status": "active", "source_chapter": 2}]
        projected = revision.view(project)
        self.assertEqual(projected["characters"][0]["heat"], 100)
        self.assertEqual(projected["characters"][1]["heat"], 0)
        self.assertEqual(projected["threads"][0]["name"], "寻找线索")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.project = revision.import_project(self.temp.name, json.dumps(sample_project()).encode())
        self.id = self.project["id"]

    def tearDown(self):
        self.temp.cleanup()

    def test_edit_restore_and_project_isolation(self):
        original = revision.get_chapter(self.temp.name, self.id, 1)
        updated = revision.save_chapter(self.temp.name, self.id, 1, 101,
                                        {"title": "新标题", "outline": "新章纲", "content": "人工修改正文", "summary": "新梗概"})
        self.assertEqual(updated["content"], "人工修改正文")
        self.assertEqual(updated["revision_history"][0]["content"], original["content"])
        self.assertEqual(revision.get_chapter(self.temp.name, self.id, 2)["content"], "未来正文")
        self.assertEqual(revision.get_project(self.temp.name, self.id)["memory_stale_from"], 1)
        with self.assertRaisesRegex(ValueError, "其他操作"):
            revision.save_chapter(self.temp.name, self.id, 1, 101, {"content": "过期保存"})
        restored = revision.restore_chapter(self.temp.name, self.id, 1, updated["updated_at"])
        self.assertEqual(restored["content"], "开端正文")
        self.assertEqual(restored["outline"], "旧章纲")

    def test_regeneration_uses_prior_context_and_preserves_old_on_failure(self):
        prompts = []

        def succeed(messages, output_tokens):
            prompts.append(messages[1]["content"])
            self.assertEqual(output_tokens, 2048)
            return "新的章节正文。" * 60

        changed = revision.regenerate_chapter(self.temp.name, self.id, 1, 101, succeed)
        self.assertNotIn("未来秘密", prompts[0])
        self.assertNotIn("未来正文", prompts[0])
        self.assertEqual(changed["summary"], "")
        self.assertEqual(changed["revision_history"][0]["content"], "开端正文")
        with self.assertRaisesRegex(ValueError, "过短"):
            revision.regenerate_chapter(self.temp.name, self.id, 1, changed["updated_at"],
                                        lambda *_: "太短")
        self.assertEqual(revision.get_chapter(self.temp.name, self.id, 1)["content"], changed["content"])

    def test_http_import_edit_export_and_model_failure(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = f"http://127.0.0.1:{server.server_port}"

        def post(path, data):
            request = urllib.request.Request(base + path, data=json.dumps(data).encode(),
                                             headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)

        try:
            with patch.object(app, "data_dir", return_value=self.temp.name):
                imported = post("/api/generation/import", {"content_base64": base64.b64encode(
                    json.dumps(sample_project()).encode()).decode()})["project"]
                project_id = imported["id"]
                with urllib.request.urlopen(base + "/api/generation/projects") as response:
                    self.assertTrue(any(item["id"] == project_id for item in json.load(response)["projects"]))
                saved = post("/api/generation/save_chapter", {"id": project_id, "number": 1,
                    "expected_updated_at": 101, "changes": {"outline": "桌面章纲"}})["chapter"]
                self.assertEqual(saved["outline"], "桌面章纲")
                exported = post("/api/generation/export", {"id": project_id})["project"]
                self.assertEqual(exported["chapters"][0]["outline"], "桌面章纲")
                self.assertEqual(exported["format"], "pardo-novel-generation")
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)

    def test_desktop_can_create_and_generate_one_manual_chapter(self):
        project = revision.create_project(self.temp.name, {"name": "新小说", "protagonist_name": "乙"})
        project_id = project["id"]
        revision.update_project(self.temp.name, project_id, {"world": "山城世界", "chapter_chars": 800,
                                                              "output_tokens": 3000})
        revision.save_plan(self.temp.name, project_id, {"title": "出发", "outline": "乙出发",
                                                        "cast": ["乙"], "viewpoint": "乙"})
        calls = []

        def model(messages, tokens):
            calls.append((messages, tokens))
            if len(calls) == 1:
                return "乙来到山城。" * 90
            return json.dumps({"summary": "乙抵达山城。", "keywords": ["山城"],
                               "special_tags": ["独自远行"], "characters": [],
                               "threads": [], "world_state": []}, ensure_ascii=False)

        revision.start_one(self.temp.name, project_id, model)
        for _ in range(100):
            result = revision.get_project(self.temp.name, project_id)
            if result["status"] != "running":
                break
            time.sleep(.02)
        self.assertEqual(result["status"], "paused", result.get("error"))
        self.assertEqual(result["stage"], "plan")
        self.assertEqual(len(result["chapters"]), 1)
        self.assertEqual(revision.get_chapter(self.temp.name, project_id, 1)["special_tags"], ["独自远行"])
        self.assertEqual(len(calls), 2)  # manually saved plan avoids a planning API call

    def test_desktop_auto_plans_and_keeps_checkpoint_on_summary_failure(self):
        project = revision.create_project(self.temp.name, {"name": "自动规划", "protagonist_name": "甲"})
        project_id = project["id"]
        calls = []

        def model(messages, tokens):
            calls.append(tokens)
            if len(calls) == 1:
                return json.dumps({"end": False, "title": "第一章", "outline": "甲走进城镇",
                                   "cast": ["甲"], "keywords": ["城镇"], "special_tags": []}, ensure_ascii=False)
            if len(calls) == 2:
                return "甲走进城镇。" * 90
            return "not json"

        revision.start_one(self.temp.name, project_id, model)
        for _ in range(100):
            result = revision.get_project(self.temp.name, project_id)
            if result["status"] != "running":
                break
            time.sleep(.02)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["stage"], "extract")
        self.assertGreater(result["draft_chars"], 400)
        self.assertEqual(result["chapters"], [])
        self.assertEqual(len(calls), 3)

    def test_http_create_manual_plan_and_start(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = f"http://127.0.0.1:{server.server_port}"

        def post(path, data):
            request = urllib.request.Request(base + path, data=json.dumps(data).encode(),
                                             headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)

        calls = []

        def model(messages, tokens):
            calls.append(tokens)
            return "甲走进城镇。" * 90 if len(calls) == 1 else json.dumps(
                {"summary": "甲抵达城镇", "characters": [], "threads": [], "world_state": []}, ensure_ascii=False)

        try:
            with patch.object(app, "data_dir", return_value=self.temp.name), \
                 patch.object(app, "call_generation_model", side_effect=model):
                created = post("/api/generation/create", {"name": "桌面新书", "protagonist_name": "甲"})["project"]
                project_id = created["id"]
                post("/api/generation/update", {"id": project_id, "changes": {"world": "城镇"}})
                post("/api/generation/plan", {"id": project_id, "plan": {"title": "第一章", "outline": "甲出发"}})
                post("/api/generation/start", {"id": project_id})
                for _ in range(100):
                    with urllib.request.urlopen(base + "/api/generation/project/" + project_id) as response:
                        state = json.load(response)["project"]
                    if state["status"] != "running":
                        break
                    time.sleep(.02)
                self.assertEqual(state["status"], "paused", state.get("error"))
                self.assertEqual(len(state["chapters"]), 1)
                self.assertEqual(len(calls), 2)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
