# -*- coding: utf-8 -*-
"""MCP 协议实测客户端（官方 python sdk 2.2.0，Streamable HTTP 传输）。

流程：initialize 握手 → tools/list → tools/call（查/建/再查/汇总）。
每步打印 JSON 取值，任何断言失败以非零码退出。
"""
import json
import sys
import anyio
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18300"
URL = BASE + "/mcp"

EXPECT_TOOLS = {"get_order_by_id", "list_orders_by_customer", "create_order", "weekly_order_summary"}


def show(tag, obj):
    print(f"\n===== {tag} =====")
    print(json.dumps(obj, ensure_ascii=False, indent=1))


async def main():
    async with streamable_http_client(URL) as (read, write):
        async with ClientSession(read, write) as session:
            # 1) initialize 握手
            info = await session.initialize()
            show("initialize 握手（serverInfo）", {
                "protocolVersion": info.protocol_version,
                "serverInfo": {"name": info.server_info.name, "version": info.server_info.version},
                "instructions": (info.instructions or "")[:60],
            })
            assert info.server_info.name == "order-mcp-server", info.server_info

            # 2) tools/list
            listed = await session.list_tools()
            names = {t.name for t in listed.tools}
            descs = {t.name: (t.description or "") for t in listed.tools}
            show("tools/list 工具数与名称", {"count": len(listed.tools), "names": sorted(names)})
            assert names == EXPECT_TOOLS, names
            assert all(len(d) > 15 for d in descs.values()), descs
            show("tools/list 工具描述（截断）", {k: v[:42] + "..." for k, v in sorted(descs.items())})

            # 3) tools/call 查订单（种子数据 ORD-1001）
            res = await session.call_tool("get_order_by_id", {"orderId": "ORD-1001"})
            payload = json.loads(res.content[0].text)
            show("tools/call get_order_by_id ORD-1001", payload)
            assert payload["id"] == "ORD-1001" and payload["customerId"] == "C-001", payload

            # 4) tools/call 创建订单（amount 是 number，不能传字符串）
            res = await session.call_tool("create_order", {
                "customerId": "C-007", "product": "机械臂", "amount": 4500.00})
            created = json.loads(res.content[0].text)
            show("tools/call create_order C-007 机械臂 4500.00", created)
            assert created["status"] == "CREATED" and created["id"].startswith("ORD-"), created

            # 5) 再查询：创建的订单要能查到
            res = await session.call_tool("get_order_by_id", {"orderId": created["id"]})
            again = json.loads(res.content[0].text)
            show(f"tools/call get_order_by_id {created['id']}（回读）", again)
            assert again["product"] == "机械臂", again

            # 6) 组合工具：近 7 天汇总（种子 2 笔 + 刚建 1 笔 = 3 笔）
            res = await session.call_tool("weekly_order_summary", {})
            summary = json.loads(res.content[0].text)
            show("tools/call weekly_order_summary", summary)
            assert summary["recentCount"] >= 3, summary

            print("\nSDK 客户端全部断言通过")


anyio.run(main)
