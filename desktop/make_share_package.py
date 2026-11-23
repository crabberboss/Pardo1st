import hashlib
import json
import os
import shutil
import sqlite3
import sys
import zipfile


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUTPUTS = os.path.join(ROOT, "outputs")
SOURCE_DB = os.path.join(OUTPUTS, "data", "novel_memory.sqlite3")
STAGE = os.path.join(ROOT, "work", "NovelMemory-share")
ZIP_PATH = os.path.join(OUTPUTS, "NovelMemory-知识库分享版.zip")

SENSITIVE_SETTINGS = {
    "provider", "base_url", "api_key", "chat_model", "temperature", "max_tokens", "top_k",
    "username", "theme", "summary_prompt", "summary_max_chars", "chat_prompt", "roleplay_prompt", "rewrite_global_prompt",
}


def table_exists(conn: sqlite3.Connection, name: str) -> bool:
    return conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone() is not None


def main() -> None:
    if not os.path.exists(SOURCE_DB):
        raise SystemExit("找不到现有知识库数据库")
    if os.path.exists(STAGE):
        shutil.rmtree(STAGE)
    os.makedirs(os.path.join(STAGE, "data"), exist_ok=True)
    shutil.copy2(os.path.join(OUTPUTS, "NovelMemory.exe"), os.path.join(STAGE, "NovelMemory.exe"))
    shutil.copy2(os.path.join(OUTPUTS, "NovelMemory-使用说明.md"), os.path.join(STAGE, "NovelMemory-使用说明.md"))

    target_db = os.path.join(STAGE, "data", "novel_memory.sqlite3")
    source = sqlite3.connect(SOURCE_DB)
    target = sqlite3.connect(target_db)
    source.backup(target)
    source.close()
    target.execute("PRAGMA foreign_keys=ON")

    completed_ids = [row[0] for row in target.execute(
        "SELECT a.id FROM archives a WHERE EXISTS (SELECT 1 FROM chunks c WHERE c.archive_id=a.id) "
        "AND EXISTS (SELECT 1 FROM summaries s WHERE s.archive_id=a.id AND s.scope='archive' AND LENGTH(TRIM(s.content))>0) "
        "ORDER BY a.id"
    )]
    if not completed_ids:
        target.close()
        raise SystemExit("没有已经生成全书总览的完整知识库，未生成分享包")
    placeholders = ",".join("?" for _ in completed_ids)
    target.execute(f"DELETE FROM archives WHERE id NOT IN ({placeholders})", completed_ids)

    for table in (
        "provider_configs", "messages", "chat_presets", "prompt_templates", "chapter_prompts", "rewrites",
        "roleplay_messages", "roleplay_profiles", "conversation_sessions", "archive_settings", "rewrite_templates",
    ):
        if table_exists(target, table):
            target.execute(f'DELETE FROM "{table}"')
    if table_exists(target, "settings"):
        target.executemany("DELETE FROM settings WHERE key=?", [(key,) for key in SENSITIVE_SETTINGS])
    target.commit()
    target.execute("VACUUM")
    target.close()

    if os.path.exists(ZIP_PATH):
        os.remove(ZIP_PATH)
    with zipfile.ZipFile(ZIP_PATH, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for folder, _, files in os.walk(STAGE):
            for filename in files:
                path = os.path.join(folder, filename)
                arcname = os.path.relpath(path, STAGE)
                zf.write(path, arcname)

    digest = hashlib.sha256()
    with open(ZIP_PATH, "rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(block)
    print(json.dumps({
        "zip": ZIP_PATH,
        "size": os.path.getsize(ZIP_PATH),
        "sha256": digest.hexdigest(),
        "stage": STAGE,
        "shared_archive_ids": completed_ids,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
