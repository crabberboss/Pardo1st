"""Native-window entry point for Novel Memory's existing local web UI."""

import glob
import os
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.request

import app

WEBVIEW2_CLIENT_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
WEBVIEW2_DOWNLOAD_URL = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"
WEBVIEW2_INSTALLER = "MicrosoftEdgeWebView2RuntimeInstallerX64.exe"
DOTNET_MIN_RELEASE = 461808  # .NET Framework 4.7.2
DOTNET_DOWNLOAD_URL = "https://go.microsoft.com/fwlink/?LinkId=2088631"
DOTNET_INSTALLERS = (
    "ndp48-x86-x64-allos-enu.exe",
    "ndp48-x86-x64-allos-chs.exe",
    "NDP472-KB4054530-x86-x64-AllOS-ENU.exe",
)


def runtime_message(text: str, error: bool = False) -> None:
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            None, text, "帕朵一号 · 环境检测", 0x10 if error else 0x40
        )
    except Exception:
        pass


def runtime_log(text: str) -> None:
    try:
        log_path = os.path.join(app.data_dir(), "desktop-runtime-check.log")
        with open(log_path, "a", encoding="utf-8") as handle:
            handle.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
    except Exception:
        pass


def launcher_dir() -> str:
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def webview2_versions() -> list:
    versions = []
    try:
        import winreg
        locations = (
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\EdgeUpdate\Clients"),
            (winreg.HKEY_CURRENT_USER, r"SOFTWARE\Microsoft\EdgeUpdate\Clients"),
        )
        for root, path in locations:
            try:
                with winreg.OpenKey(root, path) as key:
                    with winreg.OpenKey(key, WEBVIEW2_CLIENT_GUID) as client:
                        value, _ = winreg.QueryValueEx(client, "pv")
                        if value:
                            versions.append(str(value))
            except OSError:
                continue
    except Exception:
        pass
    patterns = (
        os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"), "Microsoft", "EdgeWebView", "Application", "*", "msedgewebview2.exe"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Microsoft", "EdgeWebView", "Application", "*", "msedgewebview2.exe"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "EdgeWebView", "Application", "*", "msedgewebview2.exe"),
    )
    for pattern in patterns:
        if glob.glob(pattern):
            versions.append("file-installed")
    return versions


def dotnet_release() -> int:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full") as key:
            value, _ = winreg.QueryValueEx(key, "Release")
            return int(value)
    except Exception:
        return 0


def find_installer(names: tuple) -> str:
    roots = [launcher_dir(), os.path.join(launcher_dir(), "runtime")]
    meipass = getattr(sys, "_MEIPASS", "")
    if meipass:
        roots.extend([meipass, os.path.join(meipass, "runtime")])
    for root in roots:
        for name in names:
            path = os.path.join(root, name)
            if os.path.isfile(path):
                return path
    return ""


def download_installer(url: str, filename: str) -> str:
    target = os.path.join(tempfile.gettempdir(), filename)
    runtime_log(f"downloading {url} -> {target}")
    request = urllib.request.Request(url, headers={"User-Agent": "NovelMemory/2.9.10"})
    with urllib.request.urlopen(request, timeout=60) as response, open(target, "wb") as handle:
        while True:
            chunk = response.read(1024 * 256)
            if not chunk:
                break
            handle.write(chunk)
    return target


def run_installer(path: str) -> int:
    runtime_log(f"running installer {path}")
    result = subprocess.run([path, "/silent", "/install"], timeout=900)
    return int(result.returncode)


def ensure_runtime() -> bool:
    """Check the two runtimes that the packaged window actually needs."""
    if sys.platform != "win32":
        runtime_message("当前安装包仅支持 Windows。", True)
        return False
    if os.environ.get("PROCESSOR_ARCHITECTURE", "").lower() not in {"amd64", "x86_64"}:
        runtime_message("当前安装包为 Windows x64 版本，请在 64 位 Windows 上运行。", True)
        return False

    release = dotnet_release()
    runtime_log(f"dotnet release={release}")
    if release and release < DOTNET_MIN_RELEASE:
        local_installer = find_installer(DOTNET_INSTALLERS)
        runtime_message("检测到系统 .NET Framework 版本较旧，将尝试安装 .NET Framework 4.8。安装可能需要管理员权限并重启电脑。")
        try:
            installer = local_installer or download_installer(DOTNET_DOWNLOAD_URL, "ndp48-web-installer.exe")
            subprocess.run([installer, "/q", "/norestart"], timeout=900)
        except Exception as exc:
            runtime_log("dotnet install failed: " + str(exc))
        if dotnet_release() >= DOTNET_MIN_RELEASE:
            runtime_log("dotnet install completed")
        else:
            runtime_message(
                "缺少 .NET Framework 4.7.2 或更高版本。请安装 .NET Framework 4.8 后重新打开帕朵一号：\n"
                "https://dotnet.microsoft.com/download/dotnet-framework/net48",
                True,
            )
            return False
    elif not release:
        runtime_message(
            "无法检测到 .NET Framework 4.7.2 或更高版本。请先安装 .NET Framework 4.8 后重试：\n"
            "https://dotnet.microsoft.com/download/dotnet-framework/net48",
            True,
        )
        return False

    installed = webview2_versions()
    runtime_log("webview2=" + (", ".join(installed) if installed else "missing"))
    if installed:
        return True

    runtime_message("检测到缺少 Microsoft Edge WebView2 运行环境，正在自动安装，请稍候。")
    installer = find_installer((WEBVIEW2_INSTALLER,))
    try:
        if not installer:
            installer = download_installer(WEBVIEW2_DOWNLOAD_URL, WEBVIEW2_INSTALLER)
        run_installer(installer)
    except Exception as exc:
        runtime_log("webview2 install failed: " + str(exc))
    if webview2_versions():
        runtime_log("webview2 install completed")
        return True
    runtime_message(
        "WebView2 运行环境安装未完成。请手动安装后重试：\n"
        "https://developer.microsoft.com/microsoft-edge/webview2/\n\n"
        "也可以把 MicrosoftEdgeWebView2RuntimeInstallerX64.exe 放在程序同目录，再启动帕朵一号。",
        True,
    )
    return False


def show_startup_error(detail: str) -> None:
    try:
        log_path = os.path.join(app.data_dir(), "desktop-startup-error.log")
        with open(log_path, "a", encoding="utf-8") as handle:
            handle.write(detail + "\n")
    except Exception:
        log_path = "无法写入错误日志"
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            None, f"独立窗口启动失败。\n\n{detail[:1000]}\n\n日志：{log_path}",
            "Novel Memory", 0x10,
        )
    except Exception:
        pass


def log_webview_diagnostics() -> None:
    """Write import failures for the packaged WebView runtime; harmless in source runs."""
    try:
        import importlib
        lines = []
        for name in ("clr", "pythonnet", "webview.platforms.winforms"):
            try:
                importlib.import_module(name)
                lines.append(f"{name}: OK")
            except Exception:
                lines.append(f"{name}: FAILED\n{traceback.format_exc()}")
        log_path = os.path.join(app.data_dir(), "desktop-webview-debug.log")
        if any("FAILED" in line for line in lines):
            with open(log_path, "w", encoding="utf-8") as handle:
                handle.write("\n".join(lines) + "\n")
        elif os.path.exists(log_path):
            os.remove(log_path)
    except Exception:
        pass


def main() -> int:
    server = None
    server_thread = None
    smoke_failures = []
    try:
        if "--skip-runtime-check" not in sys.argv and not ensure_runtime():
            return 1
        log_webview_diagnostics()
        import webview

        app.init_db()
        port = app.find_port()
        server = app.QuietThreadingHTTPServer(("127.0.0.1", port), app.Handler)
        # The window owns the service lifetime. Do not use browser-tab inactivity shutdown here.
        server.browser_clients = app.BrowserClientTracker()
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()

        window = webview.create_window(
            "帕朵一号 · 小说知识库与改写",
            f"http://127.0.0.1:{port}/",
            width=1440,
            height=900,
            min_size=(1080, 700),
            background_color="#f7f1eb",
        )
        if "--smoke-test" in sys.argv:
            # Verify the actual WebView rendered the workspaces, then close its native window.
            def finish_smoke():
                time.sleep(1)
                try:
                    ready = window.evaluate_js(
                        "Boolean(document.querySelector('#sceneWorkspace') && "
                        "document.querySelector('#chapterRows') && "
                        "document.querySelector('#rewriteView') && "
                        "document.querySelector('#rewriteKeywordSearch') && "
                        "document.querySelector('#modelSelect') && "
                        "document.querySelector('#chatSettingsPanel') && "
                        "!document.querySelector('#generationView') && "
                        "!document.querySelector('#navGeneration') && "
                        "!document.querySelector('#storyTimelineSection'))"
                    )
                    if not ready:
                        smoke_failures.append("WebView 页面未显示完整工作区")
                    search_ok = window.evaluate_js("""(() => {
                        const previous = rewriteRows;
                        const search = document.querySelector('#rewriteKeywordSearch');
                        rewriteRows = [
                          {title:'第一章',memory:{keywords:'密室、交谈'},special_tags:[]},
                          {title:'第二章',memory:{keywords:'草原、远行'},special_tags:[]}
                        ];
                        search.value = '密室';
                        const result = visibleRewriteIndices();
                        rewriteRows = previous;
                        search.value = '';
                        return result.length === 1 && result[0] === 0;
                    })()""")
                    if not search_ok:
                        smoke_failures.append("关键词筛选未返回预期章节")
                    model_select_ok = window.evaluate_js("""(() => {
                        renderModelOptions(['model-a', 'model-b'], 'model-a');
                        const select = document.querySelector('#modelSelect');
                        return select.options.length === 3 && select.value === 'model-a' &&
                          document.querySelector('#chatModel').value === 'model-a';
                    })()""")
                    if not model_select_ok:
                        smoke_failures.append("模型列表下拉未正确填充")
                    drawer_ok = window.evaluate_js("""(() => {
                        toggleNavDrawer(true);
                        const opened = document.querySelector('.topbar').classList.contains('open') &&
                          document.querySelector('#navBackdrop').classList.contains('open');
                        toggleNavDrawer(false);
                        return opened && !document.querySelector('.topbar').classList.contains('open');
                    })()""")
                    if not drawer_ok:
                        smoke_failures.append("左侧功能栏抽屉未正确滑动")
                    hover_ok = window.evaluate_js("""(() => {
                        setNavHoverEnabled(true);
                        document.dispatchEvent(new MouseEvent('mousemove', {clientX: 5, clientY: 300, bubbles: true}));
                        const opened = document.querySelector('.topbar').classList.contains('open');
                        toggleNavDrawer(false);
                        setNavHoverEnabled(false);
                        const pinned = document.querySelector('.topbar').classList.contains('open') &&
                          !document.querySelector('#navBackdrop').classList.contains('open');
                        setNavHoverEnabled(true);
                        return opened && pinned;
                    })()""")
                    if not hover_ok:
                        smoke_failures.append("左侧 1/10 悬停滑出或开关未生效")
                    blur_ok = window.evaluate_js("""(() => {
                        setBackgroundBlur('chat', 12);
                        return getComputedStyle(document.documentElement).getPropertyValue('--blur-chat').trim() === '12px';
                    })()""")
                    if not blur_ok:
                        smoke_failures.append("分区背景虚化滑块未生效")
                    settings_drawer_ok = window.evaluate_js("""(() => {
                        toggleChatSettings(true);
                        const opened = document.querySelector('#chatSettingsPanel').classList.contains('open') &&
                          document.querySelector('#chatTabSessions').classList.contains('active');
                        switchChatSettingsTab('persona');
                        const persona = document.querySelector('#chatTabPersona').classList.contains('active') &&
                          document.querySelector('#chatTabSessions').classList.contains('active') === false;
                        switchChatSettingsTab('sessions');
                        toggleChatSettings(false);
                        return opened && persona && !document.querySelector('#chatSettingsPanel').classList.contains('open');
                    })()""")
                    if not settings_drawer_ok:
                        smoke_failures.append("对话设置抽屉或标签页未正确切换")
                    background_ok = window.evaluate_js("""(() => {
                        const pixel = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z3ZkAAAAASUVORK5CYII=';
                        localStorage.setItem('pardo.appBackground.v1', pixel);
                        loadAppBackground();
                        const applied = getComputedStyle(document.body).backgroundImage.includes('data:image/png');
                        clearAppBackground();
                        return applied && !getComputedStyle(document.body).backgroundImage.includes('data:image/png');
                    })()""")
                    if not background_ok:
                        smoke_failures.append("自定义背景导入/清除未正确应用")
                    if not window.evaluate_js("runRewriteChapter.toString().includes('整章一次性发送给模型改写')"):
                        smoke_failures.append("改写界面仍使用旧版分批流程")
                    chapter_prompt_ok = window.evaluate_js("""(() => {
                        const previous = rewriteRows;
                        const previousIndex = currentRewriteIndex;
                        rewriteRows = [{title:'测试章', document_id:0, has_rewrite:false,
                          chapter_prompt:'不应显示的提示词', rewrite_extra_instruction:'不应显示的额外要求',
                          rewrite_content:'', status:'', char_count:0}];
                        currentRewriteIndex = 0;
                        fillRewriteEditor(0);
                        const untouched = document.querySelector('#rewritePromptStatus').textContent.includes('本章尚未改写') &&
                          document.querySelector('#chapterRewritePrompt').value === '不应显示的提示词' &&
                          document.querySelector('#rewriteInstruction').value === '不应显示的额外要求';
                        rewriteRows[0].has_rewrite = true;
                        rewriteRows[0].chapter_prompt = '该章专属提示词';
                        rewriteRows[0].rewrite_extra_instruction = '该章专属要求';
                        fillRewriteEditor(0);
                        const rewritten = document.querySelector('#chapterRewritePrompt').value === '该章专属提示词' &&
                          document.querySelector('#rewriteInstruction').value === '该章专属要求';
                        rewriteRows = previous;
                        currentRewriteIndex = previousIndex;
                        fillRewriteEditor(previousIndex);
                        return untouched && rewritten;
                    })()""")
                    if not chapter_prompt_ok:
                        smoke_failures.append("章节提示词显示与未改写状态不正确")
                    if window.evaluate_js("Boolean(state && state.archives && state.archives.length)"):
                        window.evaluate_js("showView('rewrite')")
                        for _ in range(40):
                            if window.evaluate_js("document.querySelectorAll('#rewriteRows .rewrite-item').length > 0"):
                                break
                            time.sleep(0.25)
                        else:
                            smoke_failures.append("已有知识库的改写章节列表未显示")
                except Exception:
                    smoke_failures.append(traceback.format_exc())
                finally:
                    window.destroy()

            window.events.loaded += lambda: threading.Thread(target=finish_smoke, daemon=True).start()
        webview.start(gui="edgechromium", debug=False)
        if smoke_failures:
            with open(os.path.join(app.data_dir(), "desktop-smoke-error.log"), "w", encoding="utf-8") as handle:
                handle.write("\n".join(smoke_failures))
            return 1
        return 0
    except Exception:
        show_startup_error(traceback.format_exc())
        return 1
    finally:
        if server is not None:
            server.shutdown()
            server.server_close()
        if server_thread is not None:
            server_thread.join(timeout=3)


if __name__ == "__main__":
    raise SystemExit(main())
