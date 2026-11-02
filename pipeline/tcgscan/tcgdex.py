"""Polite, disk-cached client for the TCGdex API (https://tcgdex.dev).

TCGdex is free and needs no key, but asks users to be considerate and cache.
Everything fetched here is cached under `cache_dir`, so repeated pipeline runs
only hit the API for things that are missing or older than the given max age.
"""
from __future__ import annotations

import json
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.parse import quote

import requests

API_BASE = "https://api.tcgdex.net/v2"
USER_AGENT = "card-scanner-pipeline/0.1 (personal TCG price scanner; cached, low volume)"

log = logging.getLogger(__name__)


def safe_name(segment: str) -> str:
    """Make an id usable as a file name (card ids can contain '?', '!' etc.)."""
    return quote(segment, safe="-_.") or "_"


class TCGdexClient:
    def __init__(self, cache_dir: Path | str, workers: int = 8, timeout: float = 30, retries: int = 6):
        self.cache_dir = Path(cache_dir)
        self.workers = workers
        self.timeout = timeout
        self.retries = retries
        self._local = threading.local()
        self.http_requests = 0
        self._count_lock = threading.Lock()

    # ------------------------------------------------------------------ http
    def _session(self) -> requests.Session:
        s = getattr(self._local, "session", None)
        if s is None:
            s = requests.Session()
            s.headers["User-Agent"] = USER_AGENT
            self._local.session = s
        return s

    def _http_get(self, url: str) -> requests.Response | None:
        """GET with retries. Returns None on 404."""
        delay = 1.0
        last_err: Exception | str = ""
        for _ in range(self.retries):
            try:
                with self._count_lock:
                    self.http_requests += 1
                r = self._session().get(url, timeout=self.timeout)
            except requests.RequestException as e:  # network hiccup
                last_err = e
            else:
                if r.status_code == 200:
                    return r
                if r.status_code == 404:
                    return None
                if r.status_code not in (408, 425, 429, 500, 502, 503, 504):
                    r.raise_for_status()
                last_err = f"HTTP {r.status_code}"
                retry_after = r.headers.get("Retry-After", "")
                if retry_after.isdigit():
                    delay = max(delay, float(retry_after))
            time.sleep(delay)
            delay = min(delay * 2, 60)
        raise RuntimeError(f"giving up on {url}: {last_err}")

    # ------------------------------------------------------------------ json
    def get_json(self, *segments: str, max_age_h: float | None = None) -> Any:
        """GET {API_BASE}/{seg1}/{seg2}/... with an on-disk cache.

        max_age_h=None caches forever. A 404 is cached as None.
        """
        url = API_BASE + "/" + "/".join(quote(s, safe="") for s in segments)
        cache_file = self.cache_dir / "json" / Path(*[safe_name(s) for s in segments[:-1]]) / (
            safe_name(segments[-1]) + ".json"
        )
        if cache_file.exists():
            age_h = (time.time() - cache_file.stat().st_mtime) / 3600
            if max_age_h is None or age_h < max_age_h:
                return json.loads(cache_file.read_text("utf-8"))
        r = self._http_get(url)
        data = None if r is None else r.json()
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = cache_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False), "utf-8")
        tmp.replace(cache_file)
        return data

    # ---------------------------------------------------------------- images
    def get_image(self, image_base: str, dest: Path, quality: str = "low", ext: str = "webp") -> bool:
        """Download `{image_base}/{quality}.{ext}` to dest unless it already exists."""
        if dest.exists() and dest.stat().st_size > 0:
            return True
        r = self._http_get(f"{image_base}/{quality}.{ext}")
        if r is None or not r.content:
            return False
        ctype = r.headers.get("Content-Type", "image/")
        if not ctype.startswith("image/"):
            log.warning("not an image (%s): %s", ctype, image_base)
            return False
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(".tmp")
        tmp.write_bytes(r.content)
        tmp.replace(dest)
        return True

    # -------------------------------------------------------------- parallel
    def map(self, fn: Callable[[Any], Any], items: Iterable[Any], desc: str,
            max_fail_frac: float = 0.05) -> dict[Any, Any]:
        """Run fn over items in a thread pool. Failed items map to None.

        Raises if more than max_fail_frac of the items failed (likely an outage).
        """
        items = list(items)
        results: dict[Any, Any] = {}
        failures = 0
        if not items:
            return results
        t0 = time.time()
        with ThreadPoolExecutor(self.workers) as ex:
            futs = {ex.submit(fn, it): it for it in items}
            for i, f in enumerate(as_completed(futs), 1):
                it = futs[f]
                try:
                    results[it] = f.result()
                except Exception as e:  # keep going, count failures
                    failures += 1
                    results[it] = None
                    if failures <= 10:
                        log.warning("%s failed for %r: %s", desc, it, e)
                if i % 1000 == 0 or i == len(items):
                    log.info("%s: %d/%d (%.0fs, %d failed)", desc, i, len(items), time.time() - t0, failures)
        if failures > max_fail_frac * len(items):
            raise RuntimeError(f"{desc}: {failures}/{len(items)} failed - aborting")
        return results
