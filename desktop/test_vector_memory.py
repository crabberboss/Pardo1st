import os
import json
import tempfile
import unittest
from unittest.mock import patch

import app
import vector_memory


SUMMARY = (
    "关键词：琥珀、密室、古地图\n"
    "剧情概览：琥珀在密室找到古地图，并与高文商量前往旧城。\n"
    "人物与关系：琥珀协助高文。\n"
    "地点/物品/设定：旧城、古地图。\n"
    "伏笔与未解问题：古地图的来源尚不明确。"
)


class VectorMemoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.db_patch = patch.object(app, "db_path", return_value=os.path.join(self.temporary.name, "test.sqlite3"))
        self.data_patch = patch.object(app, "data_dir", return_value=self.temporary.name)
        self.db_patch.start()
        self.data_patch.start()
        app.init_db()
        self.archive = app.create_archive("向量测试")
        result = app.import_document(self.archive, "test.txt", "第一章 密室\n琥珀找到了古地图。")
        with app.connect_db() as conn:
            chunk = conn.execute("SELECT id,document_id,title FROM chunks WHERE archive_id=?", (self.archive,)).fetchone()
            self.chunk_id = int(chunk["id"])
            self.source_key = f"doc:{chunk['document_id']}:chapter:{chunk['title']}"
            app.upsert_summary(conn, self.archive, "chapter", self.source_key, chunk["title"], SUMMARY)

    def tearDown(self):
        self.db_patch.stop()
        self.data_patch.stop()
        self.temporary.cleanup()

    def fake_embed(self, backend, texts, model="", base_url="", api_key=""):
        return [[1.0, 0.0] for _ in texts]

    def test_builtin_index_and_hybrid_lookup(self):
        with patch.object(vector_memory, "embed_with", side_effect=self.fake_embed), \
             patch.object(vector_memory, "provider_name", return_value="显卡（DirectML）"):
            result = app.index_summary_vectors(self.archive)
            self.assertEqual(result["chapters"], 1)
            self.assertGreater(result["embedded_parts"], 0)
            self.assertEqual(app.search_summary_vectors(self.archive, "进入封闭房间", 3)[0]["id"], self.chunk_id)
            self.assertEqual(app.search_chunks(self.archive, "进入封闭房间", 3)[0]["id"], self.chunk_id)
            again = app.index_summary_vectors(self.archive)
            self.assertEqual(again["embedded_parts"], 0)

    def test_edited_summary_is_not_reused(self):
        with patch.object(vector_memory, "embed_with", side_effect=self.fake_embed), \
             patch.object(vector_memory, "provider_name", return_value="CPU"):
            app.index_summary_vectors(self.archive)
            with app.connect_db() as conn:
                app.upsert_summary(conn, self.archive, "chapter", self.source_key, "第一章", SUMMARY + "\n补充：新事实。")
            self.assertEqual(app.search_summary_vectors(self.archive, "进入封闭房间", 3), [])
            self.assertGreater(app.index_summary_vectors(self.archive)["embedded_parts"], 0)

    def test_no_external_request_for_builtin(self):
        with patch.object(vector_memory, "service_embed", side_effect=AssertionError("external service called")):
            values = vector_memory.embed(["章节总结"])
            self.assertEqual(len(values[0]), 512)

    def test_existing_summaries_can_be_indexed_without_resummarizing(self):
        with patch.object(vector_memory, "embed_with", side_effect=self.fake_embed), \
             patch.object(vector_memory, "provider_name", return_value="CPU"), \
             patch.object(app, "summarize_reliably", side_effect=AssertionError("resummarized")):
            app.build_memory_job("vector-only-test", self.archive, missing_only=True, vectorize=True)
        job = app.JOBS["vector-only-test"]
        self.assertEqual(job["status"], "done", job.get("message"))
        self.assertEqual(job["vector_result"]["chapters"], 1)

    def test_original_text_index_is_resumable_portable_and_independent(self):
        with patch.object(vector_memory, "embed_with", side_effect=self.fake_embed), \
             patch.object(app, "summarize_reliably", side_effect=AssertionError("resummarized")):
            built = app.index_source_vectors(self.archive)
            self.assertGreater(built["embedded_parts"], 0)
            self.assertEqual(app.index_source_vectors(self.archive)["embedded_parts"], 0)
            self.assertEqual(app.search_source_vectors(self.archive, "找到地图", 3)[0]["id"], self.chunk_id)
            exported = app.export_archive(self.archive)
            self.assertEqual(exported["version"], 5)
            self.assertGreater(len(exported["source_vector_index"]["records"]), 0)
            imported = app.import_archive_package(exported)
            self.assertTrue(app.search_source_vectors(imported, "找到地图", 3))

    def test_source_edit_invalidates_original_vector_without_resummarizing(self):
        with patch.object(vector_memory, "embed_with", side_effect=self.fake_embed):
            app.index_source_vectors(self.archive)
            with app.connect_db() as conn:
                conn.execute("UPDATE chunks SET text=text||'后来离开。' WHERE id=?", (self.chunk_id,))
            self.assertEqual(app.search_source_vectors(self.archive, "后来", 3), [])
            self.assertGreater(app.index_source_vectors(self.archive)["embedded_parts"], 0)

    def test_online_channel_reuses_saved_provider_credentials(self):
        with app.connect_db() as conn, patch.object(app, "get_provider_config", return_value={
            "base_url": "https://openrouter.ai/api/v1", "api_key": "saved-key",
        }):
            url, key = app.resolve_vector_credentials(conn, "online", "openrouter", "")
        self.assertEqual(url, "https://openrouter.ai/api/v1")
        self.assertEqual(key, "saved-key")
        self.assertNotIn("saved-key", str(app.export_archive(self.archive)))

    def test_online_channel_requires_a_separate_embedding_model(self):
        with self.assertRaisesRegex(ValueError, "模型 ID"):
            vector_memory.model_key("online", "", "https://openrouter.ai/api/v1")

    def test_online_request_uses_embedding_endpoint_and_key(self):
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, *_args):
                return json.dumps({"data": [{"index": 0, "embedding": [3, 4]}]}).encode()

        with patch.object(vector_memory.urllib.request, "urlopen", return_value=Response()) as request:
            result = vector_memory.service_embed(["测试"], "embed-model", "https://example.com/v1", "secret", online=True)
        self.assertEqual(result, [[0.6, 0.8]])
        self.assertEqual(request.call_args.args[0].full_url, "https://example.com/v1/embeddings")
        self.assertEqual(request.call_args.args[0].get_header("Authorization"), "Bearer secret")


if __name__ == "__main__":
    unittest.main()
