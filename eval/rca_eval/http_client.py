"""基于 Python 标准库的 JSON HTTP 客户端。

只发送评测需要的 JSON 请求；不读取 API Key、环境变量或浏览器凭据，
也不会把请求头或响应原文写入日志。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from http.client import HTTPException
from typing import Any, Dict, Optional, Protocol
from urllib import error, request

DEFAULT_TIMEOUT_SECONDS = 30.0


class HttpTransportError(RuntimeError):
    """网络层失败，没有获得可解析的 HTTP 响应。"""


@dataclass(frozen=True)
class HttpResponse:
    status: int
    headers: Dict[str, str]
    body: str

    def json(self) -> Any:
        if not self.body or not self.body.strip():
            return None
        try:
            return json.loads(self.body)
        except ValueError as exc:
            raise ValueError("响应不是合法 JSON") from exc


class HttpClient(Protocol):
    def request(self, method: str, url: str,
                payload: Optional[Dict[str, Any]] = None,
                timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> HttpResponse:
        ...


class UrllibHttpClient:
    """urllib 实现；非 2xx 响应也会返回 HttpResponse，由调用方统一判断。"""

    def request(self, method: str, url: str,
                payload: Optional[Dict[str, Any]] = None,
                timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> HttpResponse:
        headers = {"Accept": "application/json"}
        data: Optional[bytes] = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"

        req = request.Request(
            url=url, data=data, headers=headers, method=method.upper())
        try:
            with request.urlopen(req, timeout=timeout_seconds) as response:
                return HttpResponse(
                    status=int(getattr(response, "status", response.getcode())),
                    headers={
                        str(key).lower(): str(value)
                        for key, value in response.headers.items()
                    },
                    body=response.read().decode("utf-8", errors="replace"),
                )
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            response_headers = exc.headers.items() if exc.headers is not None else ()
            return HttpResponse(
                status=int(exc.code),
                headers={str(key).lower(): str(value) for key, value in response_headers},
                body=body,
            )
        except (error.URLError, TimeoutError, OSError, HTTPException) as exc:
            reason = getattr(exc, "reason", exc)
            raise HttpTransportError("HTTP 请求失败: %s" % reason) from exc
        except ValueError as exc:
            raise HttpTransportError("HTTP 请求地址无效: %s" % exc) from exc
