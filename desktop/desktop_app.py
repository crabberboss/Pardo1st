import base64
import json
import os
import queue
import sys
import threading
import time
import traceback
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

import app


APP_TITLE = "Novel Memory"


def runtime_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def log_path():
    return os.path.join(runtime_dir(), "NovelMemory-error.log")


def log_event(message):
    try:
        with open(log_path(), "a", encoding="utf-8") as fh:
            fh.write(time.strftime("%Y-%m-%d %H:%M:%S") + " " + message + "\n")
    except Exception:
        pass


def recompute_archive_stats(archive_id):
    with app.connect_db() as conn:
        docs = conn.execute("SELECT id FROM documents WHERE archive_id=?", (archive_id,)).fetchall()
        for doc in docs:
            total = conn.execute(
                "SELECT COALESCE(SUM(LENGTH(text)), 0) AS n FROM chunks WHERE document_id=?",
                (doc["id"],),
            ).fetchone()["n"]
            conn.execute("UPDATE documents SET char_count=? WHERE id=?", (total, doc["id"]))
        conn.execute("DELETE FROM documents WHERE archive_id=? AND id NOT IN (SELECT DISTINCT document_id FROM chunks)", (archive_id,))
        conn.execute("UPDATE archives SET updated_at=? WHERE id=?", (app.now_ts(), archive_id))


def run_async(target, on_done=None):
    result_queue = queue.Queue()

    def worker():
        try:
            result_queue.put(("ok", target()))
        except Exception as exc:
            result_queue.put(("err", exc))

    threading.Thread(target=worker, daemon=True).start()
    return result_queue, on_done


class TextDialog(simpledialog.Dialog):
    def __init__(self, parent, title, label, initial_text=""):
        self.label = label
        self.initial_text = initial_text
        self.result = None
        super().__init__(parent, title)

    def body(self, master):
        ttk.Label(master, text=self.label).pack(anchor="w", pady=(0, 6))
        self.text = tk.Text(master, width=88, height=24, wrap="word")
        self.text.pack(fill="both", expand=True)
        self.text.insert("1.0", self.initial_text)
        return self.text

    def apply(self):
        self.result = self.text.get("1.0", "end").strip()


class NovelMemoryDesktop(tk.Tk):
    def __init__(self):
        super().__init__()
        log_event("Tk root created")
        app.init_db()
        log_event("database initialized")
        self.title(APP_TITLE)
        self.geometry("1280x820")
        self.minsize(1120, 720)

        self.archives = []
        self.current_archive_id = None
        self.current_detail = None
        self.current_chapter = None
        self.jobs_after_id = None
        self.async_tasks = []

        self._setup_style()
        log_event("style initialized")
        self._build_layout()
        log_event("layout built")
        self.load_state()
        log_event("state loaded")
        self.force_show_window()
        log_event("window show requested")

    def _setup_style(self):
        self.style = ttk.Style(self)
        self.style.theme_use("clam")
        self.colors = {
            "paper": {"bg": "#f5f1e8", "panel": "#fffdf8", "accent": "#2f6f5e", "text": "#242721"},
            "ocean": {"bg": "#eef5f6", "panel": "#f9fcfc", "accent": "#246b83", "text": "#1f2b31"},
            "ink": {"bg": "#20211f", "panel": "#30332f", "accent": "#8cc3aa", "text": "#f4f1e8"},
        }
        self.apply_theme(app.current_config().get("theme", "paper"))

    def apply_theme(self, theme):
        colors = self.colors.get(theme, self.colors["paper"])
        self.configure(bg=colors["bg"])
        self.style.configure(".", background=colors["bg"], foreground=colors["text"], fieldbackground=colors["panel"])
        self.style.configure("TFrame", background=colors["bg"])
        self.style.configure("Panel.TFrame", background=colors["panel"])
        self.style.configure("TLabel", background=colors["bg"], foreground=colors["text"])
        self.style.configure("Panel.TLabel", background=colors["panel"], foreground=colors["text"])
        self.style.configure("TButton", padding=(10, 7))
        self.style.configure("Accent.TButton", background=colors["accent"], foreground="#ffffff")
        self.style.configure("Treeview", rowheight=28, fieldbackground=colors["panel"])
        self.style.map("Treeview", background=[("selected", colors["accent"])])

    def _build_layout(self):
        root = ttk.Frame(self)
        root.pack(fill="both", expand=True)

        sidebar = ttk.Frame(root, width=210, style="Panel.TFrame")
        sidebar.pack(side="left", fill="y")
        sidebar.pack_propagate(False)
        ttk.Label(sidebar, text="Novel Memory", font=("Microsoft YaHei", 18, "bold"), style="Panel.TLabel").pack(anchor="w", padx=18, pady=(20, 4))
        self.user_label = ttk.Label(sidebar, text="", style="Panel.TLabel")
        self.user_label.pack(anchor="w", padx=18, pady=(0, 22))

        self.nav_buttons = {}
        for key, text in [
            ("home", "开屏"),
            ("kb", "知识库创建"),
            ("chat", "开始对话"),
            ("settings", "设置"),
        ]:
            btn = ttk.Button(sidebar, text=text, command=lambda k=key: self.show_view(k))
            btn.pack(fill="x", padx=16, pady=5)
            self.nav_buttons[key] = btn

        self.status_var = tk.StringVar(value="就绪")
        ttk.Label(sidebar, textvariable=self.status_var, wraplength=170, style="Panel.TLabel").pack(side="bottom", anchor="w", padx=18, pady=18)

        self.content = ttk.Frame(root)
        self.content.pack(side="left", fill="both", expand=True)
        self.views = {}
        self._build_home_view()
        self._build_kb_view()
        self._build_chat_view()
        self._build_settings_view()

    def _view_frame(self, name):
        frame = ttk.Frame(self.content)
        frame.place(relx=0, rely=0, relwidth=1, relheight=1)
        self.views[name] = frame
        return frame

    def _build_home_view(self):
        frame = self._view_frame("home")
        inner = ttk.Frame(frame)
        inner.pack(expand=True)
        ttk.Label(inner, text="选择你的工作区", font=("Microsoft YaHei", 28, "bold")).pack(anchor="w", pady=(0, 10))
        ttk.Label(
            inner,
            text="知识库创建和对话是两个同等重要的流程：先把小说加工成可复用记忆，再在对话区调用模型使用这些记忆。",
            wraplength=850,
            font=("Microsoft YaHei", 11),
        ).pack(anchor="w", pady=(0, 24))
        cards = ttk.Frame(inner)
        cards.pack(fill="x")
        for key, title, body in [
            ("kb", "知识库创建", "创建小说档案、导入文本、查看切分章节、生成摘要，并编辑章节结构。"),
            ("chat", "开始对话", "选择知识库，基于全书总览、章节记忆和原文片段调用模型问答。"),
            ("settings", "设置", "设置色调、用户名和 API，测试 LM Studio / 硅基流动 / DeepSeek 连接。"),
        ]:
            card = ttk.Frame(cards, padding=18, style="Panel.TFrame")
            card.pack(side="left", fill="both", expand=True, padx=8)
            ttk.Label(card, text=title, font=("Microsoft YaHei", 18, "bold"), style="Panel.TLabel").pack(anchor="w")
            ttk.Label(card, text=body, wraplength=260, style="Panel.TLabel").pack(anchor="w", pady=12)
            ttk.Button(card, text="进入", command=lambda k=key: self.show_view(k)).pack(anchor="w", pady=(12, 0))

    def _build_kb_view(self):
        frame = self._view_frame("kb")
        paned = ttk.PanedWindow(frame, orient="horizontal")
        paned.pack(fill="both", expand=True, padx=16, pady=16)

        left = ttk.Frame(paned, padding=12, style="Panel.TFrame")
        paned.add(left, weight=1)
        ttk.Label(left, text="小说知识库", font=("Microsoft YaHei", 16, "bold"), style="Panel.TLabel").pack(anchor="w")
        row = ttk.Frame(left, style="Panel.TFrame")
        row.pack(fill="x", pady=(10, 8))
        self.new_archive_var = tk.StringVar()
        ttk.Entry(row, textvariable=self.new_archive_var).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="创建", command=self.create_archive).pack(side="left", padx=(8, 0))
        self.archive_list = tk.Listbox(left, height=12, activestyle="none")
        self.archive_list.pack(fill="both", expand=True)
        self.archive_list.bind("<<ListboxSelect>>", self.on_archive_select)
        ttk.Button(left, text="删除当前库", command=self.delete_current_archive).pack(fill="x", pady=(10, 0))

        center = ttk.Frame(paned, padding=12, style="Panel.TFrame")
        paned.add(center, weight=3)
        top = ttk.Frame(center, style="Panel.TFrame")
        top.pack(fill="x")
        self.kb_title_var = tk.StringVar(value="请选择或创建知识库")
        ttk.Label(top, textvariable=self.kb_title_var, font=("Microsoft YaHei", 16, "bold"), style="Panel.TLabel").pack(side="left")
        ttk.Button(top, text="导入小说", command=self.import_text_file).pack(side="right")

        action_bar = ttk.Frame(center, style="Panel.TFrame")
        action_bar.pack(fill="x", pady=10)
        ttk.Button(action_bar, text="新增章节", command=self.add_chapter).pack(side="left", padx=(0, 6))
        ttk.Button(action_bar, text="删除章节", command=self.delete_chapter).pack(side="left", padx=6)
        ttk.Button(action_bar, text="合并章节", command=self.merge_chapters).pack(side="left", padx=6)
        ttk.Button(action_bar, text="刷新", command=self.refresh_current).pack(side="left", padx=6)

        columns = ("status", "chunks", "chars", "doc")
        self.chapter_tree = ttk.Treeview(center, columns=columns, show="tree headings", selectmode="extended")
        self.chapter_tree.heading("#0", text="章节")
        self.chapter_tree.heading("status", text="总结状态")
        self.chapter_tree.heading("chunks", text="片段数")
        self.chapter_tree.heading("chars", text="字数")
        self.chapter_tree.heading("doc", text="文档")
        self.chapter_tree.column("#0", width=310)
        self.chapter_tree.column("status", width=90, anchor="center")
        self.chapter_tree.column("chunks", width=70, anchor="e")
        self.chapter_tree.column("chars", width=90, anchor="e")
        self.chapter_tree.column("doc", width=130)
        self.chapter_tree.pack(fill="both", expand=True)
        self.chapter_tree.bind("<<TreeviewSelect>>", self.on_chapter_select)

        build_bar = ttk.Frame(center, style="Panel.TFrame")
        build_bar.pack(fill="x", pady=(10, 0))
        self.build_button = ttk.Button(build_bar, text="生成/续建记忆", command=lambda: self.start_memory_build(False))
        self.build_button.pack(side="left")
        self.rebuild_button = ttk.Button(build_bar, text="强制重建", command=lambda: self.start_memory_build(True))
        self.rebuild_button.pack(side="left", padx=8)
        self.progress = ttk.Progressbar(build_bar, mode="determinate")
        self.progress.pack(side="left", fill="x", expand=True, padx=8)
        self.memory_status_var = tk.StringVar(value="导入小说后，可生成片段摘要、章节摘要和全书总览。")
        ttk.Label(center, textvariable=self.memory_status_var, style="Panel.TLabel").pack(anchor="w", pady=(8, 0))

        right = ttk.Frame(paned, padding=12, style="Panel.TFrame")
        paned.add(right, weight=2)
        ttk.Label(right, text="章节摘要编辑", font=("Microsoft YaHei", 16, "bold"), style="Panel.TLabel").pack(anchor="w")
        self.chapter_info_var = tk.StringVar(value="请选择章节")
        ttk.Label(right, textvariable=self.chapter_info_var, style="Panel.TLabel").pack(anchor="w", pady=(4, 8))
        self.summary_text = tk.Text(right, wrap="word", height=24)
        self.summary_text.pack(fill="both", expand=True)
        edit_bar = ttk.Frame(right, style="Panel.TFrame")
        edit_bar.pack(fill="x", pady=(10, 0))
        ttk.Button(edit_bar, text="保存章节总结", command=self.save_chapter_summary).pack(side="left")
        ttk.Button(edit_bar, text="修改章节标题", command=self.rename_chapter).pack(side="left", padx=8)
        ttk.Label(right, text="全书总览", font=("Microsoft YaHei", 13, "bold"), style="Panel.TLabel").pack(anchor="w", pady=(16, 6))
        self.archive_summary_text = tk.Text(right, wrap="word", height=8)
        self.archive_summary_text.pack(fill="x")

    def _build_chat_view(self):
        frame = self._view_frame("chat")
        paned = ttk.PanedWindow(frame, orient="horizontal")
        paned.pack(fill="both", expand=True, padx=16, pady=16)

        left = ttk.Frame(paned, padding=12, style="Panel.TFrame")
        paned.add(left, weight=1)
        ttk.Label(left, text="选择知识库", font=("Microsoft YaHei", 16, "bold"), style="Panel.TLabel").pack(anchor="w")
        self.chat_archive_list = tk.Listbox(left, height=18, activestyle="none")
        self.chat_archive_list.pack(fill="both", expand=True, pady=(10, 0))
        self.chat_archive_list.bind("<<ListboxSelect>>", self.on_chat_archive_select)

        main = ttk.Frame(paned, padding=12, style="Panel.TFrame")
        paned.add(main, weight=4)
        self.chat_title_var = tk.StringVar(value="开始对话")
        ttk.Label(main, textvariable=self.chat_title_var, font=("Microsoft YaHei", 16, "bold"), style="Panel.TLabel").pack(anchor="w")
        self.chat_text = tk.Text(main, wrap="word")
        self.chat_text.pack(fill="both", expand=True, pady=10)
        row = ttk.Frame(main, style="Panel.TFrame")
        row.pack(fill="x")
        self.question_text = tk.Text(row, height=4, wrap="word")
        self.question_text.pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="提问", command=self.ask_question).pack(side="left", padx=(8, 0), fill="y")

    def _build_settings_view(self):
        frame = self._view_frame("settings")
        outer = ttk.Frame(frame, padding=22, style="Panel.TFrame")
        outer.pack(fill="both", expand=True, padx=16, pady=16)
        ttk.Label(outer, text="设置", font=("Microsoft YaHei", 18, "bold"), style="Panel.TLabel").pack(anchor="w")

        form = ttk.Frame(outer, style="Panel.TFrame")
        form.pack(fill="x", pady=12)
        self.username_var = tk.StringVar()
        self.theme_var = tk.StringVar()
        self.provider_var = tk.StringVar()
        self.base_url_var = tk.StringVar()
        self.api_key_var = tk.StringVar()
        self.model_var = tk.StringVar()
        self.temperature_var = tk.StringVar()
        self.max_tokens_var = tk.StringVar()
        self.top_k_var = tk.StringVar()

        self._field(form, "用户名", self.username_var, 0, 0)
        ttk.Label(form, text="软件色调", style="Panel.TLabel").grid(row=0, column=2, sticky="w", padx=(18, 6), pady=6)
        theme_combo = ttk.Combobox(form, textvariable=self.theme_var, values=["paper", "ocean", "ink"], state="readonly")
        theme_combo.grid(row=0, column=3, sticky="ew", pady=6)
        theme_combo.bind("<<ComboboxSelected>>", lambda _e: self.apply_theme(self.theme_var.get()))

        ttk.Separator(outer).pack(fill="x", pady=10)
        self._combo_field(form, "供应商", self.provider_var, ["lmstudio", "siliconflow", "deepseek"], 1, 0)
        self._field(form, "Base URL", self.base_url_var, 2, 0, span=3)
        self._field(form, "API Key", self.api_key_var, 3, 0, span=3, show="*")
        self._field(form, "聊天模型", self.model_var, 4, 0, span=3)
        self._field(form, "温度", self.temperature_var, 5, 0)
        self._field(form, "引用片段数", self.top_k_var, 5, 2)
        self._field(form, "最大输出 Tokens", self.max_tokens_var, 6, 0)
        for i in range(4):
            form.columnconfigure(i, weight=1 if i % 2 else 0)

        buttons = ttk.Frame(outer, style="Panel.TFrame")
        buttons.pack(fill="x", pady=12)
        ttk.Button(buttons, text="保存设置", command=self.save_settings).pack(side="left")
        self.test_button = ttk.Button(buttons, text="测试连接", command=self.test_api)
        self.test_button.pack(side="left", padx=8)
        self.test_status_var = tk.StringVar(value="连接状态：未响应")
        ttk.Label(outer, textvariable=self.test_status_var, style="Panel.TLabel", wraplength=900).pack(anchor="w", pady=(0, 8))
        self.provider_help_var = tk.StringVar()
        ttk.Label(outer, textvariable=self.provider_help_var, style="Panel.TLabel", wraplength=900).pack(anchor="w")

    def _field(self, parent, label, var, row, col, span=1, show=None):
        ttk.Label(parent, text=label, style="Panel.TLabel").grid(row=row, column=col, sticky="w", padx=(0, 6), pady=6)
        entry = ttk.Entry(parent, textvariable=var, show=show)
        entry.grid(row=row, column=col + 1, columnspan=span, sticky="ew", pady=6)
        return entry

    def _combo_field(self, parent, label, var, values, row, col):
        ttk.Label(parent, text=label, style="Panel.TLabel").grid(row=row, column=col, sticky="w", padx=(0, 6), pady=6)
        combo = ttk.Combobox(parent, textvariable=var, values=values, state="readonly")
        combo.grid(row=row, column=col + 1, sticky="ew", pady=6)
        combo.bind("<<ComboboxSelected>>", lambda _e: self.apply_provider_preset(False))
        return combo

    def show_view(self, name):
        for frame in self.views.values():
            frame.lower()
        self.views[name].lift()
        if name == "chat":
            self.render_archive_lists()
        self.status_var.set({"home": "开屏", "kb": "知识库创建", "chat": "开始对话", "settings": "设置"}[name])

    def force_show_window(self):
        self.state("normal")
        self.deiconify()
        self.update_idletasks()
        self.lift()
        try:
            self.focus_force()
        except Exception:
            pass
        try:
            self.attributes("-topmost", True)
            self.after(900, lambda: self.attributes("-topmost", False))
        except Exception:
            pass

    def load_state(self):
        self.archives = app.list_archives()
        if not self.current_archive_id and self.archives:
            self.current_archive_id = self.archives[0]["id"]
        self.load_settings()
        self.render_archive_lists()
        if self.current_archive_id:
            self.load_archive(self.current_archive_id)
        self.show_view("home")

    def load_settings(self):
        config = app.current_config()
        self.user_label.config(text=f"{config.get('username', '读者')}，你好")
        self.username_var.set(config.get("username", "读者"))
        self.theme_var.set(config.get("theme", "paper"))
        self.provider_var.set(config.get("provider", "lmstudio"))
        self.base_url_var.set(config.get("base_url", ""))
        self.api_key_var.set(config.get("api_key", ""))
        self.model_var.set(config.get("chat_model", ""))
        self.temperature_var.set(config.get("temperature", "0.35"))
        self.max_tokens_var.set(config.get("max_tokens", "1600"))
        self.top_k_var.set(config.get("top_k", "8"))
        self.apply_theme(self.theme_var.get())
        self.update_provider_help()

    def render_archive_lists(self):
        items = []
        for archive in self.archives:
            mark = " ✓" if archive.get("has_archive_summary") else ""
            items.append(f"{archive['name']}{mark}  ({archive.get('doc_count', 0)} 文件 / {archive.get('chunk_count', 0)} 片段)")
        for listbox in (self.archive_list, self.chat_archive_list):
            listbox.delete(0, "end")
            for item in items:
                listbox.insert("end", item)
            for idx, archive in enumerate(self.archives):
                if archive["id"] == self.current_archive_id:
                    listbox.selection_clear(0, "end")
                    listbox.selection_set(idx)
                    listbox.see(idx)
                    break

    def archive_id_from_listbox(self, listbox):
        selection = listbox.curselection()
        if not selection:
            return None
        idx = selection[0]
        if idx >= len(self.archives):
            return None
        return self.archives[idx]["id"]

    def on_archive_select(self, _event=None):
        archive_id = self.archive_id_from_listbox(self.archive_list)
        if archive_id:
            self.load_archive(archive_id)

    def on_chat_archive_select(self, _event=None):
        archive_id = self.archive_id_from_listbox(self.chat_archive_list)
        if archive_id:
            self.load_archive(archive_id)

    def load_archive(self, archive_id):
        self.current_archive_id = archive_id
        self.current_detail = app.archive_detail(archive_id)
        self.kb_title_var.set(self.current_detail["archive"]["name"])
        self.chat_title_var.set(self.current_detail["archive"]["name"])
        self.render_archive_lists()
        self.render_chapters()
        self.render_archive_summary()
        self.render_chat_history()
        job = self.current_detail.get("job") or {}
        if job.get("status") in ("queued", "running"):
            self.poll_job(job.get("id"))

    def refresh_current(self):
        self.archives = app.list_archives()
        if self.current_archive_id:
            self.load_archive(self.current_archive_id)
        else:
            self.render_archive_lists()

    def list_chapters(self):
        if not self.current_archive_id:
            return []
        with app.connect_db() as conn:
            rows = conn.execute(
                """
                SELECT c.archive_id, c.document_id, d.filename, c.title, COUNT(*) AS chunk_count,
                       SUM(LENGTH(c.text)) AS char_count, MIN(c.ord) AS first_ord
                FROM chunks c
                JOIN documents d ON d.id=c.document_id
                WHERE c.archive_id=?
                GROUP BY c.document_id, c.title
                ORDER BY c.document_id, first_ord
                """,
                (self.current_archive_id,),
            ).fetchall()
            result = []
            for row in rows:
                source_key = f"doc:{row['document_id']}:chapter:{row['title']}"
                summary = app.get_summary(conn, self.current_archive_id, "chapter", source_key)
                item = dict(row)
                item["source_key"] = source_key
                item["status"] = "已总结" if summary else "未总结"
                item["summary"] = summary
                result.append(item)
            return result

    def render_chapters(self):
        self.chapter_tree.delete(*self.chapter_tree.get_children())
        self.chapter_items = {}
        for idx, chapter in enumerate(self.list_chapters()):
            item_id = f"chapter:{idx}"
            self.chapter_items[item_id] = chapter
            self.chapter_tree.insert(
                "",
                "end",
                iid=item_id,
                text=chapter["title"],
                values=(chapter["status"], chapter["chunk_count"], chapter["char_count"] or 0, chapter["filename"]),
            )
        self.summary_text.delete("1.0", "end")
        self.chapter_info_var.set("请选择章节")

    def render_archive_summary(self):
        self.archive_summary_text.delete("1.0", "end")
        self.archive_summary_text.insert("1.0", "尚未生成。")
        if not self.current_archive_id:
            return
        with app.connect_db() as conn:
            summary = app.get_summary(conn, self.current_archive_id, "archive", "main")
        self.archive_summary_text.delete("1.0", "end")
        self.archive_summary_text.insert("1.0", summary or "尚未生成。")

    def on_chapter_select(self, _event=None):
        selection = self.chapter_tree.selection()
        if not selection:
            return
        chapter = self.chapter_items.get(selection[0])
        self.current_chapter = chapter
        self.chapter_info_var.set(f"{chapter['title']} · {chapter['status']} · {chapter['chunk_count']} 个片段")
        self.summary_text.delete("1.0", "end")
        self.summary_text.insert("1.0", chapter.get("summary") or "")

    def create_archive(self):
        name = self.new_archive_var.get().strip()
        if not name:
            messagebox.showinfo(APP_TITLE, "先给知识库起个名字。")
            return
        self.current_archive_id = app.create_archive(name)
        self.new_archive_var.set("")
        self.refresh_current()

    def delete_current_archive(self):
        if not self.current_archive_id:
            return
        if not messagebox.askyesno(APP_TITLE, "删除当前知识库及其文本、摘要、聊天记录？此操作不可恢复。"):
            return
        app.delete_archive(self.current_archive_id)
        self.current_archive_id = None
        self.archives = app.list_archives()
        if self.archives:
            self.current_archive_id = self.archives[0]["id"]
        self.refresh_current()

    def import_text_file(self):
        if not self.current_archive_id:
            messagebox.showinfo(APP_TITLE, "请先选择或创建知识库。")
            return
        path = filedialog.askopenfilename(filetypes=[("Text files", "*.txt *.md"), ("All files", "*.*")])
        if not path:
            return

        def task():
            with open(path, "rb") as fh:
                raw = fh.read()
            text = raw.decode("utf-8", errors="replace")
            return app.import_document(self.current_archive_id, os.path.basename(path), text)

        self.run_task("正在导入并切分小说……", task, lambda result: self.after_import(result))

    def after_import(self, result):
        self.status_var.set(f"导入完成：{result['chars']} 字，{result['chunks']} 个片段")
        self.refresh_current()

    def selected_chapters(self):
        return [self.chapter_items[item] for item in self.chapter_tree.selection() if item in self.chapter_items]

    def add_chapter(self):
        if not self.current_archive_id:
            return
        title = simpledialog.askstring(APP_TITLE, "新章节标题：", parent=self)
        if not title:
            return
        dialog = TextDialog(self, "新增章节", "请输入章节原文：")
        text = dialog.result
        if not text:
            return
        with app.connect_db() as conn:
            doc = conn.execute(
                "SELECT id FROM documents WHERE archive_id=? ORDER BY id LIMIT 1",
                (self.current_archive_id,),
            ).fetchone()
            if doc:
                document_id = doc["id"]
            else:
                cur = conn.execute(
                    "INSERT INTO documents(archive_id, filename, title, char_count, created_at) VALUES(?, ?, ?, ?, ?)",
                    (self.current_archive_id, "manual.txt", "手动章节", len(text), app.now_ts()),
                )
                document_id = int(cur.lastrowid)
            ord_row = conn.execute(
                "SELECT COALESCE(MAX(ord), -1) + 1 AS next_ord FROM chunks WHERE document_id=?",
                (document_id,),
            ).fetchone()
            terms = app.Counter(app.tokenize(text))
            cur = conn.execute(
                "INSERT INTO chunks(archive_id, document_id, ord, title, text, char_start, char_end, token_count) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                (self.current_archive_id, document_id, ord_row["next_ord"], title, text, 0, len(text), sum(terms.values())),
            )
            chunk_id = int(cur.lastrowid)
            conn.executemany(
                "INSERT INTO term_index(archive_id, chunk_id, term, freq) VALUES(?, ?, ?, ?)",
                [(self.current_archive_id, chunk_id, term, freq) for term, freq in terms.items()],
            )
        recompute_archive_stats(self.current_archive_id)
        self.refresh_current()

    def delete_chapter(self):
        chapters = self.selected_chapters()
        if not chapters:
            messagebox.showinfo(APP_TITLE, "请先选择章节。")
            return
        if not messagebox.askyesno(APP_TITLE, f"删除选中的 {len(chapters)} 个章节及其原文片段和摘要？"):
            return
        with app.connect_db() as conn:
            for chapter in chapters:
                rows = conn.execute(
                    "SELECT id FROM chunks WHERE archive_id=? AND document_id=? AND title=?",
                    (self.current_archive_id, chapter["document_id"], chapter["title"]),
                ).fetchall()
                chunk_ids = [row["id"] for row in rows]
                conn.execute(
                    "DELETE FROM chunks WHERE archive_id=? AND document_id=? AND title=?",
                    (self.current_archive_id, chapter["document_id"], chapter["title"]),
                )
                conn.execute(
                    "DELETE FROM summaries WHERE archive_id=? AND scope='chapter' AND source_key=?",
                    (self.current_archive_id, chapter["source_key"]),
                )
                for chunk_id in chunk_ids:
                    conn.execute(
                        "DELETE FROM summaries WHERE archive_id=? AND scope='chunk' AND source_key=?",
                        (self.current_archive_id, f"chunk:{chunk_id}"),
                    )
        recompute_archive_stats(self.current_archive_id)
        self.refresh_current()

    def merge_chapters(self):
        chapters = self.selected_chapters()
        if len(chapters) < 2:
            messagebox.showinfo(APP_TITLE, "请至少选择两个章节。")
            return
        target_title = simpledialog.askstring(APP_TITLE, "合并后的章节标题：", initialvalue=chapters[0]["title"], parent=self)
        if not target_title:
            return
        target_doc_id = chapters[0]["document_id"]
        merged_summaries = []
        with app.connect_db() as conn:
            for chapter in chapters:
                if chapter.get("summary"):
                    merged_summaries.append(f"## {chapter['title']}\n{chapter['summary']}")
                conn.execute(
                    "UPDATE chunks SET document_id=?, title=? WHERE archive_id=? AND document_id=? AND title=?",
                    (target_doc_id, target_title, self.current_archive_id, chapter["document_id"], chapter["title"]),
                )
                conn.execute(
                    "DELETE FROM summaries WHERE archive_id=? AND scope='chapter' AND source_key=?",
                    (self.current_archive_id, chapter["source_key"]),
                )
            if merged_summaries:
                app.upsert_summary(
                    conn,
                    self.current_archive_id,
                    "chapter",
                    f"doc:{target_doc_id}:chapter:{target_title}",
                    target_title,
                    "\n\n".join(merged_summaries),
                )
        recompute_archive_stats(self.current_archive_id)
        self.refresh_current()

    def rename_chapter(self):
        chapter = self.current_chapter
        if not chapter:
            messagebox.showinfo(APP_TITLE, "请先选择章节。")
            return
        new_title = simpledialog.askstring(APP_TITLE, "新的章节标题：", initialvalue=chapter["title"], parent=self)
        if not new_title or new_title == chapter["title"]:
            return
        with app.connect_db() as conn:
            conn.execute(
                "UPDATE chunks SET title=? WHERE archive_id=? AND document_id=? AND title=?",
                (new_title, self.current_archive_id, chapter["document_id"], chapter["title"]),
            )
            if chapter.get("summary"):
                conn.execute(
                    "DELETE FROM summaries WHERE archive_id=? AND scope='chapter' AND source_key=?",
                    (self.current_archive_id, chapter["source_key"]),
                )
                app.upsert_summary(
                    conn,
                    self.current_archive_id,
                    "chapter",
                    f"doc:{chapter['document_id']}:chapter:{new_title}",
                    new_title,
                    chapter["summary"],
                )
        recompute_archive_stats(self.current_archive_id)
        self.refresh_current()

    def save_chapter_summary(self):
        chapter = self.current_chapter
        if not chapter:
            messagebox.showinfo(APP_TITLE, "请先选择章节。")
            return
        content = self.summary_text.get("1.0", "end").strip()
        with app.connect_db() as conn:
            app.upsert_summary(
                conn,
                self.current_archive_id,
                "chapter",
                chapter["source_key"],
                chapter["title"],
                content,
            )
        self.refresh_current()
        self.status_var.set("章节总结已保存")

    def start_memory_build(self, force):
        if not self.current_archive_id:
            return
        if force and not messagebox.askyesno(APP_TITLE, "强制重建会重新调用模型生成所有摘要，可能消耗较多时间和 API 额度。继续吗？"):
            return
        self.save_settings(silent=True)
        job = app.start_memory_build(self.current_archive_id, force)
        self.poll_job(job.get("id"))

    def poll_job(self, job_id):
        if not job_id:
            return
        job = app.JOBS.get(job_id, {})
        total = job.get("total") or 0
        done = job.get("done") or 0
        self.progress.config(maximum=max(total, 1), value=done)
        self.memory_status_var.set(f"{job.get('stage', '任务')}：{job.get('message', '')} ({done}/{total})")
        running = job.get("status") in ("queued", "running")
        self.build_button.config(state="disabled" if running else "normal")
        self.rebuild_button.config(state="disabled" if running else "normal")
        if running:
            self.jobs_after_id = self.after(1200, lambda: self.poll_job(job_id))
        else:
            self.refresh_current()
            if job.get("status") == "error":
                messagebox.showerror(APP_TITLE, job.get("error", "生成失败"))

    def render_chat_history(self):
        self.chat_text.delete("1.0", "end")
        messages = (self.current_detail or {}).get("messages") or []
        if not messages:
            self.chat_text.insert("end", "选择知识库后就可以提问。回答会结合知识库记忆和原文片段。\n")
            return
        for msg in messages:
            role = "你" if msg["role"] == "user" else "助手"
            self.chat_text.insert("end", f"{role}：{msg['content']}\n\n")

    def ask_question(self):
        if not self.current_archive_id:
            return
        question = self.question_text.get("1.0", "end").strip()
        if not question:
            return
        self.question_text.delete("1.0", "end")
        self.chat_text.insert("end", f"你：{question}\n\n助手：正在检索知识库并调用模型……\n\n")
        self.run_task(
            "正在调用模型回答……",
            lambda: app.answer_question_with_memory(self.current_archive_id, question),
            self.after_answer,
        )

    def after_answer(self, result):
        self.chat_text.insert("end", result["answer"] + "\n\n")
        if result.get("contexts"):
            self.chat_text.insert("end", "引用片段：\n")
            for idx, item in enumerate(result["contexts"], 1):
                self.chat_text.insert("end", f"{idx}. {item['title']} · 分数 {item.get('score')}\n")
            self.chat_text.insert("end", "\n")
        self.chat_text.see("end")

    def apply_provider_preset(self, force=False):
        provider = self.provider_var.get()
        preset = app.PROVIDER_PRESETS.get(provider)
        if preset:
            if force or not self.base_url_var.get():
                self.base_url_var.set(preset["base_url"])
            if force or not self.model_var.get() or self.model_var.get() == "local-model":
                self.model_var.set(preset["chat_model"])
            if provider == "lmstudio" and force:
                self.api_key_var.set("")
        self.update_provider_help()

    def update_provider_help(self):
        provider = self.provider_var.get()
        help_text = {
            "lmstudio": "LM Studio：先加载模型并开启 Local Server，默认 Base URL 为 http://localhost:1234/v1。",
            "siliconflow": "硅基流动：需要 API Key，模型名填写账户可用模型。",
            "deepseek": "DeepSeek：需要 API Key，模型名以 DeepSeek 控制台和官方文档为准。",
        }.get(provider, "")
        self.provider_help_var.set(help_text)

    def settings_payload(self):
        return {
            "username": self.username_var.get().strip() or "读者",
            "theme": self.theme_var.get() or "paper",
            "provider": self.provider_var.get() or "lmstudio",
            "base_url": self.base_url_var.get().strip(),
            "api_key": self.api_key_var.get().strip(),
            "chat_model": self.model_var.get().strip(),
            "temperature": self.temperature_var.get().strip() or "0.35",
            "max_tokens": self.max_tokens_var.get().strip() or "1600",
            "top_k": self.top_k_var.get().strip() or "8",
        }

    def save_settings(self, silent=False):
        config = app.save_config(self.settings_payload())
        self.apply_theme(config.get("theme", "paper"))
        self.user_label.config(text=f"{config.get('username', '读者')}，你好")
        if not silent:
            messagebox.showinfo(APP_TITLE, "设置已保存。")

    def test_api(self):
        self.test_button.config(state="disabled")
        self.test_status_var.set("连接状态：测试中")
        payload = self.settings_payload()

        def task():
            config = app.save_config(payload)
            return app.call_chat_api(
                [
                    {"role": "system", "content": "你只需要用中文简短回答。"},
                    {"role": "user", "content": "请回复：连接成功"},
                ],
                config,
            )

        self.run_task("正在测试 API 连接……", task, self.after_test_api, self.after_test_api_error)

    def after_test_api(self, result):
        self.test_button.config(state="normal")
        self.test_status_var.set(f"连接状态：连接成功；模型返回：{result}")

    def after_test_api_error(self, error):
        self.test_button.config(state="normal")
        self.test_status_var.set(f"连接状态：未响应；{error}")

    def run_task(self, status, task, on_done, on_error=None):
        self.status_var.set(status)
        result_queue, callback = run_async(task, on_done)
        self.async_tasks.append((result_queue, callback, on_error))
        self.after(120, self.check_async_tasks)

    def check_async_tasks(self):
        pending = []
        for result_queue, callback, on_error in self.async_tasks:
            try:
                state, value = result_queue.get_nowait()
            except queue.Empty:
                pending.append((result_queue, callback, on_error))
                continue
            if state == "ok":
                self.status_var.set("就绪")
                if callback:
                    callback(value)
            else:
                self.status_var.set("出错")
                if on_error:
                    on_error(value)
                else:
                    messagebox.showerror(APP_TITLE, str(value))
        self.async_tasks = pending
        if self.async_tasks:
            self.after(120, self.check_async_tasks)


def smoke_test():
    app.init_db()
    config = app.current_config()
    assert "username" in config
    assert "theme" in config
    return 0


def main():
    log_event("main entered")
    if "--smoke-test" in sys.argv:
        log_event("smoke test entered")
        return smoke_test()
    try:
        root = NovelMemoryDesktop()
        log_event("entering mainloop")
        root.mainloop()
        log_event("mainloop exited")
    except Exception:
        detail = traceback.format_exc()
        log_event("fatal error\n" + detail)
        try:
            messagebox.showerror(APP_TITLE, "启动失败，错误日志已写入：\n" + log_path() + "\n\n" + detail[:1200])
        except Exception:
            pass
        raise
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
