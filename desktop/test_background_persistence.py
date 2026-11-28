import base64
import json
import tempfile
import threading
import unittest
import urllib.request
from unittest.mock import patch

import app


class BackgroundPersistenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.data_patch = patch.object(app, "data_dir", return_value=self.temp.name)
        self.data_patch.start()

    def tearDown(self):
        self.data_patch.stop()
        self.temp.cleanup()

    def test_save_reload_and_clear(self):
        image = "data:image/png;base64," + base64.b64encode(b"\x89PNG\r\n\x1a\nbackground").decode()
        app.save_background_image(image)
        self.assertEqual(app.load_background_image(), image)
        with self.assertRaises(ValueError):
            app.save_background_image("data:text/plain;base64,YQ==")
        self.assertEqual(app.load_background_image(), image)
        app.clear_background_image()
        self.assertEqual(app.load_background_image(), "")

    def test_ui_exposes_per_region_opacity_controls(self):
        for marker in ("id=\"alphaNav\"", "id=\"alphaChat\"", "id=\"alphaPanel\"",
                       "id=\"alphaInput\"", "setBackgroundAlpha", "--alpha-chat"):
            self.assertIn(marker, app.UI_HTML)
        self.assertIn("pardo.backgroundAlpha.v1", app.UI_HTML)

    def test_http_round_trip(self):
        server = app.QuietThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        url = f"http://127.0.0.1:{server.server_port}/api/ui/background"
        image = "data:image/png;base64," + base64.b64encode(b"\x89PNG\r\n\x1a\nbackground").decode()

        def save(value):
            request = urllib.request.Request(url, data=json.dumps({"image": value}).encode(),
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=5) as response:
                self.assertTrue(json.load(response)["ok"])

        try:
            save(image)
            with urllib.request.urlopen(url, timeout=5) as response:
                self.assertEqual(json.load(response)["image"], image)
            save("")
            with urllib.request.urlopen(url, timeout=5) as response:
                self.assertEqual(json.load(response)["image"], "")
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)

    def test_appearance_survives_reload_and_clamps_values(self):
        saved = app.save_ui_appearance({"alpha": {"nav": 58, "chat": 0}, "blur": {"panel": 30}})
        self.assertEqual(saved, {"alpha": {"nav": 58, "chat": 20}, "blur": {"panel": 24}})
        self.assertEqual(app.load_ui_appearance(), saved)
        with self.assertRaises(ValueError):
            app.save_ui_appearance({"alpha": []})
        self.assertEqual(app.load_ui_appearance(), saved)


if __name__ == "__main__":
    unittest.main()
