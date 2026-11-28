"""Embedding helpers for summary and portable source-text indexes."""

import hashlib
import json
import math
from pathlib import Path
import threading
import struct
import urllib.error
import urllib.parse
import urllib.request


MODEL_ID = "BAAI/bge-small-zh-v1.5"
DEFAULT_SERVICE_URL = "http://127.0.0.1:1234/v1"
PART_CHARS = 360
PART_OVERLAP = 40
SOURCE_PART_CHARS = 600
SOURCE_PART_OVERLAP = 60
_model = None
_model_lock = threading.Lock()
_provider = ""


def cache_dir():
    return Path(__file__).resolve().parent / "assets" / "embedding_cache"


def load_model():
    global _model, _provider
    if _model is not None:
        return _model
    with _model_lock:
        if _model is not None:
            return _model
        directory = cache_dir()
        if not directory.is_dir():
            raise RuntimeError("程序缺少内置向量模型文件；请重新下载完整安装包")
        try:
            import onnxruntime as ort
            from fastembed import TextEmbedding
        except ImportError as exc:
            raise RuntimeError("程序缺少本地向量推理组件；请重新下载完整安装包") from exc
        available = ort.get_available_providers()
        # DirectML uses the installed GPU on Windows. CPU remains a fallback.
        providers = ["DmlExecutionProvider", "CPUExecutionProvider"] if "DmlExecutionProvider" in available else ["CPUExecutionProvider"]
        _model = TextEmbedding(model_name=MODEL_ID, cache_dir=str(directory), local_files_only=True,
                               providers=providers, threads=8)
        _provider = providers[0]
        return _model


def provider_name():
    load_model()
    return "显卡（DirectML）" if _provider == "DmlExecutionProvider" else "CPU"


def parts(text):
    text = (text or "").strip()
    if not text:
        return []
    out = []
    start = 0
    while start < len(text):
        end = min(len(text), start + PART_CHARS)
        out.append(text[start:end])
        if end == len(text):
            break
        start = end - PART_OVERLAP
    return out


def source_parts(text):
    """Return stable, chapter-relative text spans for the cross-device index."""
    text = text or ""
    out = []
    start = 0
    while start < len(text):
        end = min(len(text), start + SOURCE_PART_CHARS)
        segment = text[start:end]
        if segment.strip():
            out.append((start, end, segment))
        if end == len(text):
            break
        start = end - SOURCE_PART_OVERLAP
    return out


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def normalize(values):
    numbers = [float(value) for value in values]
    if not numbers or any(not math.isfinite(value) for value in numbers):
        raise ValueError("嵌入模型返回无效向量")
    magnitude = math.sqrt(sum(value * value for value in numbers))
    if magnitude <= 0:
        raise ValueError("嵌入模型返回零向量")
    return [value / magnitude for value in numbers]


def embed(texts):
    if not texts:
        return []
    vectors = [normalize(vector) for vector in load_model().embed(texts, batch_size=12)]
    if len(vectors) != len(texts) or len({len(vector) for vector in vectors}) != 1:
        raise RuntimeError("内置模型返回的向量数量或维度无效")
    return vectors


def service_url(base_url):
    parsed = urllib.parse.urlparse((base_url or DEFAULT_SERVICE_URL).strip().rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("LM Studio 向量渠道仅允许连接本机地址")
    if parsed.username or parsed.password or not parsed.port:
        raise ValueError("LM Studio 地址格式示例：http://127.0.0.1:1234/v1")
    return parsed.geturl().rstrip("/")


def service_embed(texts, model, base_url=DEFAULT_SERVICE_URL, api_key="", online=False):
    if not (model or "").strip():
        raise ValueError("请填写嵌入模型 ID")
    if online:
        parsed = urllib.parse.urlparse((base_url or "").strip().rstrip("/"))
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("在线嵌入 API 必须使用有效的 HTTPS Base URL")
        if not (api_key or "").strip():
            raise ValueError("请填写在线嵌入 API Key")
        root = parsed.geturl().rstrip("/")
    else:
        root = service_url(base_url)
    url = root + "/embeddings"
    payload = json.dumps({"model": model.strip(), "input": texts}, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if online:
        headers["Authorization"] = "Bearer " + api_key.strip()
    request = urllib.request.Request(url, payload, headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read(500).decode("utf-8", "replace")
        raise RuntimeError(f"嵌入接口返回 HTTP {exc.code}：{detail}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError("无法连接嵌入接口；请检查地址、网络和模型状态") from exc
    rows = result.get("data") or []
    if len(rows) != len(texts):
        raise RuntimeError("嵌入接口返回的向量数量与输入数量不符")
    vectors = [None] * len(texts)
    for row in rows:
        index = int(row.get("index", -1))
        if index < 0 or index >= len(texts) or vectors[index] is not None:
            raise RuntimeError("嵌入接口返回的向量序号无效")
        vectors[index] = normalize(row.get("embedding") or [])
    if len({len(vector) for vector in vectors}) != 1:
        raise RuntimeError("嵌入接口返回的向量维度不一致")
    return vectors


def model_key(backend, model="", base_url=""):
    if backend == "builtin":
        return "builtin:" + MODEL_ID
    if backend == "lmstudio":
        if not (model or "").strip():
            raise ValueError("请填写 LM Studio 嵌入模型 ID")
        return "lmstudio:" + service_url(base_url) + ":" + model.strip()
    if backend == "online":
        parsed = urllib.parse.urlparse((base_url or "").strip().rstrip("/"))
        if parsed.scheme != "https" or not parsed.hostname or not (model or "").strip():
            raise ValueError("请填写在线嵌入 API 的 HTTPS 地址和模型 ID")
        return "online:" + parsed.geturl().rstrip("/") + ":" + model.strip()
    raise ValueError("不支持的向量化渠道")


def embed_with(backend, texts, model="", base_url="", api_key=""):
    if backend == "builtin":
        return embed(texts)
    if backend == "lmstudio":
        return service_embed(texts, model, base_url)
    if backend == "online":
        return service_embed(texts, model, base_url, api_key, online=True)
    raise ValueError("不支持的向量化渠道")


def pack(vector):
    return struct.pack("<" + "f" * len(vector), *vector)


def unpack(blob):
    if len(blob) % 4:
        raise ValueError("向量字节长度无效")
    return struct.unpack("<" + "f" * (len(blob) // 4), blob)


def cosine(blob, query):
    values = unpack(blob)
    if len(values) != len(query):
        return None
    return sum(a * b for a, b in zip(values, query))
