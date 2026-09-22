# -*- coding: utf-8 -*-
"""手写 JSON-RPC over Streamable HTTP 客户端（不依赖 mcp sdk 的备用验证路径）。

MCP 2.0 Streamable HTTP 传输要点：
- 客户端向服务器端点（默认 /mcp）POST JSON-RPC 报文；
- Accept 头必须同时带 application/json 与 text/event-stream；
- initialize 响应头 mcp-session-id 是后续请求的会话凭证；
- 响应可能是普通 JSON，也可能是 SSE 流（data: 行），两种都要会解析。
流程：initialize → notifications/initialized → tools/list → tools/call。
"""
import json
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18300"
URL = BASE + "/mcp"
HEADERS = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}

_id = [0]


def rpc(method, params=None, notify=False, session=None):
    body = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        body["params"] = params
    if not notify:
        _id[0] += 1
        body["id"] = _id[0]
    req = urllib.request.Request(URL, json.dumps(body).encode("utf-8"), HEADERS)
    if session:
        req.add_header("mcp-session-id", session)
    with urllib.request.urlopen(req, timeout=30) as resp:
        sid = resp.headers.get("mcp-session-id")
        ctype = resp.headers.get("Content-Type", "")
        raw = resp.read().decode("utf-8")
    payload = None
    if not raw.strip():
        return payload, sid  # 通知类请求服务器返回 202 空体
    if "text/event-stream" in ctype:
        for line in raw.splitlines():
            if line.startswith("data:"):
                chunk = line[5:].strip()
                if chunk:
                    msg = json.loads(chunk)
                    if not notify and msg.get("id") == _id[0] and "result" in msg:
                        payload = msg["result"]
    else:
        msg = json.loads(raw)
        payload = None if notify else msg.get("result")
    return payload, sid


def show(tag, obj):
    print(f"\n===== {tag} =====")
    print(json.dumps(obj, ensure_ascii=False, indent=1))


def main():
    # 1) initialize 握手：声明协议版本与客户端能力
    result, session = rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "raw-jsonrpc-client", "version": "1.0.0"},
    })
    show("initialize 握手（serverInfo）", {
        "protocolVersion": result["protocolVersion"],
        "serverInfo": result["serverInfo"],
    })
    assert result["serverInfo"]["name"] == "order-mcp-server", result
    show("会话凭证 mcp-session-id", {"value": session})
    assert session, "服务器未返回 mcp-session-id"

    # 2) initialized 通知（无响应体）
    rpc("notifications/initialized", {}, notify=True, session=session)

    # 3) tools/list
    result, _ = rpc("tools/list", {}, session=session)
    tools = result["tools"]
    names = sorted(t["name"] for t in tools)
    show("tools/list 工具数与名称", {"count": len(tools), "names": names})
    assert names == ["create_order", "get_order_by_id", "list_orders_by_customer", "weekly_order_summary"], names
    show("tools/list 参数 schema（get_order_by_id）",
         next(t for t in tools if t["name"] == "get_order_by_id")["inputSchema"])

    # 4) tools/call 查种子订单
    result, _ = rpc("tools/call", {
        "name": "get_order_by_id",
        "arguments": {"orderId": "ORD-1001"},
    }, session=session)
    payload = json.loads(result["content"][0]["text"])
    show("tools/call get_order_by_id ORD-1001", payload)
    assert payload["id"] == "ORD-1001" and payload["amount"] == 399, payload

    # 5) tools/call 按客户查列表
    result, _ = rpc("tools/call", {
        "name": "list_orders_by_customer",
        "arguments": {"customerId": "C-001"},
    }, session=session)
    payload = json.loads(result["content"][0]["text"])
    show("tools/call list_orders_by_customer C-001（条数）", {"count": len(payload)})

    # 6) tools/call 汇总
    result, _ = rpc("tools/call", {"name": "weekly_order_summary", "arguments": {}}, session=session)
    payload = json.loads(result["content"][0]["text"])
    show("tools/call weekly_order_summary", payload)
    assert payload["recentCount"] >= 2, payload

    print("\n手写 JSON-RPC 客户端全部断言通过")


if __name__ == "__main__":
    main()
