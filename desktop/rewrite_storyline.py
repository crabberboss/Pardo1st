"""Independent rewrite projects. The knowledge-base SQLite connection is read-only here."""

import hashlib
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from urllib.parse import quote


_lock = threading.RLock()


def _root(db_file):
    path = os.path.join(os.path.dirname(os.path.abspath(db_file)), "rewrite_projects")
    os.makedirs(path, exist_ok=True)
    return path


def _path(db_file, project_id):
    if not re.fullmatch(r"[0-9a-f]{32}", str(project_id or "")):
        raise ValueError("改写工程 ID 无效")
    return os.path.join(_root(db_file), project_id)


@contextmanager
def _read_kb(db_file):
    uri = "file:" + quote(os.path.abspath(db_file).replace("\\", "/"), safe="/:") + "?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def _write_json(path, value):
    temporary = path + "." + uuid.uuid4().hex + ".tmp"
    try:
        with open(temporary, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def _write_text(path, value):
    temporary = path + "." + uuid.uuid4().hex + ".tmp"
    try:
        with open(temporary, "w", encoding="utf-8") as handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def chapter_key(document_id, title):
    return f"{int(document_id)}:{title}"


def chapter_file(document_id, title):
    digest = hashlib.sha256(chapter_key(document_id, title).encode("utf-8")).hexdigest()
    return digest + ".txt"


def _summary_overview(summary):
    if not summary:
        return "尚无章节摘要；请先在知识库生成摘要，或手动添加关键事件。"
    match = re.search(r"(?:剧情概览|核心情节|情节概览)\s*[：:]\s*(.+?)(?=\n\s*(?:关键词|人物|地点|伏笔|因果|设定)\s*[：:]|\Z)", summary, re.S)
    return (match.group(1) if match else summary).strip()[:240]


def _events(summary):
    overview = _summary_overview(summary)
    if not summary:
        return [overview]
    sentences = [item.strip() for item in re.split(r"[。；;\n]+", overview) if len(item.strip()) >= 5]
    return [item[:120] for item in sentences[:3]] or [overview]


def _original_nodes(archive_id, chapters):
    nodes = []
    for index, chapter in enumerate(chapters):
        key = chapter_key(chapter["document_id"], chapter["title"])
        for event_index, text in enumerate(_events(chapter["summary"])):
            nodes.append({
                "id": "original:" + hashlib.sha256((str(archive_id) + ":" + key + ":" + str(event_index)).encode("utf-8")).hexdigest()[:20],
                "chapter_key": key, "document_id": chapter["document_id"],
                "chapter_title": chapter["title"], "filename": chapter["filename"],
                "chapter_index": index, "event_index": event_index,
                "source_key": chapter["source_key"],
                "summary_hash": hashlib.sha256(chapter["summary"].encode("utf-8")).hexdigest(),
                "text": text,
                "needs_confirmation": not bool(chapter["summary"]),
            })
    return nodes


def _source_chapters(db_file, archive_id):
    with _read_kb(db_file) as conn:
        archive = conn.execute("SELECT id, name FROM archives WHERE id=?", (archive_id,)).fetchone()
        if not archive:
            raise ValueError("知识库不存在")
        rows = conn.execute(
            "SELECT c.document_id, c.title, MIN(c.ord) AS first_ord, d.filename "
            "FROM chunks c JOIN documents d ON d.id=c.document_id "
            "WHERE c.archive_id=? GROUP BY c.document_id,c.title "
            "ORDER BY c.document_id,first_ord", (archive_id,)
        ).fetchall()
        chapters = []
        for row in rows:
            key = f"doc:{row['document_id']}:chapter:{row['title']}"
            summary_row = conn.execute(
                "SELECT content FROM summaries WHERE archive_id=? AND scope='chapter' AND source_key=?",
                (archive_id, key),
            ).fetchone()
            summary = summary_row[0] if summary_row else ""
            chapters.append({"document_id": row["document_id"], "title": row["title"],
                             "filename": row["filename"], "first_ord": row["first_ord"],
                             "summary": summary, "source_key": key})
    return archive["name"], chapters


def _manifest(db_file, project_id, archive_id=None):
    path = os.path.join(_path(db_file, project_id), "project.json")
    if not os.path.isfile(path):
        raise ValueError("改写工程不存在")
    with open(path, encoding="utf-8") as handle:
        project = json.load(handle)
    if archive_id is not None and int(project["archive_id"]) != int(archive_id):
        raise ValueError("改写工程不属于当前知识库")
    return project


def list_projects(db_file, archive_id):
    _source_chapters(db_file, archive_id)
    result = []
    for name in os.listdir(_root(db_file)):
        if not re.fullmatch(r"[0-9a-f]{32}", name):
            continue
        try:
            item = _manifest(db_file, name, archive_id)
        except (OSError, ValueError, KeyError, json.JSONDecodeError):
            continue
        result.append({k: item[k] for k in ("id", "archive_id", "name", "created_at", "updated_at")})
    return sorted(result, key=lambda x: x["created_at"], reverse=True)


def create_project(db_file, archive_id, name="", copy_legacy=False):
    archive_name, chapters = _source_chapters(db_file, archive_id)
    project_id = uuid.uuid4().hex
    folder = _path(db_file, project_id)
    os.makedirs(os.path.join(folder, "chapters"), exist_ok=False)
    now = int(time.time())
    project = {"format": "novel-memory-rewrite-project", "version": 1, "id": project_id,
               "archive_id": archive_id, "archive_name": archive_name,
               "name": (name or archive_name + " · 改写线").strip(),
               "created_at": now, "updated_at": now, "chapter_data": {},
               "original_nodes": _original_nodes(archive_id, chapters), "overlay_nodes": [], "global_prompt": "",
               "templates": []}
    with _read_kb(db_file) as conn:
        legacy = conn.execute("SELECT document_id,title,content,prompt FROM rewrites WHERE archive_id=?", (archive_id,)).fetchall() if copy_legacy else []
    for row in legacy:
        key = chapter_key(row["document_id"], row["title"])
        project["chapter_data"][key] = {"prompt": row["prompt"] or "", "status": "草稿", "source": "旧版复制"}
        _write_text(os.path.join(folder, "chapters", chapter_file(row["document_id"], row["title"])), row["content"] or "")
    _write_json(os.path.join(folder, "project.json"), project)
    return project


def project_state(db_file, project_id, archive_id):
    project = _manifest(db_file, project_id, archive_id)
    _, chapters = _source_chapters(db_file, archive_id)
    actual = {chapter_key(c["document_id"], c["title"]): c for c in chapters}
    for node in project["original_nodes"]:
        chapter = actual.get(node["chapter_key"])
        node["anchor_status"] = "章节已删除或改名" if not chapter else (
            "摘要已变化" if hashlib.sha256(chapter["summary"].encode("utf-8")).hexdigest() != node["summary_hash"] else "正常")
    return project


def update_project(db_file, project_id, archive_id, **fields):
    with _lock:
        project = _manifest(db_file, project_id, archive_id)
        if "global_prompt" in fields:
            project["global_prompt"] = str(fields["global_prompt"] or "")
        if "name" in fields and str(fields["name"]).strip():
            project["name"] = str(fields["name"]).strip()[:100]
        project["updated_at"] = int(time.time())
        _write_json(os.path.join(_path(db_file, project_id), "project.json"), project)
        return project


def refresh_original_nodes(db_file, project_id, archive_id):
    with _lock:
        project = _manifest(db_file, project_id, archive_id)
        _, chapters = _source_chapters(db_file, archive_id)
        fresh = _original_nodes(archive_id, chapters)
        fresh_ids = {node["id"] for node in fresh}
        # Keep deleted/renamed anchors as explicit orphans, so overlay notes never drift.
        orphans = [node for node in project["original_nodes"] if node["id"] not in fresh_ids and
                   any(overlay["anchor_id"] == node["id"] for overlay in project["overlay_nodes"])]
        project["original_nodes"] = fresh + orphans
        project["updated_at"] = int(time.time())
        _write_json(os.path.join(_path(db_file, project_id), "project.json"), project)
        return project_state(db_file, project_id, archive_id)


def get_chapter(db_file, project_id, archive_id, document_id, title):
    project = _manifest(db_file, project_id, archive_id)
    return chapter_from_project(db_file, project, document_id, title)


def chapter_from_project(db_file, project, document_id, title):
    """Read one draft using an already loaded project manifest."""
    project_id = project["id"]
    key = chapter_key(document_id, title)
    info = project["chapter_data"].get(key, {})
    path = os.path.join(_path(db_file, project_id), "chapters", chapter_file(document_id, title))
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as handle:
            content = handle.read()
    else:
        content = ""
    return {"content": content, "prompt": info.get("prompt", ""),
            "extra_instruction": info.get("extra_instruction", ""), "status": info.get("status", "未改写"),
            "source": info.get("source", ""), "updated_at": info.get("updated_at", 0)}


def save_chapter(db_file, project_id, archive_id, document_id, title, content=None, prompt=None,
                 status=None, extra_instruction=None):
    with _lock:
        project = _manifest(db_file, project_id, archive_id)
        if chapter_key(document_id, title) not in {n["chapter_key"] for n in project["original_nodes"]}:
            raise ValueError("章节不属于该改写工程")
        key = chapter_key(document_id, title)
        info = project["chapter_data"].setdefault(key, {})
        if prompt is not None:
            info["prompt"] = str(prompt)
        if extra_instruction is not None:
            info["extra_instruction"] = str(extra_instruction)
        if content is not None:
            _write_text(os.path.join(_path(db_file, project_id), "chapters", chapter_file(document_id, title)), str(content))
            info["status"] = status if status in {"草稿", "已确认"} else ("草稿" if str(content).strip() else "未改写")
        elif status in {"草稿", "已确认"}:
            info["status"] = status
        info["updated_at"] = project["updated_at"] = int(time.time())
        _write_json(os.path.join(_path(db_file, project_id), "project.json"), project)
        return get_chapter(db_file, project_id, archive_id, document_id, title)


def save_overlay(db_file, project_id, archive_id, payload):
    with _lock:
        project = _manifest(db_file, project_id, archive_id)
        kind = payload.get("kind")
        if kind not in {"annotation", "insert"}:
            raise ValueError("节点类型无效")
        original_ids = {n["id"] for n in project["original_nodes"]}
        anchor = payload.get("anchor_id")
        if anchor not in original_ids:
            raise ValueError("必须挂靠到当前工程的原著节点")
        node_id = str(payload.get("id") or uuid.uuid4().hex)
        if not re.fullmatch(r"[0-9a-f]{32}", node_id):
            raise ValueError("改写节点 ID 无效")
        nodes = project["overlay_nodes"]
        existing = next((n for n in nodes if n["id"] == node_id), None)
        if payload.get("delete"):
            if existing:
                nodes.remove(existing)
        else:
            value = {"id": node_id, "kind": kind, "anchor_id": anchor,
                     "placement": payload.get("placement") if payload.get("placement") in {"before", "after", "on"} else "on",
                     "change": payload.get("change") if payload.get("change") in {"保留", "变更", "取消", "补充"} else "补充",
                     "text": str(payload.get("text") or "").strip()[:2000],
                     "region": str(payload.get("region") or "").strip()[:100],
                     "characters": str(payload.get("characters") or "").strip()[:200],
                     "subplot": str(payload.get("subplot") or "").strip()[:100]}
            if not value["text"]:
                raise ValueError("节点内容不能为空")
            if existing:
                existing.update(value)
            else:
                nodes.append(value)
        project["updated_at"] = int(time.time())
        _write_json(os.path.join(_path(db_file, project_id), "project.json"), project)
        return project


def save_template(db_file, project_id, archive_id, config):
    with _lock:
        project = _manifest(db_file, project_id, archive_id)
        if not isinstance(config, dict) or not str(config.get("name") or "").strip():
            raise ValueError("模板名称不能为空")
        templates = project["templates"]
        name = str(config["name"]).strip()[:100]
        existing = next((item for item in templates if item["name"] == name), None)
        if existing:
            existing["config"] = config
        else:
            templates.append({"id": max([item["id"] for item in templates], default=0) + 1,
                              "name": name, "config": config})
        project["updated_at"] = int(time.time())
        _write_json(os.path.join(_path(db_file, project_id), "project.json"), project)
        return project


def export_manuscript(db_file, project_id, archive_id):
    project = project_state(db_file, project_id, archive_id)
    lines = []
    seen_chapters = set()
    for node in project["original_nodes"]:
        if node["chapter_key"] in seen_chapters:
            continue
        seen_chapters.add(node["chapter_key"])
        chapter = get_chapter(db_file, project_id, archive_id, node["document_id"], node["chapter_title"])
        if chapter["content"].strip():
            lines.extend((node["chapter_title"], "", chapter["content"].strip(), "", ""))
    if not lines:
        raise ValueError("还没有可导出的改写正文")
    filename = "改写正文-" + time.strftime("%Y%m%d-%H%M%S") + ".txt"
    path = os.path.join(_path(db_file, project_id), filename)
    _write_text(path, "\n".join(lines).rstrip() + "\n")
    return path
