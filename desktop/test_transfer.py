import tempfile
import unittest
import base64
from unittest.mock import patch

import app


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.data_patch = patch.object(app, "data_dir", return_value=self.temp.name)
        self.data_patch.start()
        app.init_db()

    def tearDown(self):
        self.data_patch.stop()
        self.temp.cleanup()

    def test_settings_exclude_secrets_by_default_and_preserve_existing_keys(self):
        with app.connect_db() as conn:
            app.upsert_provider_config(conn, "deepseek", {"base_url": "https://api.deepseek.com", "chat_model": "deepseek-chat", "api_key": "private-key"})
        bundle = app.export_settings_bundle()
        self.assertNotIn("private-key", str(bundle))
        self.assertFalse(bundle["contains_secrets"])
        bundle["providers"]["deepseek"]["chat_model"] = "new-model"
        app.import_settings_bundle(bundle)
        with app.connect_db() as conn:
            saved = app.get_provider_config(conn, "deepseek")
        self.assertEqual(saved["chat_model"], "new-model")
        self.assertEqual(saved["api_key"], "private-key")

    def test_import_secrets_requires_explicit_opt_in(self):
        bundle = app.export_settings_bundle(True)
        with self.assertRaisesRegex(ValueError, "密钥"):
            app.import_settings_bundle(bundle)
        self.assertTrue(app.import_settings_bundle(bundle, True)["included_keys"])

    def test_bad_setting_does_not_partly_update_provider(self):
        before = app.export_settings_bundle()
        changed = app.export_settings_bundle()
        changed["providers"]["deepseek"]["chat_model"] = "should-not-save"
        changed["globals"]["summary_prompt"] = "x" * 50001
        with self.assertRaisesRegex(ValueError, "超出长度限制"):
            app.import_settings_bundle(changed)
        after = app.export_settings_bundle()
        self.assertEqual(before["providers"]["deepseek"], after["providers"]["deepseek"])

    def test_epub_image_survives_archive_export_import(self):
        archive_id = app.create_archive("插图测试")
        with app.connect_db() as conn:
            doc = conn.execute("INSERT INTO documents(archive_id,filename,title,char_count,created_at) VALUES(?,?,?,?,?)",
                               (archive_id, "book.epub", "插图", 0, app.now_ts())).lastrowid
            conn.execute("INSERT INTO epub_images(archive_id,document_id,image_ord,filename,mime,content) VALUES(?,?,?,?,?,?)",
                         (archive_id, doc, 0, "picture.png", "image/png", b"image-bytes"))
        package = app.export_archive(archive_id)
        self.assertEqual(b"image-bytes", base64.b64decode(package["epub_images"][0]["content_base64"]))
        imported = app.import_archive_package(package)
        with app.connect_db() as conn:
            row = conn.execute("SELECT filename,mime,content FROM epub_images WHERE archive_id=?", (imported,)).fetchone()
        self.assertEqual(("picture.png", "image/png", b"image-bytes"), tuple(row))

    def test_unified_page_keeps_old_routes(self):
        self.assertIn('id="transferView"', app.UI_HTML)
        self.assertIn('id="navTransfer"', app.UI_HTML)
        self.assertIn('"/api/export_archive"', app.UI_HTML)
        self.assertIn('"/api/import_archive_json"', app.UI_HTML)
        self.assertIn("delete card.id", app.UI_HTML)
        self.assertIn("delete card.data.id", app.UI_HTML)
        self.assertIn("decodeNovelText(file", app.UI_HTML)
        self.assertIn('id="transferRewriteProject"', app.UI_HTML)
        self.assertIn("pardo-chat-preset", app.UI_HTML)

    def test_transfer_checkboxes_and_status_have_scoped_layout(self):
        self.assertIn('class="transfer-check"><input id="transferIncludeKeys"', app.UI_HTML)
        self.assertIn('class="transfer-check"><input id="transferAllowKeys"', app.UI_HTML)
        self.assertIn('#transferView .transfer-check input[type="checkbox"]', app.UI_HTML)
        self.assertIn('id="transferStatus" class="transfer-status" role="status" hidden', app.UI_HTML)
        self.assertIn('align-items:start;margin-top:16px', app.UI_HTML)
        self.assertIn("left.className='transfer-column';right.className='transfer-column'", app.UI_HTML)
        self.assertIn('gap:16px', app.UI_HTML)


if __name__ == "__main__":
    unittest.main()
