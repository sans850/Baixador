import os, json, re, traceback
from pathlib import Path
import yt_dlp

def _write_status(status_path, **data):
    p = Path(status_path)
    p.parent.mkdir(parents=True, exist_ok=True)
    old = {}
    try:
        old = json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        pass
    old.update(data)
    p.write_text(json.dumps(old, ensure_ascii=False), encoding="utf-8")

def download(url, quality, output_dir, status_path):
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)
    def hook(d):
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            done = d.get("downloaded_bytes", 0)
            pct = round(done * 100 / total, 1) if total else None
            speed = d.get("speed")
            msg = "Recebido %.1f MB" % (done / 1048576)
            if speed:
                msg += " • %.1f MB/s" % (speed / 1048576)
            _write_status(status_path, state="downloading", percent=pct, message=msg)
        elif d.get("status") == "finished":
            _write_status(status_path, state="processing", percent=100, message="Finalizando arquivo...")
    if quality == "audio":
        fmt = "bestaudio/best"
    elif quality == "best":
        fmt = "best/bestvideo+bestaudio"
    else:
        fmt = "best[height<=%s]/best" % quality
    opts = {
        "outtmpl": str(out / "%(title).120B [%(id)s].%(ext)s"),
        "format": fmt,
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "progress_hooks": [hook],
        "retries": 2,
        "fragment_retries": 2,
        "continuedl": True,
        "overwrites": False,
    }
    try:
        _write_status(status_path, state="analyzing", percent=0, message="Identificando a mídia...")
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            expected = ydl.prepare_filename(info)
        candidates = [p for p in out.iterdir() if p.is_file() and not p.name.endswith((".part", ".ytdl"))]
        if expected and Path(expected).exists():
            final_path = Path(expected)
        elif candidates:
            final_path = max(candidates, key=lambda p: p.stat().st_size)
        else:
            raise RuntimeError("A origem não retornou um arquivo baixado.")
        _write_status(status_path, state="finished", percent=100,
                      message="Download concluído.", filename=final_path.name,
                      filepath=str(final_path), filesize=final_path.stat().st_size)
        return str(final_path)
    except Exception as exc:
        _write_status(status_path, state="error", percent=None,
                      message=str(exc)[:650])
        return ""


def start_download(url, quality, output_dir, status_path):
    import threading
    thread = threading.Thread(
        target=download,
        args=(url, quality, output_dir, status_path),
        daemon=True
    )
    thread.start()
    return "started"
