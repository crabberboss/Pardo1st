import base64
import json
import hashlib
import os
import sqlite3
import struct
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import app


class SceneWorkspaceTests(unittest.TestCase):
    def test_web_search_is_off_by_default_and_only_fetches_when_enabled(self):
        self.assertEqual(app.web_search_context("测试"), "")
        app.save_web_search_settings({"enabled": True, "key": "test-key"})
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, *args): return b'{"web":{"results":[{"title":"Example","url":"https://example.com","description":"Result"}]}}'
        with patch("urllib.request.urlopen", return_value=Response()) as fetch:
            result = app.web_search_context("测试")
        self.assertIn("https://example.com", result)
        self.assertEqual(fetch.call_args.args[0].get_header("X-subscription-token"), "test-key")
        app.save_web_search_settings({"enabled": False})
        self.assertEqual(app.web_search_context("测试"), "")

    def test_deepseek_search_uses_selected_provider_key_and_official_endpoint(self):
        app.save_web_search_settings({"enabled": True})
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, *args): return b'{"results":[{"title":"DeepSeek","url":"https://example.com","summary":"Result"}]}'
        with patch.object(app, "current_config", return_value={"provider":"deepseek","api_key":"deepseek-test-key"}), patch("urllib.request.urlopen", return_value=Response()) as fetch:
            result = app.web_search_context("测试")
        request = fetch.call_args.args[0]
        self.assertEqual(request.full_url, "https://api.deepseek.com/v1/tools/search")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Authorization"), "Bearer deepseek-test-key")
        self.assertEqual(json.loads(request.data)["count"], 3)
        self.assertIn("Result", result)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "test.sqlite3")
        self.db_patch = patch.object(app, "db_path", lambda: self.db)
        self.db_patch.start()
        app.init_db()
        self.a = app.create_archive("第一本")
        self.b = app.create_archive("第二本")
        self.ca = app.add_chapter(self.a, "第一章", "两人在密室里交谈，门被锁住。")['chapters'][0]
        self.cb = app.add_chapter(self.b, "第一章", "一群人在草原上奔跑。")['chapters'][0]

    def tearDown(self):
        self.db_patch.stop()
        self.tmp.cleanup()

    def test_memory_pause_keeps_completed_chapter_and_skips_remaining(self):
        app.add_chapter(self.a, '第二章', '主角走出密室。')
        job_id = 'test-memory-pause-chapters'
        app.JOBS[job_id] = {'id': job_id, 'kind': 'memory', 'archive_id': self.a, 'status': 'queued'}
        calls = []

        def summarize(title, _text, _level, _config):
            calls.append(title)
            app.pause_memory_build(job_id, self.a)
            return '关键词：密室\n剧情概览：两人交谈。\n人物关系：两人合作。\n关键事件：走出密室。\n后续伏笔：无。'

        try:
            with patch.object(app, 'data_dir', lambda: self.tmp.name), patch.object(app, 'summarize_reliably', side_effect=summarize):
                app.build_memory_job(job_id, self.a)
            self.assertEqual(calls, ['第一章'])
            self.assertEqual(app.JOBS[job_id]['status'], 'paused')
            self.assertEqual(app.JOBS[job_id]['done'], 1)
            self.assertTrue(os.path.isfile(app.JOBS[job_id]['export_path']))
            chapters = app.list_chapters(self.a)
            self.assertTrue(chapters[0]['summary'])
            self.assertFalse(chapters[1]['summary'])
        finally:
            app.JOBS.pop(job_id, None)

    def test_memory_pause_during_overview_preserves_chapter_summaries(self):
        job_id = 'test-memory-pause-overview'
        app.JOBS[job_id] = {'id': job_id, 'kind': 'memory', 'archive_id': self.a, 'status': 'queued'}

        def compress(_title, _summaries, _level, _config, progress=None):
            app.pause_memory_build(job_id, self.a)
            return '不应写入的全书总览'

        try:
            with patch.object(app, 'data_dir', lambda: self.tmp.name), \
                 patch.object(app, 'summarize_reliably', return_value='关键词：密室\n剧情概览：两人交谈。\n人物关系：合作。\n关键事件：交谈。\n后续伏笔：无。'), \
                 patch.object(app, 'compress_summaries', side_effect=compress):
                app.build_memory_job(job_id, self.a, rebuild_overview=True)
            self.assertEqual(app.JOBS[job_id]['status'], 'paused')
            self.assertTrue(app.list_chapters(self.a)[0]['summary'])
            with app.connect_db() as conn:
                self.assertFalse(app.get_summary(conn, self.a, 'archive', 'main'))
        finally:
            app.JOBS.pop(job_id, None)

    def test_archive_isolation_and_export_import(self):
        self.assertEqual(app.list_special_tags(self.b), [])
        self.assertEqual(app.list_rewrite_templates(self.b), [])
        app.save_archive_setting(self.a, "rewrite_global_prompt", "第一本专属")
        app.save_special_tag(self.a, "密室双人", "仅当两人在封闭空间", "#8844aa")
        tag = app.list_special_tags(self.a)[0]
        app.save_special_tag_hit(self.a, tag['id'], self.ca['document_id'], "第一章", "两人被困密室交谈")
        self.assertEqual(app.rewrite_state(self.b)['rewrite_global_prompt'], app.DEFAULT_PROMPTS['rewrite_global_prompt'])
        self.assertEqual(app.special_tag_hits(self.b), [])
        with self.assertRaises(ValueError):
            app.save_special_tag_hit(self.b, tag['id'], self.cb['document_id'], "第一章", "跨库")
        package = app.export_archive(self.a)
        self.assertEqual(len(package['special_tag_hits']), 1)
        imported = app.import_archive_package(package)
        self.assertEqual(len(app.special_tag_hits(imported)), 1)
        self.assertEqual(app.rewrite_state(imported)['rewrite_global_prompt'], app.DEFAULT_PROMPTS['rewrite_global_prompt'])
        self.assertEqual(len(app.special_tag_hits(self.a)), 1)

    def test_source_edit_only_changes_archive_copy_and_marks_memory_stale(self):
        source_path = os.path.join(self.tmp.name, 'original.txt')
        with open(source_path, 'w', encoding='utf-8') as handle:
            handle.write('两人在密室里交谈，门被锁住。')
        old = app.chapter_source(self.a, self.ca['document_id'], '第一章')
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章', '关键词：密室\n剧情概览：两人在交谈。')
        edited = '两人来到雪山，发现一座古堡。'
        result = app.save_chapter_source(self.a, self.ca['document_id'], '第一章', edited, old['sha256'])
        self.assertTrue(result['changed'])
        self.assertEqual(app.chapter_source(self.a, self.ca['document_id'], '第一章')['text'], edited)
        with open(source_path, encoding='utf-8') as handle:
            self.assertEqual(handle.read(), '两人在密室里交谈，门被锁住。')
        self.assertEqual(app.chapter_source(self.b, self.cb['document_id'], '第一章')['text'], '一群人在草原上奔跑。')
        chapter = app.list_chapters(self.a)[0]
        self.assertTrue(chapter['source_edited'])
        self.assertEqual(chapter['status'], '原文已修改·待更新记忆')
        self.assertEqual(chapter['char_count'], len(edited))
        self.assertEqual(app.relevant_summaries(self.a, [{'document_id': self.ca['document_id'], 'title': '第一章'}])['chapters'], [])
        with app.connect_db() as conn:
            self.assertEqual(conn.execute('SELECT char_count FROM documents WHERE id=?', (self.ca['document_id'],)).fetchone()[0], len(edited))
            indexed = {row[0] for row in conn.execute('SELECT term FROM term_index WHERE archive_id=?', (self.a,))}
        self.assertIn('雪山', indexed)
        self.assertNotIn('密室', indexed)
        package = app.export_archive(self.a)
        self.assertEqual(package['version'], 4)
        imported = app.import_archive_package(package)
        self.assertTrue(app.list_chapters(imported)[0]['source_edited'])
        self.assertEqual(app.chapter_source(imported, app.list_chapters(imported)[0]['document_id'], '第一章')['text'], edited)
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章', '关键词：雪山\n剧情概览：抵达古堡。')
        self.assertFalse(app.list_chapters(self.a)[0]['source_edited'])

    def test_source_edit_rejects_stale_version_and_wrong_archive(self):
        old = app.chapter_source(self.a, self.ca['document_id'], '第一章')
        with self.assertRaises(ValueError):
            app.save_chapter_source(self.b, self.ca['document_id'], '第一章', '跨库编辑', old['sha256'])
        with self.assertRaises(ValueError):
            app.save_chapter_source(self.a, self.ca['document_id'], '第一章', ' ', old['sha256'])
        app.save_chapter_source(self.a, self.ca['document_id'], '第一章', '首次修改', old['sha256'])
        with self.assertRaises(ValueError):
            app.save_chapter_source(self.a, self.ca['document_id'], '第一章', '覆盖修改', old['sha256'])
        self.assertEqual(app.chapter_source(self.a, self.ca['document_id'], '第一章')['text'], '首次修改')

    def test_source_edit_marker_follows_rename_and_is_removed_on_delete(self):
        old = app.chapter_source(self.a, self.ca['document_id'], '第一章')
        app.save_chapter_source(self.a, self.ca['document_id'], '第一章', '新的正文', old['sha256'])
        app.rename_chapter(self.a, self.ca['document_id'], '第一章', '序章')
        self.assertTrue(app.list_chapters(self.a)[0]['source_edited'])
        app.delete_chapters(self.a, [{'document_id': self.ca['document_id'], 'title': '序章'}])
        with app.connect_db() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM chapter_source_edits WHERE archive_id=?', (self.a,)).fetchone()[0], 0)

    def test_rewrite_status_remains_visible_after_source_edit(self):
        initial = app.rewrite_state(self.a)['chapters'][0]
        self.assertFalse(initial['has_rewrite'])
        self.assertEqual(initial['rewrite_status'], '未改写')
        app.save_rewrite_content(self.a, self.ca['document_id'], '第一章', '已改写的正文')
        done = app.rewrite_state(self.a)['chapters'][0]
        self.assertTrue(done['has_rewrite'])
        self.assertEqual(done['rewrite_status'], '草稿')
        old = app.chapter_source(self.a, self.ca['document_id'], '第一章')
        app.save_chapter_source(self.a, self.ca['document_id'], '第一章', '修改后的原文', old['sha256'])
        review = app.rewrite_state(self.a)['chapters'][0]
        self.assertTrue(review['has_rewrite'])
        self.assertEqual(review['rewrite_status'], '已改写·章节记忆待更新')

    def test_rewrite_instructions_persist_per_chapter_without_changing_kb(self):
        second = app.add_chapter(self.a, '第二章', '主角离开密室。')['chapters'][-1]
        project = app.default_rewrite_project(self.a)
        original = app.chapter_source(self.a, self.ca['document_id'], '第一章')['text']
        app.save_rewrite_content(self.a, self.ca['document_id'], '第一章', '改写稿甲',
                                 '突出两人关系', extra_instruction='使用近景描写')
        state = app.rewrite_state(self.a, basic=True)['chapters']
        first = next(row for row in state if row['title'] == '第一章')
        untouched = next(row for row in state if row['title'] == '第二章')
        self.assertEqual(first['chapter_prompt'], '突出两人关系')
        self.assertEqual(first['rewrite_extra_instruction'], '使用近景描写')
        self.assertTrue(first['has_rewrite'])
        self.assertEqual(untouched['chapter_prompt'], '')
        self.assertEqual(untouched['rewrite_extra_instruction'], '')
        self.assertFalse(untouched['has_rewrite'])
        reloaded = app.storyline.project_state(self.db, project['id'], self.a)
        key = app.storyline.chapter_key(self.ca['document_id'], '第一章')
        self.assertEqual(reloaded['chapter_data'][key]['extra_instruction'], '使用近景描写')
        self.assertEqual(app.chapter_source(self.a, self.ca['document_id'], '第一章')['text'], original)
        self.assertEqual(app.rewrite_state(self.b, basic=True)['chapters'][0]['rewrite_extra_instruction'], '')
        app.save_rewrite_content(self.a, second['document_id'], '第二章', '旧版改写稿')
        legacy = next(row for row in app.rewrite_state(self.a, basic=True)['chapters'] if row['title'] == '第二章')
        self.assertTrue(legacy['has_rewrite'])
        self.assertEqual(legacy['rewrite_extra_instruction'], '')

    def test_portable_rewrite_bundle_round_trip_keeps_kb_separate(self):
        app.save_rewrite_content(self.a, self.ca['document_id'], '第一章', '手机改写正文',
                                 '章节提示词', extra_instruction='额外要求')
        bundle = app.export_rewrite_bundle(self.a)
        self.assertEqual(bundle['format'], 'novel-memory-rewrites')
        self.assertEqual(bundle['chapters'][0]['extra_instruction'], '额外要求')
        imported = app.import_archive_package(app.export_archive(self.a))
        self.assertFalse(app.rewrite_state(imported, basic=True)['chapters'][0]['has_rewrite'])
        result = app.import_rewrite_bundle(imported, bundle)
        self.assertEqual(result['imported'], 1)
        restored = app.rewrite_state(imported, basic=True)['chapters'][0]
        self.assertEqual(restored['rewrite_content'], '手机改写正文')
        self.assertEqual(restored['chapter_prompt'], '章节提示词')
        self.assertEqual(restored['rewrite_extra_instruction'], '额外要求')
        self.assertEqual(app.export_archive(imported)['rewrites'], [])

    def test_model_scan_preserves_manual_and_rejects_bad_json(self):
        app.save_special_tag(self.a, "密室双人", "两人同处密室", "#8844aa")
        tag = app.list_special_tags(self.a)[0]
        app.save_special_tag_hit(self.a, tag['id'], self.ca['document_id'], "第一章", "人工确认的密室情节")
        with patch.object(app, "call_chat_api", return_value='{"matches":[{"tag":"密室双人","description":"模型检测的场景"}]}'):
            app.scan_special_tags_for_chapter(self.a, self.ca['document_id'], "第一章")
        hits = app.special_tag_hits(self.a)
        self.assertEqual(hits[0]['description'], "人工确认的密室情节")
        with patch.object(app, "call_chat_api", return_value="无效 JSON"):
            with self.assertRaises(ValueError):
                app.scan_special_tags_for_chapter(self.a, self.ca['document_id'], "第一章")
        self.assertEqual(app.special_tag_hits(self.a)[0]['description'], "人工确认的密室情节")

    def test_chapter_summary_generates_special_hits_in_same_api_call(self):
        app.save_special_tag(self.a, '密室双人', '仅当两人在封闭空间', '#8844aa')
        tag = app.list_special_tags(self.a)[0]
        response = ('关键词：交谈、密室\n剧情概览：两人在密室交谈。\n人物与关系：两人合作。\n'
                    '地点/物品/设定：密室。\n伏笔与未解问题：门为何锁住。\n'
                    f'特殊标签JSON：{{"matches":[{{"id":{tag["id"]},"description":"两人被困密室交谈","evidence":"门被锁住"}}]}}')
        with patch.object(app, 'call_chat_api', return_value=response) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('integrated-tags-test', self.a)
        self.assertEqual(model.call_count, 1)
        self.assertIn('仅当两人在封闭空间', model.call_args.args[0][1]['content'])
        self.assertEqual(app.JOBS['integrated-tags-test']['status'], 'done')
        chapter = app.list_chapters(self.a)[0]
        self.assertEqual(chapter['status'], '已总结')
        self.assertNotIn('特殊标签JSON', chapter['summary'])
        self.assertEqual(app.special_tag_hits(self.a)[0]['description'], '两人被困密室交谈')
        package = app.export_archive(self.a)
        self.assertEqual(len(package['special_tag_hits']), 1)

        app.save_special_tag_hit(self.a, tag['id'], self.ca['document_id'], '第一章', '人工修订的密室情节')
        with patch.object(app, 'call_chat_api', return_value=response.replace('两人被困密室交谈', '模型再次改写')) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('integrated-tags-rerun', self.a, force=True)
        self.assertEqual(model.call_count, 1)
        self.assertEqual(app.special_tag_hits(self.a)[0]['description'], '人工修订的密室情节')

    def test_invalid_integrated_tag_json_keeps_summary_and_previous_hit(self):
        app.save_special_tag(self.a, '密室双人', '两人在密室', '#8844aa')
        tag = app.list_special_tags(self.a)[0]
        with app.connect_db() as conn:
            app.replace_model_special_tag_hits(conn, self.a, self.ca['document_id'], '第一章',
                                               {tag['id']: ('原有模型标签', '')})
        response = ('关键词：密室\n剧情概览：两人交谈。\n人物与关系：合作。\n'
                    '地点/物品/设定：密室。\n伏笔与未解问题：门。\n特殊标签JSON：{错误')
        with patch.object(app, 'call_chat_api', return_value=response) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('integrated-tags-invalid', self.a)
        self.assertEqual(model.call_count, 1)
        self.assertEqual(app.JOBS['integrated-tags-invalid']['status'], 'done')
        self.assertNotIn('特殊标签JSON', app.list_chapters(self.a)[0]['summary'])
        self.assertEqual(app.special_tag_hits(self.a)[0]['description'], '原有模型标签')

        empty_response = response.split('特殊标签JSON：')[0] + '特殊标签JSON：{"matches":[]}'
        with patch.object(app, 'call_chat_api', return_value=empty_response), \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('integrated-tags-empty', self.a, force=True)
        self.assertEqual(app.special_tag_hits(self.a), [])

    def test_batch_scan_uses_only_plot_and_preserves_manual_hits(self):
        app.save_special_tag(self.a, '密室双人', '两人在封闭空间', '#8844aa')
        tag = app.list_special_tags(self.a)[0]
        second = app.add_chapter(self.a, '第二章', '仅原文中才有的秘密门。')['chapters'][-1]
        app.add_chapter(self.a, '第三章', '尚无章节总结。')
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章',
                                 '关键词：交谈\n剧情概览：两人在密室里交谈，门被锁住。')
        app.save_chapter_summary(self.a, second['document_id'], '第二章',
                                 '关键词：草原\n剧情概览：众人来到草原。')
        response = {'chapters': [
            {'key': 'C1', 'matches': [{'tag_id': tag['id'], 'description': '两人密室交谈',
                                      'evidence': '两人在密室里交谈'}]},
            {'key': 'C2', 'matches': [{'tag_id': tag['id'], 'description': '不应接受的猜测',
                                      'evidence': '原文中才有的秘密门'}]},
        ]}
        with patch.object(app, 'call_chat_api', return_value=json.dumps(response, ensure_ascii=False)) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.scan_special_tags_job('batch-only-plot', self.a, [])
        self.assertEqual(model.call_count, 1)
        self.assertNotIn('仅原文中才有的秘密门', model.call_args.args[0][1]['content'])
        self.assertIn('众人来到草原', model.call_args.args[0][1]['content'])
        self.assertEqual(app.JOBS['batch-only-plot']['skipped'], 1)
        self.assertEqual(app.JOBS['batch-only-plot']['batch_total'], 1)
        hits = app.special_tag_hits(self.a)
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0]['description'], '两人密室交谈')
        app.save_special_tag_hit(self.a, tag['id'], self.ca['document_id'], '第一章', '人工确认')
        with patch.object(app, 'call_chat_api', return_value=json.dumps(response, ensure_ascii=False)), \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.scan_special_tags_job('batch-manual-safe', self.a, [])
        self.assertEqual(app.special_tag_hits(self.a)[0]['description'], '人工确认')

    def test_batch_scan_failure_keeps_old_results_and_only_retries_failed_batch(self):
        app.save_special_tag(self.a, '密室双人', '两人在封闭空间', '#8844aa')
        tag = app.list_special_tags(self.a)[0]
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章',
                                 '关键词：交谈\n剧情概览：两人在密室里交谈，门被锁住。')
        with app.connect_db() as conn:
            app.replace_model_special_tag_hits(conn, self.a, self.ca['document_id'], '第一章',
                                               {tag['id']: ('旧有结果', '两人在密室里交谈')})
        with patch.object(app, 'call_chat_api', return_value='invalid JSON') as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.scan_special_tags_job('batch-failed', self.a, [])
        self.assertEqual(model.call_count, 2)
        self.assertEqual(app.JOBS['batch-failed']['failed'], 1)
        self.assertEqual(app.special_tag_hits(self.a)[0]['description'], '旧有结果')

    def test_batch_limits(self):
        chapters = [{'plot': '情节'} for _ in range(25)]
        self.assertEqual([len(batch) for batch in app.batch_special_tag_chapters(chapters)], [12, 12, 1])

    def test_template_scope_filter_and_rewrite(self):
        app.save_special_tag(self.a, "密室双人", "两人同处密室", "#8844aa")
        tag = app.list_special_tags(self.a)[0]
        config = {"name": "密室模板", "tags": [{"name": "密室双人", "color": "#8844aa", "recognition_prompt": "两人同处密室"}],
                  "filter": {"mode": "all", "tags": ["密室双人"]},
                  "rewrite": {"global_prompt": "模板全局", "chapter_prompt": "每章精修", "rules": "保持时间线"}}
        app.save_rewrite_template(self.a, config)
        template_id = app.list_rewrite_templates(self.a)[0]['id']
        self.assertEqual(app.list_rewrite_templates(self.b), [])
        with self.assertRaises(ValueError):
            app.rewrite_chapter(self.a, self.ca['document_id'], "第一章", template_id=template_id)
        app.save_special_tag_hit(self.a, tag['id'], self.ca['document_id'], "第一章", "两人在密室交谈")
        with patch.object(app, "call_chat_api", return_value="改写正文") as model:
            result = app.rewrite_chapter(self.a, self.ca['document_id'], "第一章", template_id=template_id)
        self.assertEqual(result['content'], "改写正文")
        messages = model.call_args.args[0]
        self.assertIn("保持时间线", messages[1]['content'])
        self.assertIn("两人在密室交谈", messages[1]['content'])
        with self.assertRaises(ValueError):
            app.rewrite_chapter(self.b, self.cb['document_id'], "第一章", template_id=template_id)

    def test_rewrite_projects_keep_kb_unchanged_and_isolate_storylines(self):
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章', '关键词：密室\n剧情概览：两人被困在密室。')
        with app.connect_db() as conn:
            before = [tuple(row) for row in conn.execute('SELECT archive_id,document_id,title,text FROM chunks ORDER BY id')]
            summary_before = [tuple(row) for row in conn.execute('SELECT archive_id,scope,source_key,content FROM summaries ORDER BY id')]
        first = app.storyline.create_project(self.db, self.a, '改写甲')
        second = app.storyline.create_project(self.db, self.a, '改写乙')
        self.assertEqual(first['original_nodes'][0]['chapter_title'], '第一章')
        self.assertIn('两人被困', first['original_nodes'][0]['text'])
        node_id = first['original_nodes'][0]['id']
        app.storyline.save_overlay(self.db, first['id'], self.a, {'kind': 'insert', 'anchor_id': node_id,
                                                                   'placement': 'after', 'text': '主角找到一条暗道',
                                                                   'region': '密室', 'characters': '主角'})
        app.save_rewrite_content(self.a, self.ca['document_id'], '第一章', '改写后的正文', project_id=first['id'])
        app.storyline.save_chapter(self.db, first['id'], self.a, self.ca['document_id'], '第一章', status='已确认')
        self.assertEqual(app.rewrite_state(self.a, first['id'])['chapters'][0]['rewrite_status'], '已确认')
        self.assertFalse(app.rewrite_state(self.a, second['id'])['chapters'][0]['has_rewrite'])
        self.assertFalse(app.storyline.project_state(self.db, second['id'], self.a)['overlay_nodes'])
        path = app.storyline.export_manuscript(self.db, first['id'], self.a)
        with open(path, encoding='utf-8') as handle:
            self.assertIn('改写后的正文', handle.read())
        package = app.export_archive(self.a)
        self.assertEqual(package['rewrites'], [])
        with app.connect_db() as conn:
            self.assertEqual(before, [tuple(row) for row in conn.execute('SELECT archive_id,document_id,title,text FROM chunks ORDER BY id')])
            self.assertEqual(summary_before, [tuple(row) for row in conn.execute('SELECT archive_id,scope,source_key,content FROM summaries ORDER BY id')])
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM rewrites').fetchone()[0], 0)

    def test_storyline_anchor_validation_and_summary_staleness(self):
        project = app.storyline.create_project(self.db, self.a)
        node = project['original_nodes'][0]
        app.storyline.save_overlay(self.db, project['id'], self.a, {'kind': 'annotation',
                                                                    'anchor_id': node['id'], 'text': '保留此处'})
        with self.assertRaises(ValueError):
            app.storyline.save_overlay(self.db, project['id'], self.a, {'kind': 'insert', 'anchor_id': 'wrong', 'text': '无效'})
        with self.assertRaises(ValueError):
            app.storyline.project_state(self.db, project['id'], self.b)
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章', '剧情概览：新的情节')
        updated = app.storyline.project_state(self.db, project['id'], self.a)
        self.assertEqual(updated['original_nodes'][0]['anchor_status'], '摘要已变化')
        self.assertEqual(updated['original_nodes'][0]['id'], node['id'])
        refreshed = app.storyline.refresh_original_nodes(self.db, project['id'], self.a)
        self.assertEqual(refreshed['original_nodes'][0]['anchor_status'], '正常')
        self.assertEqual(refreshed['overlay_nodes'][0]['anchor_id'], node['id'])

    def test_legacy_rewrites_copied_once_and_new_storyline_starts_empty(self):
        with app.connect_db() as conn:
            conn.execute('INSERT INTO rewrites(archive_id,document_id,title,content,prompt,created_at,updated_at) '
                         'VALUES(?,?,?,?,?,?,?)', (self.a, self.ca['document_id'], '第一章', '旧版改写', '旧提示', 1, 1))
        first = app.storyline.create_project(self.db, self.a, copy_legacy=True)
        second = app.storyline.create_project(self.db, self.a)
        self.assertEqual(app.storyline.get_chapter(self.db, first['id'], self.a, self.ca['document_id'], '第一章')['content'], '旧版改写')
        self.assertEqual(app.storyline.get_chapter(self.db, second['id'], self.a, self.ca['document_id'], '第一章')['content'], '')
        self.assertEqual(app.export_archive(self.a)['rewrites'], [])
        with app.connect_db() as conn:
            self.assertEqual(conn.execute('SELECT content FROM rewrites').fetchone()[0], '旧版改写')

    def test_long_rewrite_sends_whole_chapter_once(self):
        text = '甲' * 7000
        chapter = app.add_chapter(self.a, '第二章', text)['chapters'][-1]
        estimate = app.rewrite_estimate(self.a, chapter['document_id'], '第二章')
        self.assertEqual(estimate['requests'], 2)  # one full request plus one optional truncation continuation
        self.assertGreater(estimate['maximum_output_tokens'], 1600)
        with self.assertRaises(ValueError):
            app.rewrite_chapter(self.a, chapter['document_id'], '第二章', max_calls=0)
        self.assertFalse(app.rewrite_state(self.a)['chapters'][-1]['has_rewrite'])
        with patch.object(app, 'call_chat_api', return_value='改写正文') as model:
            result = app.rewrite_chapter(self.a, chapter['document_id'], '第二章', max_calls=1)
        self.assertEqual(result['segments'], 1)
        self.assertEqual(model.call_count, 1)
        self.assertIn(text, model.call_args.args[0][1]['content'])
        self.assertTrue(model.call_args.args[1]['_preserve_input'])
        self.assertTrue(model.call_args.args[1]['_single_request'])

    def test_rewrite_removes_long_internal_loop_but_keeps_short_refrain(self):
        paragraph = '他再次确认门已经锁上。' * 15
        response = paragraph + '\n\n她开始寻找钥匙。\n\n' + paragraph
        self.assertEqual(app.remove_internal_rewrite_repeats(response),
                         paragraph + '\n\n她开始寻找钥匙。')
        refrain = '不要回头。\n\n她走进了走廊。\n\n不要回头。'
        self.assertEqual(app.remove_internal_rewrite_repeats(refrain), refrain)

    def test_whole_rewrite_rejects_truncated_response_without_saving(self):
        second = app.add_chapter(self.a, '第二章', '原文甲。\n原文乙。')['chapters'][-1]
        truncated = {'choices': [{'finish_reason': 'length', 'message': {'content': '只生成了开头'}}]}
        with patch.object(app, 'request_api_json', return_value=truncated) as request:
            with self.assertRaisesRegex(RuntimeError, '截断'):
                app.rewrite_chapter(self.a, second['document_id'], '第二章', max_calls=1)
        self.assertEqual(request.call_count, 2)
        self.assertEqual(request.call_args.kwargs['attempts'], 1)
        self.assertIn('原文甲。\n原文乙。', request.call_args.kwargs['body']['messages'][1]['content'])
        self.assertFalse(app.rewrite_state(self.a, basic=True)['chapters'][-1]['has_rewrite'])
        self.assertTrue(app.rewrite_state(self.a, basic=True)['chapters'][-1]['has_partial_rewrite'])

    def test_rewrite_continuation_joins_and_logs_actual_usage(self):
        first = {'choices': [{'finish_reason': 'length', 'message': {'content': '改写的前半段'}}], 'usage': {'prompt_tokens': 20, 'completion_tokens': 30}}
        second = {'choices': [{'finish_reason': 'stop', 'message': {'content': '改写的后半段'}}], 'usage': {'prompt_tokens': 10, 'completion_tokens': 15}}
        with patch.object(app, 'request_api_json', side_effect=[first, second]) as request:
            result = app.rewrite_chapter(self.a, self.ca['document_id'], '第一章')
        self.assertEqual(request.call_count, 2)
        self.assertEqual(result['content'], '改写的前半段\n\n改写的后半段')
        self.assertFalse(app.rewrite_state(self.a, basic=True)['chapters'][0]['has_partial_rewrite'])
        self.assertEqual(app.rewrite_usage_totals(self.a)['input_tokens'], 30)
        self.assertEqual(app.rewrite_usage_totals(self.a)['output_tokens'], 45)

    def test_job_checkpoint_survives_restart_as_interrupted(self):
        job_id = 'checkpoint-restart-test'
        app.JOBS[job_id] = {'id': job_id, 'archive_id': self.a, 'kind': 'memory', 'status': 'running', 'created_at': app.now_ts()}
        app.set_job(job_id, done=1, total=3, message='第一章已完成')
        app.JOBS.pop(job_id)
        app.init_db()
        job = app.JOBS[job_id]
        self.assertEqual(job['status'], 'interrupted')
        self.assertEqual(job['done'], 1)
        self.assertEqual(job['total'], 3)
        self.assertFalse(app.active_job_for_archive(self.a))

    def test_rewrite_output_limit_is_saved_and_used_without_old_ceiling(self):
        saved = app.save_config({'provider': 'deepseek', 'rewrite_max_tokens': '24576'})
        self.assertEqual(saved['rewrite_max_tokens'], '24576')
        self.assertEqual(app.current_config()['rewrite_max_tokens'], '24576')
        with patch.object(app, 'request_api_json', return_value={
            'choices': [{'finish_reason': 'stop', 'message': {'content': '完整改写正文'}}]
        }) as request:
            app.rewrite_chapter(self.a, self.ca['document_id'], '第一章')
        self.assertEqual(request.call_args.kwargs['body']['max_tokens'], 24576)
        self.assertEqual(app.rewrite_estimate(self.a, self.ca['document_id'], '第一章')['maximum_output_tokens'], 24576)
        with self.assertRaisesRegex(ValueError, '改写最大输出 Token'):
            app.save_config({'provider': 'deepseek', 'rewrite_max_tokens': '40000'})
        app.save_config({'provider': 'deepseek', 'rewrite_max_tokens': '0'})
        self.assertEqual(app.current_config()['rewrite_max_tokens'], '0')

    def test_whole_rewrite_saves_complete_single_response(self):
        complete = {'choices': [{'finish_reason': 'stop', 'message': {'content': '完整改写正文'}}]}
        with patch.object(app, 'request_api_json', return_value=complete) as request:
            result = app.rewrite_chapter(self.a, self.ca['document_id'], '第一章', max_calls=1)
        self.assertEqual(request.call_count, 1)
        self.assertEqual(request.call_args.kwargs['attempts'], 1)
        self.assertEqual(result['segments'], 1)
        self.assertEqual(result['content'], '完整改写正文')
        self.assertEqual(app.rewrite_state(self.a, basic=True)['chapters'][0]['rewrite_content'], '完整改写正文')

    def test_model_rewrite_saves_chapter_extra_instruction(self):
        with patch.object(app, 'call_chat_api', return_value='完整改写正文') as model:
            app.rewrite_chapter(self.a, self.ca['document_id'], '第一章', user_instruction='本章只用第一人称')
        self.assertIn('[本章额外要求]\n本章只用第一人称', model.call_args.args[0][1]['content'])
        chapter = app.rewrite_state(self.a, basic=True)['chapters'][0]
        self.assertEqual(chapter['rewrite_extra_instruction'], '本章只用第一人称')

    def test_whole_rewrite_never_compacts_local_source(self):
        config = {'provider': 'lmstudio', 'chat_model': 'test', 'max_tokens': '1600',
                  '_ensure_final_answer': True, '_preserve_input': True, '_single_request': True}
        messages = [{'role': 'system', 'content': '改写'},
                    {'role': 'user', 'content': '甲' * 10000}]
        with patch.object(app, 'request_api_json') as request:
            with self.assertRaisesRegex(ValueError, '未调用 API'):
                app.call_chat_api(messages, config)
        request.assert_not_called()

    def test_basic_rewrite_needs_no_storyline_and_uses_archive_prompt(self):
        app.save_chapter_summary(self.a, self.ca['document_id'], '第一章',
                                 '关键词：密室\n剧情概览：两人在密室交谈。')
        app.save_archive_setting(self.a, 'rewrite_global_prompt', '基础改写总规则：保持叙事人称')
        project = app.storyline.create_project(self.db, self.a, '旧故事线')
        app.storyline.save_overlay(self.db, project['id'], self.a,
                                   {'kind': 'annotation', 'anchor_id': project['original_nodes'][0]['id'],
                                    'text': '不应进入基础改写的旧批注'})
        state = app.rewrite_state(self.a, basic=True)
        self.assertEqual(state['project_id'], project['id'])
        self.assertEqual(state['rewrite_global_prompt'], '基础改写总规则：保持叙事人称')
        with patch.object(app, 'call_chat_api', return_value='基础改写正文') as model:
            result = app.rewrite_chapter(self.a, self.ca['document_id'], '第一章')
        self.assertEqual(result['content'], '基础改写正文')
        self.assertIn('基础改写总规则：保持叙事人称', model.call_args.args[0][0]['content'])
        self.assertNotIn('不应进入基础改写的旧批注', model.call_args.args[0][1]['content'])
        self.assertNotIn('当前章节改写故事线节点', model.call_args.args[0][1]['content'])
        self.assertEqual(app.rewrite_state(self.a, basic=True)['chapters'][0]['rewrite_content'], '基础改写正文')
        self.assertEqual(app.list_chapters(self.a)[0]['memory']['plot'], '两人在密室交谈。')

    def test_rewrite_list_reuses_one_project_manifest(self):
        app.add_chapter(self.a, '第二章', '主角离开密室。')
        app.add_chapter(self.a, '第三章', '主角来到旷野。')
        project = app.storyline.create_project(self.db, self.a)
        with patch.object(app.storyline, '_manifest', wraps=app.storyline._manifest) as manifest:
            chapters = app.rewrite_state(self.a, basic=True)['chapters']
        self.assertEqual(len(chapters), 3)
        self.assertTrue(all('rewrite_status' in chapter for chapter in chapters))
        self.assertLessEqual(manifest.call_count, 2)

    def test_storyline_http_flow(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        def post(route, payload):
            request = urllib.request.Request(base + route, data=json.dumps(payload).encode('utf-8'),
                                             headers={'Content-Type': 'application/json'}, method='POST')
            with urllib.request.urlopen(request, timeout=5) as response:
                return json.load(response)
        try:
            with urllib.request.urlopen(base + '/', timeout=5) as response:
                html = response.read().decode('utf-8')
            self.assertNotIn('storyTimelineSection', html)
            self.assertIn('rewriteKeywordSearch', html)
            result = post('/api/rewrite/project/create', {'archive_id': self.a, 'name': 'HTTP 测试'})
            project = result['project']
            project_id = project['id']
            node_id = project['original_nodes'][0]['id']
            post('/api/rewrite/node', {'archive_id': self.a, 'project_id': project_id,
                                       'kind': 'annotation', 'anchor_id': node_id, 'text': '这里改变结局'})
            post('/api/rewrite/save', {'archive_id': self.a, 'project_id': project_id,
                                       'document_id': self.ca['document_id'], 'title': '第一章', 'content': '新正文'})
            with urllib.request.urlopen(base + f'/api/rewrite_state/{self.a}?project_id={project_id}', timeout=5) as response:
                state = json.load(response)
            self.assertEqual(state['chapters'][0]['rewrite_content'], '新正文')
            self.assertEqual(len(state['project']['overlay_nodes']), 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_retired_generation_surface_is_removed(self):
        self.assertFalse(hasattr(app, 'generation_store'))
        self.assertFalse(hasattr(app, 'generation_writer'))
        self.assertFalse(hasattr(app, 'GENERATION_THREADS'))
        self.assertNotIn('generationView', app.UI_HTML)
        self.assertNotIn('navGeneration', app.UI_HTML)
        self.assertNotIn('/api/generation', app.UI_HTML)
        server = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            cases = [
                ('GET', '/api/generation/projects', None),
                ('GET', '/api/generation/project/legacy', None),
                ('POST', '/api/generation/create', {'title': '旧工程'}),
                ('POST', '/api/generation/start', {'id': 'legacy'}),
            ]
            for method, route, payload in cases:
                data = json.dumps(payload).encode('utf-8') if payload is not None else None
                request = urllib.request.Request(base + route, data=data,
                                                 headers={'Content-Type': 'application/json'},
                                                 method=method)
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(request, timeout=5)
                self.assertEqual(caught.exception.code, 404)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_basic_rewrite_http_flow_without_project_id(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        def post(route, payload):
            request = urllib.request.Request(base + route, data=json.dumps(payload).encode('utf-8'),
                                             headers={'Content-Type': 'application/json'}, method='POST')
            with urllib.request.urlopen(request, timeout=5) as response:
                return json.load(response)
        try:
            post('/api/archive_setting', {'archive_id': self.a, 'key': 'rewrite_global_prompt',
                                          'value': '基础改写总规则'})
            with urllib.request.urlopen(base + f'/api/rewrite_state/{self.a}?basic=1', timeout=5) as response:
                state = json.load(response)
            self.assertTrue(state['project_id'])
            self.assertEqual(state['rewrite_global_prompt'], '基础改写总规则')
            post('/api/rewrite/prompt', {'archive_id': self.a, 'document_id': self.ca['document_id'],
                                         'title': '第一章', 'prompt': '保持对话节奏',
                                         'extra_instruction': '优先写人物动作'})
            with patch.object(app, 'call_chat_api', return_value='HTTP 改写正文') as model:
                result = post('/api/rewrite/chapter', {'archive_id': self.a,
                              'document_id': self.ca['document_id'], 'title': '第一章',
                              'user_instruction': '优先写人物动作', 'max_calls': 1})
            self.assertEqual(result['content'], 'HTTP 改写正文')
            self.assertIn('基础改写总规则', model.call_args.args[0][0]['content'])
            self.assertIn('保持对话节奏', model.call_args.args[0][1]['content'])
            with urllib.request.urlopen(base + f'/api/rewrite_state/{self.a}?basic=1', timeout=5) as response:
                saved = json.load(response)
            self.assertEqual(saved['chapters'][0]['rewrite_content'], 'HTTP 改写正文')
            self.assertEqual(saved['chapters'][0]['rewrite_extra_instruction'], '优先写人物动作')
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_standalone_chinese_numeral_sections(self):
        sections = app.split_text('一\n第一段。\n二\n第二段。\n三\n第三段。')
        self.assertEqual([item[0] for item in sections], ['一', '二', '三'])

    def test_rewrite_prompt_uses_overlay_and_accepted_previous_chapter(self):
        second = app.add_chapter(self.a, '第二章', '主角来到广场。')['chapters'][-1]
        project = app.storyline.create_project(self.db, self.a)
        app.storyline.save_chapter(self.db, project['id'], self.a, self.ca['document_id'], '第一章',
                                   content='确认的第一章结尾', status='已确认')
        node = next(n for n in project['original_nodes'] if n['chapter_title'] == '第二章')
        app.storyline.save_overlay(self.db, project['id'], self.a, {'kind': 'insert', 'anchor_id': node['id'],
                                                                    'placement': 'after', 'text': '新角色加入队伍'})
        with patch.object(app, 'call_chat_api', return_value='改写后的第二章') as model:
            app.rewrite_chapter(self.a, second['document_id'], '第二章', project_id=project['id'])
        prompt = model.call_args.args[0][1]['content']
        self.assertIn('确认的第一章结尾', prompt)
        self.assertIn('新角色加入队伍', prompt)

    def test_chapter_lifecycle_keeps_hits_consistent(self):
        app.save_special_tag(self.a, "密室双人", "两人同处密室", "#8844aa")
        tag_id = app.list_special_tags(self.a)[0]['id']
        app.save_special_tag_hit(self.a, tag_id, self.ca['document_id'], "第一章", "密室交谈")
        app.rename_chapter(self.a, self.ca['document_id'], "第一章", "序章")
        self.assertEqual(app.special_tag_hits(self.a)[0]['chapter_title'], "序章")
        second = app.add_chapter(self.a, "第二章", "二人推开门。")['chapters'][-1]
        app.merge_chapters(self.a, [{"document_id": self.ca['document_id'], "title": "序章"},
                                    {"document_id": second['document_id'], "title": "第二章"}], "合并章")
        self.assertEqual(app.special_tag_hits(self.a)[0]['chapter_title'], "合并章")
        app.delete_chapters(self.a, [{"document_id": self.ca['document_id'], "title": "合并章"}])
        self.assertEqual(app.special_tag_hits(self.a), [])

    def test_http_routes_and_ui_payload(self):
        self.assertIn('sceneWorkspace', app.UI_HTML)
        self.assertIn('rewriteScenePanel', app.UI_HTML)
        self.assertIn('saveChapterSourceBtn', app.UI_HTML)
        self.assertIn('onChapterRowClick(event', app.UI_HTML)
        self.assertIn('shift-select-mode', app.UI_HTML)
        self.assertIn('chapterSelectionHint', app.UI_HTML)
        self.assertIn('cell.insertAdjacentHTML(\'afterbegin\',pillsFor(matches))', app.UI_HTML)
        self.assertIn('scene-memory-field', app.UI_HTML)
        self.assertIn('从特殊关键词库点选', app.UI_HTML)
        self.assertIn('rewrite-tag-choice input:checked', app.UI_HTML)
        self.assertIn('c.chapter_prompt || ""', app.UI_HTML)
        self.assertIn("navigator.sendBeacon('/api/client/close'", app.UI_HTML)
        server = app.QuietThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            def post(path, value):
                request = urllib.request.Request(base + path, json.dumps(value).encode('utf-8'),
                                                 {'Content-Type': 'application/json'}, method='POST')
                with urllib.request.urlopen(request, timeout=5) as response:
                    return json.load(response)

            posted = post('/api/special_tag', {'archive_id': self.a, 'name': '密室双人',
                      'recognition_prompt': '两人在封闭空间', 'color': '#8844aa'})
            self.assertEqual(len(posted['tags']), 1)
            original = app.chapter_source(self.a, self.ca['document_id'], '第一章')
            saved = post('/api/chapter/source', {'archive_id': self.a, 'document_id': self.ca['document_id'],
                         'title': '第一章', 'text': '网页提交的新正文', 'expected_sha256': original['sha256']})
            self.assertTrue(saved['changed'])
            self.assertEqual(saved['sha256'], hashlib.sha256('网页提交的新正文'.encode('utf-8')).hexdigest())
            with urllib.request.urlopen(base + f'/api/special_tags/{self.b}', timeout=5) as response:
                self.assertEqual(json.load(response)['tags'], [])
            with urllib.request.urlopen(base + f'/api/archive_setting/{self.b}', timeout=5) as response:
                self.assertEqual(json.load(response)['rewrite_global_prompt'], app.DEFAULT_PROMPTS['rewrite_global_prompt'])
            config = {'name': '双人模板', 'tags': [{'name': '密室双人', 'recognition_prompt': '两人在封闭空间'}],
                      'filter': {'mode': 'all', 'tags': ['密室双人']},
                      'rewrite': {'global_prompt': '改写', 'rules': '保留事件', 'chapter_prompt': ''}}
            self.assertEqual(len(post('/api/rewrite_template', {'archive_id': self.a, 'config': config})['templates']), 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_browser_client_tracker_preserves_other_tabs_and_reload(self):
        tracker = app.BrowserClientTracker(close_grace_seconds=8, stale_seconds=120)
        self.assertFalse(tracker.should_shutdown(now=100))
        tracker.heartbeat('first-tab', now=100)
        tracker.heartbeat('second-tab', now=100)
        tracker.close('first-tab', now=101)
        self.assertFalse(tracker.should_shutdown(now=200))
        tracker.close('second-tab', now=201)
        self.assertFalse(tracker.should_shutdown(now=208))
        tracker.heartbeat('reloaded-tab', now=208)
        self.assertFalse(tracker.should_shutdown(now=220))
        tracker.close('reloaded-tab', now=221)
        self.assertFalse(tracker.should_shutdown(now=228))
        self.assertTrue(tracker.should_shutdown(now=229))

    def test_browser_client_tracker_expires_abandoned_tab(self):
        tracker = app.BrowserClientTracker(close_grace_seconds=8, stale_seconds=120)
        tracker.heartbeat('abandoned-tab', now=100)
        self.assertFalse(tracker.should_shutdown(now=220))
        self.assertFalse(tracker.should_shutdown(now=221))
        self.assertTrue(tracker.should_shutdown(now=229))

    def test_legacy_migration_does_not_fill_empty_archive(self):
        legacy = os.path.join(self.tmp.name, 'legacy.sqlite3')
        conn = sqlite3.connect(legacy)
        with conn:
            conn.executescript('''
                CREATE TABLE archives(id INTEGER PRIMARY KEY, name TEXT, description TEXT, created_at INTEGER, updated_at INTEGER);
                CREATE TABLE documents(id INTEGER PRIMARY KEY, archive_id INTEGER, filename TEXT, title TEXT, char_count INTEGER, created_at INTEGER);
                CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE prompt_templates(id INTEGER PRIMARY KEY, name TEXT, category TEXT, content TEXT, created_at INTEGER, updated_at INTEGER);
                INSERT INTO archives VALUES(10,'旧书','',0,0),(11,'空书','',0,0);
                INSERT INTO documents VALUES(1,10,'book.txt','旧书',10,0);
                INSERT INTO settings VALUES('rewrite_global_prompt','旧提示词');
                INSERT INTO prompt_templates VALUES(1,'旧模板','rewrite','旧内容',0,0);
            ''')
        conn.close()
        with patch.object(app, 'db_path', lambda: legacy):
            app.init_db()
            with app.connect_db() as conn:
                old = app.get_archive_setting(conn, 10, 'rewrite_global_prompt', '默认')
                empty = app.get_archive_setting(conn, 11, 'rewrite_global_prompt', '默认')
            self.assertEqual(old, '旧提示词')
            self.assertEqual(empty, '默认')
            self.assertEqual(len(app.list_prompt_templates('rewrite', 10)), 1)
            self.assertEqual(app.list_prompt_templates('rewrite', 11), [])


    def test_settings_never_expose_key_but_preserve_saved_key(self):
        with patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.save_config({'provider': 'deepseek', 'api_key': 'sk-secret-123456',
                             'chat_model': 'deepseek-v4-flash', 'base_url': 'https://api.deepseek.com'})
            public = app.public_provider_config(app.current_config())
            self.assertEqual(public['api_key'], '')
            self.assertTrue(public['has_key'])
            app.save_config({'provider': 'deepseek', 'api_key': '', 'chat_model': 'deepseek-v4-flash',
                             'base_url': 'https://api.deepseek.com'})
            self.assertEqual(app.current_config()['api_key'], 'sk-secret-123456')
            app.save_config({'provider': 'deepseek', 'api_key': '', 'clear_key': True,
                             'chat_model': 'deepseek-v4-flash', 'base_url': 'https://api.deepseek.com'})
            self.assertEqual(app.current_config()['api_key'], '')

    def test_api_diagnostics_classify_auth_model_and_rate_limit(self):
        seen = {"models": 0, "chat": 0}

        class Stub(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                return

            def _send(self, code, payload):
                data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(code)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _auth(self):
                return self.headers.get('Authorization', '')

            def do_GET(self):
                seen['models'] += 1
                auth = self._auth()
                if 'forbidden' in auth:
                    return self._send(403, {'error': {'message': 'forbidden'}})
                if 'badkey' in auth:
                    return self._send(401, {'error': {'message': 'Invalid API key: ' + auth}})
                if 'limited' in auth:
                    return self._send(429, {'error': {'message': 'rate limited'}})
                return self._send(200, {'data': [{'id': 'model-a'}, {'id': 'model-b'}]})

            def do_POST(self):
                seen['chat'] += 1
                length = int(self.headers.get('Content-Length', '0'))
                body = json.loads(self.rfile.read(length) or b'{}')
                auth = self._auth()
                if 'badkey' in auth:
                    return self._send(401, {'error': {'message': 'Invalid API key: ' + auth}})
                if 'noquota' in auth:
                    return self._send(402, {'error': {'message': 'insufficient quota'}})
                if 'limited' in auth:
                    return self._send(429, {'error': {'message': 'rate limited'}})
                if body.get('model') == 'missing-model':
                    return self._send(404, {'error': {'message': 'model not found'}})
                return self._send(200, {'choices': [{'message': {'content': '连接成功'}}]})

        server = ThreadingHTTPServer(('127.0.0.1', 0), Stub)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}/v1'

        def config(key='sk-goodkey123456', model='model-a', provider='openrouter'):
            return {'provider': provider, 'base_url': base, 'api_key': key,
                    'chat_model': model, 'temperature': '0.3', 'max_tokens': '1600'}

        try:
            ok = app.test_api_connection(config())
            self.assertTrue(ok['ok'])
            self.assertEqual(ok['http_status'], 200)
            self.assertEqual(ok['model_count'], 2)
            self.assertEqual(ok['answer'], '连接成功')
            self.assertIn('HTTP：200', ok['summary'])

            auth = app.test_api_connection(config(key='sk-badkey123456'))
            self.assertFalse(auth['ok'])
            self.assertEqual(auth['error_kind'], 'auth')
            self.assertEqual(auth['http_status'], 401)
            self.assertNotIn('sk-badkey123456', json.dumps(auth, ensure_ascii=False))

            forbidden = app.test_api_connection(config(key='sk-forbidden123456'))
            self.assertFalse(forbidden['ok'])
            self.assertEqual(forbidden['error_kind'], 'auth')
            self.assertEqual(forbidden['http_status'], 403)

            missing = app.test_api_connection(config(model='missing-model'))
            self.assertFalse(missing['ok'])
            self.assertEqual(missing['error_kind'], 'model_not_found')
            self.assertEqual(missing['http_status'], 404)

            limited = app.test_api_connection(config(key='sk-limited123456'))
            self.assertFalse(limited['ok'])
            self.assertEqual(limited['error_kind'], 'rate_limit')
            self.assertEqual(limited['http_status'], 429)

            quota = app.test_api_connection(config(key='sk-noquota123456'))
            self.assertFalse(quota['ok'])
            self.assertEqual(quota['error_kind'], 'quota')
            self.assertEqual(quota['http_status'], 402)

            no_key = app.test_api_connection(config(key='', provider='deepseek'))
            self.assertFalse(no_key['ok'])
            self.assertEqual(no_key['error_kind'], 'missing_key')

            seen['chat'] = 0
            listed = app.list_api_models(config())
            self.assertTrue(listed['ok'])
            self.assertEqual(listed['models'], ['model-a', 'model-b'])
            self.assertEqual(seen['chat'], 0)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_provider_defaults_are_current_and_normalized(self):
        self.assertEqual(app.PROVIDER_PRESETS['deepseek']['chat_model'], 'deepseek-v4-flash')
        self.assertEqual(app.PROVIDER_PRESETS['lmstudio']['chat_model'], '')
        self.assertEqual(app.normalize_provider_config('deepseek', {'chat_model': 'deepseek-chat'})['chat_model'],
                         'deepseek-v4-flash')
        self.assertEqual(app.normalize_base_url('openrouter', 'https://openrouter.ai/api'),
                         'https://openrouter.ai/api/v1')
        self.assertEqual(app.normalize_base_url('openrouter', 'https://openrouter.ai/api/v1/chat/completions'),
                         'https://openrouter.ai/api/v1')
        self.assertEqual(app.normalize_base_url('deepseek', 'https://api.deepseek.com/v1'),
                         'https://api.deepseek.com')
        self.assertEqual(app.PROVIDER_PRESETS['openrouter']['base_url'], 'https://openrouter.ai/api/v1')
        self.assertIn('openrouter', app.API_KEY_REQUIRED_PROVIDERS)
        self.assertEqual(app.PROVIDER_PRESETS['gemini']['base_url'], 'https://generativelanguage.googleapis.com/v1beta/openai')
        self.assertEqual(app.PROVIDER_PRESETS['glm']['base_url'], 'https://open.bigmodel.cn/api/paas/v4')
        self.assertIn('gemini', app.API_KEY_REQUIRED_PROVIDERS)
        self.assertIn('glm', app.API_KEY_REQUIRED_PROVIDERS)
        self.assertIn('value="gemini"', app.UI_HTML)
        self.assertIn('value="glm"', app.UI_HTML)
        self.assertIn('id="modelOptions"', app.UI_HTML)
        self.assertIn('id="modelSelect"', app.UI_HTML)
        self.assertIn('pickModelFromList', app.UI_HTML)
        self.assertIn('value="openrouter"', app.UI_HTML)
        self.assertIn('id="chatSettingsPanel"', app.UI_HTML)
        self.assertIn('id="modelsBtn"', app.UI_HTML)
        self.assertIn('id="chatSettingsToggle"', app.UI_HTML)
        self.assertIn('id="appBackgroundInput"', app.UI_HTML)
        self.assertIn('label.inline', app.UI_HTML)
        self.assertIn('id="chatTabSessions"', app.UI_HTML)
        self.assertIn('id="chatTabPersona"', app.UI_HTML)
        self.assertIn('id="chatTabPrompt"', app.UI_HTML)
        self.assertIn('id="blurNav"', app.UI_HTML)
        self.assertIn('id="navHoverEnabled"', app.UI_HTML)


    def test_missing_only_and_fallback_paths_make_one_chapter_call_each(self):
        app.save_special_tag(self.a, '密室双人', '仅当两人在封闭空间', '#8844aa')
        tag = app.list_special_tags(self.a)[0]
        response = ('关键词：密室\n剧情概览：两人在密室交谈。\n人物与关系：合作。\n'
                    '地点/物品/设定：密室。\n伏笔与未解问题：门。\n'
                    f'特殊标签JSON：{{"matches":[{{"id":{tag["id"]},"description":"两人被困","evidence":"门锁"}}]}}')
        with patch.object(app, 'call_chat_api', return_value=response) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('missing-tags-first', self.a)
        self.assertEqual(model.call_count, 1)
        second = app.add_chapter(self.a, '第二章', '新的章节原文，只有这一章需要补齐。')['chapters'][-1]
        with patch.object(app, 'call_chat_api', return_value=response) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('missing-tags-second', self.a, missing_only=True)
        self.assertEqual(model.call_count, 1)
        self.assertEqual(app.JOBS['missing-tags-second']['targets'], 1)
        self.assertEqual(app.JOBS['missing-tags-second']['succeeded'], 1)
        self.assertEqual(len(app.special_tag_hits(self.a)), 2)

        third = app.add_chapter(self.a, '第三章', '会失败的一章。')['chapters'][-1]
        with patch.object(app, 'call_chat_api', side_effect=RuntimeError('API 返回错误 500')) as model, \
             patch.object(app, 'data_dir', return_value=self.tmp.name):
            app.build_memory_job('fallback-one-call', self.a, missing_only=True)
        self.assertEqual(model.call_count, 1)
        self.assertEqual(app.JOBS['fallback-one-call']['status'], 'done')
        fallback = [item for item in app.list_chapters(self.a) if item['title'] == '第三章'][0]
        self.assertIn('关键词', fallback['summary'])
        self.assertIn('本地', fallback['summary'])
        self.assertEqual(fallback['status'], '需补全')
        self.assertTrue(app.is_summary_missing_or_incomplete(fallback['summary']))
        self.assertIsNotNone(third)

    def test_special_prompt_expansion_retries_reasoning_only_response(self):
        cfg = {'provider': 'deepseek', 'chat_model': 'deepseek-chat', 'max_tokens': '1200',
               'temperature': '0.3', 'special_tag_expand_prompt': '只输出最终规则'}
        reasoning_only = {'choices': [{'message': {'content': '', 'reasoning_content': '思考中'}, 'finish_reason': 'length'}]}
        final_answer = {'choices': [{'message': {'content': '仅当两人在封闭空间且原文明确交互时命中。'}, 'finish_reason': 'stop'}]}
        with patch.object(app, 'current_config', return_value=cfg), \
             patch.object(app, 'request_api_json', side_effect=[reasoning_only, final_answer]) as request:
            result = app.expand_special_tag_prompt('密室双人', '两人共处密室')
        self.assertIn('封闭空间', result['expanded'])
        self.assertEqual(request.call_count, 2)
        self.assertEqual(request.call_args_list[0].kwargs['body']['thinking']['type'], 'disabled')

    def test_roleplay_profile_http_get_accepts_archive_query(self):
        saved = app.save_roleplay_profile(self.a, {'name': '测试卡', 'character_name': '高文'})
        profile_id = saved['profile']['id']
        server = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            with urllib.request.urlopen(base + f'/api/roleplay_profile/{profile_id}?archive_id={self.a}', timeout=5) as response:
                profile = json.load(response)['profile']
            self.assertEqual(profile['character_name'], '高文')
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_tavern_card_json_and_png_import_preview(self):
        card = {
            'spec': 'chara_card_v2', 'spec_version': '2.0',
            'data': {
                'name': '爱丽丝', 'description': '图书管理员', 'personality': '安静',
                'scenario': '午后的图书馆', 'first_mes': '你来了。', 'mes_example': '{{char}}：请坐。',
                'system_prompt': '保持角色。', 'post_history_instructions': '不要解释规则。',
                'extensions': {'foo': 'bar', 'novel_memory': {'user_name': '旅人'}},
                'character_book': {'entries': [{'keys': ['图书馆'], 'content': '安静的地方'}]},
                'tags': ['日常', '图书馆'], 'unknown_field': {'keep': True},
            },
        }
        parsed = app.parse_tavern_card_bytes('card.json', json.dumps(card, ensure_ascii=False).encode('utf-8'))
        self.assertEqual(parsed['source'], 'JSON')
        preview = app.tavern_import_preview(parsed)
        self.assertIn('V2', preview['spec'])
        self.assertEqual(preview['profile']['character_name'], '爱丽丝')
        self.assertEqual(preview['profile']['user_name'], '旅人')
        self.assertIn('unknown_field', preview['unknown_keys'])
        self.assertTrue(any('角色书' in item for item in preview['warnings']))
        extensions = json.loads(preview['profile']['extensions_json'])
        self.assertEqual(extensions['foo'], 'bar')
        self.assertEqual(extensions['tavern_unmapped']['unknown_field'], {'keep': True})

        saved = app.save_roleplay_profile(self.a, {'data': card['data']})
        profile = app.get_roleplay_profile(saved['profile']['id'], self.a)
        self.assertEqual(profile['extensions']['foo'], 'bar')
        app.save_roleplay_profile(self.a, {**profile, 'id': profile['id'], 'personality': '活泼'})
        kept = app.get_roleplay_profile(profile['id'], self.a)
        self.assertEqual(kept['personality'], '活泼')
        self.assertEqual(kept['extensions']['foo'], 'bar')

        def chunk(kind, payload):
            return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF)
        encoded = base64.b64encode(json.dumps(card, ensure_ascii=False).encode('utf-8'))
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'tEXt', b'chara\x00' + encoded) + chunk(b'IEND', b'')
        parsed_png = app.parse_tavern_card_bytes('card.png', png)
        self.assertEqual(parsed_png['source'], 'PNG')
        self.assertEqual(app.tavern_import_preview(parsed_png)['profile']['character_name'], '爱丽丝')
        with self.assertRaises(ValueError):
            app.parse_tavern_card_bytes('empty.png', b'\x89PNG\r\n\x1a\n')

    def test_v3_card_nickname_and_greetings_are_preserved(self):
        card = {
            'spec': 'chara_card_v3', 'spec_version': '3.0',
            'data': {
                'name': '高文', 'nickname': '塞西尔之王', 'description': '领主',
                'first_mes': '开始吧。', 'alternate_greetings': ['另一个开场'],
                'group_only_greetings': ['群聊开场'], 'assets': [{'type': 'icon', 'uri': 'ccdefault:'}],
            },
        }
        preview = app.tavern_import_preview(app.parse_tavern_card_bytes('v3.json', json.dumps(card).encode('utf-8')))
        self.assertIn('V3', preview['spec'])
        self.assertEqual(preview['profile']['character_name'], '高文')
        self.assertTrue(any('备选开场白' in item for item in preview['warnings']))
        stored = json.loads(preview['profile']['extensions_json'])
        self.assertIn('assets', stored['tavern_unmapped'])


if __name__ == "__main__":
    unittest.main()
