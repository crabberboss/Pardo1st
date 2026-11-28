"""Desktop editor for Android's pardo-novel-generation project format.

The future controlled-planning integration remains intentionally unconnected.
This workspace edits imported generation projects; it never writes knowledge bases.
"""

import copy
import json
import os
import re
import tempfile
import threading
import time
import uuid


_lock = threading.RLock()
_busy = set()
_pause = set()
_uuid = re.compile(r"[0-9a-f-]{36}\Z")


def _folder(data_root):
    path = os.path.join(data_root, "generation_projects")
    os.makedirs(path, exist_ok=True)
    return path


def _path(data_root, project_id):
    if not _uuid.fullmatch(str(project_id)):
        raise ValueError("无效的生成项目编号")
    return os.path.join(_folder(data_root), project_id + ".json")


def _read(data_root, project_id):
    with open(_path(data_root, project_id), encoding="utf-8") as stream:
        project = json.load(stream)
    _validate(project)
    return project


def _write(data_root, project):
    target = _path(data_root, project["id"])
    fd, temporary = tempfile.mkstemp(prefix=".generation-", suffix=".tmp", dir=_folder(data_root))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(project, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _validate(project):
    if not isinstance(project, dict) or project.get("format") != "pardo-novel-generation":
        raise ValueError("不是帕朵一号小说生成项目文件")
    if project.get("version") != 1 or not isinstance(project.get("chapters"), list):
        raise ValueError("不支持此生成项目格式版本")
    if len(project["chapters"]) > 10000:
        raise ValueError("章节数量超出安全限制")
    for index, chapter in enumerate(project["chapters"], 1):
        if not isinstance(chapter, dict) or chapter.get("number") != index:
            raise ValueError(f"第 {index} 章的序号或格式有误")


def import_project(data_root, raw):
    if len(raw) > 40_000_000:
        raise ValueError("生成项目文件超过 40 MB")
    project = json.loads(raw.decode("utf-8-sig"))
    _validate(project)
    with _lock:
        project["id"] = str(uuid.uuid4())
        project["status"] = "paused"
        project["detail"] = "已导入桌面端；可编辑和重生成已完成章节"
        project["updated_at"] = int(time.time() * 1000)
        _write(data_root, project)
    return view(project)


def list_projects(data_root):
    with _lock:
        projects = []
        for name in os.listdir(_folder(data_root)):
            if not name.endswith(".json") or not _uuid.fullmatch(name[:-5]):
                continue
            try:
                project = _read(data_root, name[:-5])
                projects.append({"id": project["id"], "name": project.get("name", "未命名"),
                                 "chapters": len(project["chapters"]), "updated_at": project.get("updated_at", 0)})
            except (OSError, ValueError, KeyError, TypeError):
                continue
        return sorted(projects, key=lambda item: item["updated_at"], reverse=True)


def view(project):
    chapter_count = len(project["chapters"])
    characters = []
    for row in project.get("characters", [])[:500]:
        if not isinstance(row, dict):
            continue
        last = int(row.get("last_chapter") or 0)
        dead = row.get("status") == "dead"
        characters.append({"name": str(row.get("name") or "")[:100],
                           "state": str(row.get("state") or "")[:220],
                           "status": "dead" if dead else "active",
                           "heat": 0 if dead or last <= 0 else max(0, 100 - 5 * max(0, chapter_count - last)),
                           "last_chapter": last})
    characters.sort(key=lambda row: (row["status"] == "dead", -row["heat"], row["name"]))
    threads = [{"name": str(row.get("name") or "")[:100],
                "state": str(row.get("state") or "")[:220],
                "status": str(row.get("status") or "active"),
                "visibility": str(row.get("visibility") or "public"),
                "source_chapter": int(row.get("source_chapter") or 0)}
               for row in project.get("threads", [])[:300] if isinstance(row, dict)]
    world_state = [{"name": str(row.get("name") or "")[:100],
                    "state": str(row.get("state") or "")[:220]}
                   for row in project.get("world_state", [])[:300] if isinstance(row, dict)]
    return {"id": project["id"], "name": project.get("name", "未命名"),
            "chapter_chars": project.get("chapter_chars", 2200), "output_tokens": project.get("output_tokens", 4096),
            "protagonist_name": project.get("protagonist_name", ""), "world": project.get("world", ""),
            "system": project.get("system", ""), "protagonist": project.get("protagonist", ""),
            "style": project.get("style", ""), "outline": project.get("outline", ""),
            "generation_prompt": project.get("generation_prompt", ""),
            "status": project.get("status", "ready"), "stage": project.get("stage", "plan"),
            "detail": project.get("detail", ""), "error": project.get("error", ""),
            "pending": project.get("pending"), "draft_chars": len(project.get("draft", "")),
            "memory_stale_from": project.get("memory_stale_from", 0),
            "characters": characters, "threads": threads, "world_state": world_state,
            "timeline": [{"number": row["number"], "title": str(row.get("title") or "")[:100],
                          "summary": str(row.get("summary") or "")[:160],
                          "cast": [str(name)[:100] for name in (row.get("cast") or [])[:12]]}
                         for row in project["chapters"][-60:]],
            "chapters": [{"number": row["number"], "title": row.get("title", ""),
                          "chars": len(row.get("content", "")), "memory_stale": row.get("memory_stale", False)}
                         for row in project["chapters"]]}


def get_project(data_root, project_id):
    with _lock:
        project = _read(data_root, project_id)
        if project.get("status") == "running" and project_id not in _busy:
            project["status"] = "interrupted"
            _checkpoint(data_root, project, "上次生成被中断；可从已保存检查点继续")
        return view(project)


def create_project(data_root, payload):
    name = str(payload.get("name") or "").strip()
    protagonist = str(payload.get("protagonist_name") or "").strip()
    if not name or not protagonist or len(name) > 200 or len(protagonist) > 100:
        raise ValueError("请填写小说名与主角名字（不超过 200/100 字）")
    project = {"format": "pardo-novel-generation", "version": 1, "id": str(uuid.uuid4()),
               "name": name, "protagonist_name": protagonist, "protagonist": protagonist,
               "outline": "", "world": "", "system": "", "style": "", "generation_prompt": "",
               "chapter_chars": 2200, "output_tokens": 4096, "max_retries": 3,
               "status": "ready", "stage": "plan", "chapters": [],
               "characters": [{"id": str(uuid.uuid4()), "name": protagonist, "state": "主角，尚未登场",
                               "status": "active", "last_chapter": 0, "pinned": True}],
               "threads": [], "world_state": [], "input_tokens": 0, "output_tokens_used": 0,
               "updated_at": int(time.time() * 1000)}
    with _lock:
        _write(data_root, project)
    return view(project)


def update_project(data_root, project_id, changes):
    limits = {"outline": 12000, "world": 20000, "system": 12000, "protagonist": 12000,
              "style": 5000, "generation_prompt": 8000}
    with _lock:
        if project_id in _busy:
            raise ValueError("请先等待当前章节生成完成")
        project = _read(data_root, project_id)
        for field, limit in limits.items():
            if field in changes:
                value = str(changes[field]).strip()
                if len(value) > limit:
                    raise ValueError(field + " 超出长度限制")
                project[field] = value
        for field, lower, upper in (("chapter_chars", 600, 5000), ("output_tokens", 1024, 16384)):
            if field in changes:
                value = int(changes[field])
                if not lower <= value <= upper:
                    raise ValueError(field + " 超出范围")
                project[field] = value
        project["updated_at"] = int(time.time() * 1000)
        _write(data_root, project)
        return view(project)


def save_plan(data_root, project_id, payload):
    with _lock:
        if project_id in _busy:
            raise ValueError("请等待当前章节生成完成")
        project = _read(data_root, project_id)
        if project.get("draft"):
            raise ValueError("本章正文草稿待提取记忆，请先续建或处理失败状态")
        title = str(payload.get("title") or "").strip()
        outline = str(payload.get("outline") or "").strip()
        if not title or not outline or len(title) > 300 or len(outline) > 20000:
            raise ValueError("请填写有效的章节标题与章纲")
        cast = payload.get("cast") or []
        if not isinstance(cast, list) or len(cast) > 100:
            raise ValueError("出场角色格式无效")
        project["pending"] = {"number": len(project["chapters"]) + 1, "title": title,
                              "outline": outline, "cast": [str(item).strip()[:100] for item in cast if str(item).strip()],
                              "viewpoint": str(payload.get("viewpoint") or "")[:100],
                              "keywords": [], "special_tags": []}
        project["stage"] = "write"
        project["status"] = "paused"
        project["detail"] = "本章章纲已保存，准备生成正文"
        project["updated_at"] = int(time.time() * 1000)
        _write(data_root, project)
        return view(project)


def get_chapter(data_root, project_id, number):
    with _lock:
        project = _read(data_root, project_id)
        return copy.deepcopy(_chapter(project, number))


def _chapter(project, number):
    if not isinstance(number, int) or number < 1 or number > len(project["chapters"]):
        raise ValueError("已完成章节不存在")
    return project["chapters"][number - 1]


def _revision(chapter):
    return int(chapter.get("updated_at") or chapter.get("accepted_at") or 0)


def _check(chapter, expected):
    if _revision(chapter) != int(expected):
        raise ValueError("本章已在其他操作中改变，请重新打开章节")


_snapshot_fields = ("title", "outline", "summary", "content", "cast", "keywords", "special_tags",
                    "actual_cast", "updated_at", "accepted_at")


def _snapshot(chapter):
    return {field: copy.deepcopy(chapter[field]) for field in _snapshot_fields if field in chapter}


def _remember(chapter):
    chapter["revision_history"] = chapter.get("revision_history", [])[-2:] + [_snapshot(chapter)]


def _stale(project, chapter, number):
    chapter["memory_stale"] = True
    project["memory_stale_from"] = min(number, project.get("memory_stale_from") or number)


def _stamp(project, chapter, expected):
    now = max(int(time.time() * 1000), int(expected) + 1)
    chapter["updated_at"] = now
    project["updated_at"] = now


def save_chapter(data_root, project_id, number, expected, changes):
    with _lock:
        if project_id in _busy:
            raise ValueError("请等待本章重生成完成")
        project = _read(data_root, project_id)
        chapter = _chapter(project, number)
        _check(chapter, expected)
        fields = {key: str(changes.get(key, chapter.get(key, ""))).strip()
                  for key in ("title", "outline", "summary", "content")}
        if not fields["title"] or not fields["outline"] or not fields["content"]:
            raise ValueError("章节标题、章纲和正文不能为空")
        if any(len(fields[key]) > limit for key, limit in
               {"title": 300, "outline": 20000, "summary": 1000, "content": 150000}.items()):
            raise ValueError("本章编辑内容超出长度限制")
        if any(fields[key] != chapter.get(key, "") for key in fields):
            _remember(chapter)
            prose_changed = fields["content"] != chapter.get("content", "")
            chapter.update(fields)
            _stamp(project, chapter, expected)
            if prose_changed:
                _stale(project, chapter, number)
            _write(data_root, project)
        return copy.deepcopy(chapter)


def restore_chapter(data_root, project_id, number, expected):
    with _lock:
        if project_id in _busy:
            raise ValueError("请等待本章重生成完成")
        project = _read(data_root, project_id)
        chapter = _chapter(project, number)
        _check(chapter, expected)
        history = chapter.get("revision_history") or []
        if not history:
            raise ValueError("本章没有可恢复的上一版")
        previous = history[-1]
        chapter["revision_history"] = history[:-1] + [_snapshot(chapter)]
        for field in _snapshot_fields:
            if field in previous:
                chapter[field] = copy.deepcopy(previous[field])
            else:
                chapter.pop(field, None)
        _stamp(project, chapter, expected)
        _stale(project, chapter, number)
        _write(data_root, project)
        return copy.deepcopy(chapter)


def _context(project, number):
    previous = project["chapters"][max(0, number - 5):number - 1]
    summaries = [{"number": row["number"], "title": row.get("title", ""),
                  "summary": row.get("summary", "")[:500]} for row in previous]
    preceding = project["chapters"][number - 2].get("content", "")[-1800:] if number > 1 else ""
    return ("世界观：" + str(project.get("world", ""))[:1800] + "\n系统规则："
            + str(project.get("system", ""))[:1800] + "\n主角：" + str(project.get("protagonist_name", ""))
            + "；" + str(project.get("protagonist", ""))[:1200] + "\n文风：" + str(project.get("style", ""))[:700]
            + "\n全局生成要求：" + str(project.get("generation_prompt", ""))[:1200]
            + "\n仅限本章之前的剧情梗概：" + json.dumps(summaries, ensure_ascii=False)
            + "\n上一章结尾：" + preceding)


def _repeated_paragraph(content):
    paragraphs = [re.sub(r"\s+", "", item) for item in re.split(r"\n\s*\n", content) if len(item.strip()) >= 80]
    return len(paragraphs) != len(set(paragraphs))


def _parse_object(text):
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", cleaned, flags=re.I).strip()
    try:
        result = json.loads(cleaned)
    except json.JSONDecodeError as error:
        raise ValueError("模型没有返回可解析的 JSON；已保存当前检查点") from error
    if not isinstance(result, dict):
        raise ValueError("模型返回的结构化内容不是对象；已保存当前检查点")
    return result


def _checkpoint(data_root, project, detail):
    project["detail"] = detail
    project["updated_at"] = int(time.time() * 1000)
    _write(data_root, project)


def _extract_memory(project, changes, number):
    for field in ("characters", "threads", "world_state"):
        rows = changes.get(field, [])
        if not isinstance(rows, list):
            continue
        current = project.setdefault(field, [])
        for row in rows[:80]:
            if not isinstance(row, dict) or not str(row.get("name") or "").strip():
                continue
            name = str(row["name"]).strip()[:100]
            item = next((entry for entry in current if entry.get("name") == name), None)
            if item is None:
                item = {"id": str(uuid.uuid4()), "name": name}
                current.append(item)
            item["state"] = str(row.get("state") or item.get("state") or "")[:700]
            item["source_chapter"] = number
            if field == "characters":
                if row.get("appeared"):
                    item["last_chapter"] = number
                if row.get("died"):
                    item["status"] = "dead"
                else:
                    item.setdefault("status", "active")
                for memory_field in ("abilities", "stats", "items"):
                    if isinstance(row.get(memory_field), list):
                        item[memory_field] = row[memory_field][:100]
            elif field == "threads":
                item["status"] = row.get("status") if row.get("status") in {"active", "paused", "resolved"} else item.get("status", "active")
                item["visibility"] = "hidden" if row.get("visibility") == "hidden" else "public"
                item.setdefault("history", []).append({"chapter": number, "progress": item["state"][:160]})


def _run_one(data_root, project_id, call_model):
    try:
        with _lock:
            project = _read(data_root, project_id)
            if project.get("status") == "completed":
                raise ValueError("故事已完结")
            project["status"] = "running"
            project.pop("error", None)
            _checkpoint(data_root, project, "准备生成下一章")
        number = len(project["chapters"]) + 1
        if project.get("stage", "plan") == "plan":
            if project_id in _pause:
                raise InterruptedError("已暂停，尚未调用规划模型")
            with _lock:
                _checkpoint(data_root, project, f"规划第 {number} 章中")
            system = ("你是长篇小说章节规划器。仅输出 JSON 对象："
                      '{"end":false,"title":"","outline":"","cast":[""],"viewpoint":"",'
                      '"keywords":[""],"special_tags":[""]}。根据已有章节和进行中的事件规划下一章；'
                      "全书方向参考只是弱提示。只有故事自然结束才将 end 设为 true。")
            user = (f"下一章序号：{number}\n" + _context(project, number)
                    + "\n当前角色：" + json.dumps(project.get("characters", [])[-40:], ensure_ascii=False)[:3500]
                    + "\n当前事件：" + json.dumps(project.get("threads", [])[-30:], ensure_ascii=False)[:2500]
                    + "\n全书方向参考（弱提示）：" + str(project.get("outline", ""))[:1200])
            plan = _parse_object(call_model([{"role": "system", "content": system},
                                              {"role": "user", "content": user}], 1400))
            with _lock:
                if plan.get("end"):
                    if number == 1:
                        raise ValueError("第一章尚未生成，模型却宣告完结；请填写手动章纲")
                    project["status"] = "completed"
                    project["stage"] = "done"
                    _checkpoint(data_root, project, f"故事自然结束，共 {number - 1} 章")
                    return
                if not str(plan.get("outline") or "").strip():
                    raise ValueError("自动规划的章纲为空")
                project["pending"] = {"number": number, "title": str(plan.get("title") or f"第{number}章")[:300],
                                      "outline": str(plan["outline"])[:20000],
                                      "cast": plan.get("cast", []) if isinstance(plan.get("cast"), list) else [],
                                      "viewpoint": str(plan.get("viewpoint") or "")[:100],
                                      "keywords": plan.get("keywords", []) if isinstance(plan.get("keywords"), list) else [],
                                      "special_tags": plan.get("special_tags", []) if isinstance(plan.get("special_tags"), list) else []}
                project["stage"] = "write"
                _checkpoint(data_root, project, f"第 {number} 章章纲已保存")
        if project_id in _pause:
            raise InterruptedError("已暂停；本章章纲已保存")
        if project.get("stage") == "write":
            with _lock:
                _checkpoint(data_root, project, f"生成第 {number} 章正文中")
            plan = project["pending"]
            system = ("你是长篇小说作者。遵守本章章纲、已确认角色与世界规则。"
                      "只输出本章完整小说正文，不要说明，不要重复段落，不要跳到后续章节。")
            user = ("本章计划：" + json.dumps(plan, ensure_ascii=False)
                    + f"\n目标约 {int(project.get('chapter_chars') or 2200)} 个中文字符。\n参考材料：\n"
                    + _context(project, number)
                    + "\n当前角色：" + json.dumps(project.get("characters", [])[-40:], ensure_ascii=False)[:3500]
                    + "\n当前事件：" + json.dumps(project.get("threads", [])[-30:], ensure_ascii=False)[:2500])
            content = call_model([{"role": "system", "content": system},
                                  {"role": "user", "content": user}],
                                 max(1024, min(16384, int(project.get("output_tokens") or 4096)))).strip()
            if len(content) < min(400, int(project.get("chapter_chars") or 2200) // 3):
                raise ValueError("正文过短，未保存为完成章")
            if _repeated_paragraph(content):
                raise ValueError("检测到重复段落，未保存为完成章")
            with _lock:
                project["draft"] = content
                project["stage"] = "extract"
                _checkpoint(data_root, project, f"第 {number} 章正文已暂存，准备提取记忆")
        if project_id in _pause:
            raise InterruptedError("已暂停；正文草稿已保存")
        if project.get("stage") == "extract":
            with _lock:
                _checkpoint(data_root, project, f"总结并校验第 {number} 章中")
            system = ("仅依据本章正文明确写出的内容提取结构化增量，不猜测。只输出 JSON："
                      '{"summary":"不超过80字","keywords":[""],"special_tags":[""],'
                      '"characters":[{"name":"","state":"","appeared":true,"died":false}],'
                      '"threads":[{"name":"","state":"","status":"active","visibility":"public"}],'
                      '"world_state":[{"name":"","state":""}]}。普通关键词和特殊场景标签与本章梗概一并生成。')
            user = (f"第 {number} 章正文：\n" + project["draft"]
                    + "\n既有角色：" + json.dumps(project.get("characters", [])[-40:], ensure_ascii=False)[:2000])
            changes = _parse_object(call_model([{"role": "system", "content": system},
                                                 {"role": "user", "content": user}], 1800))
            summary = str(changes.get("summary") or "").strip()
            if not summary:
                raise ValueError("记忆提取没有章节梗概；正文草稿已保留")
            with _lock:
                chapter = {**project["pending"], "content": project["draft"],
                           "summary": summary[:160], "accepted_at": int(time.time() * 1000)}
                for field in ("keywords", "special_tags"):
                    if isinstance(changes.get(field), list):
                        chapter[field] = changes[field][:40]
                project["chapters"].append(chapter)
                _extract_memory(project, changes, number)
                project.pop("pending", None)
                project.pop("draft", None)
                project["stage"] = "plan"
                project["status"] = "paused"
                _checkpoint(data_root, project, f"第 {number} 章已完成；单章模式已停止")
    except InterruptedError as error:
        with _lock:
            project = _read(data_root, project_id)
            project["status"] = "paused"
            _checkpoint(data_root, project, str(error))
    except Exception as error:
        with _lock:
            project = _read(data_root, project_id)
            project["status"] = "failed"
            project["error"] = str(error)
            _checkpoint(data_root, project, "生成失败，当前检查点已保存：" + str(error))
    finally:
        with _lock:
            _busy.discard(project_id)
            _pause.discard(project_id)


def start_one(data_root, project_id, call_model):
    with _lock:
        if project_id in _busy:
            raise ValueError("此项目已有任务在运行")
        project = _read(data_root, project_id)
        if project.get("status") == "completed":
            raise ValueError("故事已完结")
        _busy.add(project_id)
        _pause.discard(project_id)
        project["status"] = "running"
        _checkpoint(data_root, project, "已启动，准备生成下一章")
        thread = threading.Thread(target=_run_one, args=(data_root, project_id, call_model), daemon=True)
        thread.start()
        return view(project)


def pause(data_root, project_id):
    with _lock:
        _read(data_root, project_id)
        if project_id in _busy:
            _pause.add(project_id)
            return {"requested": True}
        return {"requested": False}


def regenerate_chapter(data_root, project_id, number, expected, call_model):
    with _lock:
        if project_id in _busy:
            raise ValueError("已有此项目的章节重生成任务在运行")
        project = _read(data_root, project_id)
        chapter = _chapter(project, number)
        _check(chapter, expected)
        _busy.add(project_id)
        plan = {key: chapter.get(key) for key in
                ("number", "title", "outline", "cast", "viewpoint", "keywords", "special_tags")}
        context = _context(project, number)
        target = int(project.get("chapter_chars") or 2200)
        output_tokens = max(1024, min(16384, int(project.get("output_tokens") or 4096)))
    try:
        system = ("你是长篇小说作者。只依据本章章纲和此前剧情重新生成完整的本章正文。"
                  "不得使用本章旧稿或后续章节设定，不要改变已确认的出场角色。"
                  "只输出小说正文，不要说明，不要重复段落。")
        user = ("本章计划：" + json.dumps(plan, ensure_ascii=False) + f"\n目标约 {target} 个中文字符。"
                "\n此前材料：\n" + context)
        replacement = call_model([{"role": "system", "content": system},
                                  {"role": "user", "content": user}], output_tokens).strip()
        if len(replacement) < min(400, target // 3):
            raise ValueError("重生成正文过短；旧稿未覆盖")
        if _repeated_paragraph(replacement):
            raise ValueError("重生成正文有重复段落；旧稿未覆盖")
        with _lock:
            project = _read(data_root, project_id)
            chapter = _chapter(project, number)
            _check(chapter, expected)
            _remember(chapter)
            chapter["content"] = replacement
            chapter["summary"] = ""
            _stamp(project, chapter, expected)
            chapter["last_regenerated_at"] = int(time.time() * 1000)
            _stale(project, chapter, number)
            _write(data_root, project)
            return copy.deepcopy(chapter)
    finally:
        with _lock:
            _busy.discard(project_id)


def export_project(data_root, project_id):
    with _lock:
        return _read(data_root, project_id)
