# factorio-mcp

[English](README.md) | [简体中文](README-zh-cn.md)

让 AI agent 读取世界数据、发送有限时长的输入序列，并查看和操作正在运行的 Factorio 客户端 UI，包括菜单和模组窗口。
**无需安装模组，不依赖 RCON，也不需要游戏内管理员权限。** 通用接口在理论上可以支持任意模组，也可以用于加入服务器进行多人游戏的客户端。

**为了操作客户端，factorio-mcp 必须向 Factorio 进程注入代码。这在某些场景下可能被视为作弊。如果你对此有顾虑，请不要使用
factorio-mcp。**

目前支持 **Windows x64**，提供 stdio 和 Streamable HTTP 两种传输方式。

![电脑自己在动](images/电脑自己在动.png)

已在 Factorio 2.0.77（包含官方 DLC）中测试。

## 安装

可以选择下载可执行文件，或通过 npx 使用。以下两种方式均使用 **stdio**。
将 JSON 添加到 MCP 客户端的配置中，格式可按客户端要求调整。

### 从 GitHub Releases 下载

从 [GitHub 最新 Release](https://github.com/czp3009/factorio-mcp/releases/latest) 下载对应操作系统和架构的 ZIP，
将整个压缩包解压到你选择的目录。保持可执行文件及其附带的动态链接库在同一目录。

在 MCP 客户端中添加以下配置，将 `command` 替换为上述解压目录中可执行文件的绝对路径，包含实际文件名：

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "command": "/absolute/path/to/factorio-mcp/<executable>",
      "args": [
        "--no-http"
      ]
    }
  }
}
```

### 通过 npx 使用

安装包含 npm 的 Node.js 24+，然后改用以下配置：

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "command": "npx",
      "args": [
        "--yes",
        "@czp3009/factorio-mcp@latest",
        "--no-http"
      ]
    }
  }
}
```

### Streamable HTTP

先手动启动 factorio-mcp。可以运行从 GitHub Releases 下载并解压的可执行文件：

```text
"/absolute/path/to/factorio-mcp/<executable>" --no-stdio --http-port 3000
```

也可以通过 npx 启动：

```text
npx --yes @czp3009/factorio-mcp@latest --no-stdio --http-port 3000
```

请将可执行文件路径替换为实际路径。如果使用 PowerShell，需在带引号的可执行文件路径前加 `&`。
两种启动方式均关闭 stdio。保持服务运行，再通过以下配置让 agent 连接：

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "type": "http",
      "url": "http://127.0.0.1:3000/mcp"
    }
  }
}
```

HTTP 仅监听本机地址。如果修改端口，请同步修改启动命令和连接 URL。

## 使用要求

使用前请启动 Factorio，并等待初始加载完成。

更新 factorio-mcp 或移动其文件后， **必须先重启 Factorio，再使用 MCP**。

从源码构建需要兼容的 JDK、CMake、Ninja，以及平台对应的 C++ 构建工具和 SDK。
详见[开发文档](https://github.com/czp3009/factorio-mcp/blob/master/development.md#build)（英文）。

## 工具

| 工具             | 用途                                                   |
|------------------|--------------------------------------------------------|
| `status`         | 查看连接状态、游戏状态及可用的蓝图导入传输进度。       |
| `attach`         | 通过 PID 或进程名连接已有客户端。                      |
| `detach`         | 断开连接并取消待处理的工具调用，不关闭游戏。           |
| `ui_read`        | 读取 UI 结构、文本、标志及支持的控件属性值。           |
| `ui_action`      | 点击控件、替换文本或按键。                             |
| `screenshot`     | 获取包含游戏世界和 UI 的 PNG 截图。                    |
| `input_bindings` | 发现原版及模组的控制操作与当前绑定。                   |
| `input`          | 执行有限时长的键鼠组合，包括按住鼠标并移动。           |
| `world_query`    | 查询世界对象及相关属性、玩家、物品栏和原型目录。       |
| `world_overview` | 按网格或筛选后的实体概览指定区域，可选择需要的详情。   |
| `chat_read`      | 读取本地保留的聊天消息和通知，并使用读取游标增量查询。 |
| `chat_send`      | 以本地玩家身份发送纯文本聊天消息。                     |

参数、示例、返回值含义及取消规则见 [tools.md](tools.md)（英文）。

## 实际限制

- 连接到同一个 MCP 进程的客户端共享游戏连接；`detach` 会取消这些客户端尚未结束的工具调用。
  关闭 HTTP 会话不会解除与游戏的连接。UI 操作可触发正常菜单功能，包括退出游戏。
- 操作完成表示输入已发出，不代表游戏目标已经实现。执行依赖其结果的后续操作前，应先观察实际状态，多人游戏中尤其如此。
- UI 和世界查询有读取上限，会报告数据不完整的情况。UI 属性仅支持部分类型，自行绘制的像素不会被还原为结构化控件。
  世界查询可能包含隐藏对象，不会重建地图中记忆的历史内容；可见性标志不考虑 UI 遮挡。
- `input` 需要世界正在运行；菜单和暂停时的 UI 请使用 `ui_action`。目前不支持直接控件拖动／滚动或手柄输入。
  已存在但不在可视区域内的选项可直接选取；世界运行时，可用鼠标输入作为拖动的后备方案。滚轮在 UI 上的响应并非在所有已测状态下都可靠。
- 截图需要 DirectX，不包含桌面或 Steam 叠加层。游戏最小化或暂停渲染时，截图可能一直等待并延迟其他工具调用，必要时请取消。
  频繁截图可能降低游戏性能。
- 工具本身没有执行超时。支持显式 MCP 取消请求，但不保证在 HTTP socket 断开时自动取消。
- 部分状态或属性可能无法读取。游戏更新后，可能需要同步更新 MCP；已测试的接口不代表覆盖所有模组或自定义渲染内容。
- 聊天查询只覆盖本地客户端保留的消息，不是完整的服务器日志。对象检查只开放支持的可读属性和只读方法，
  不提供任意 Lua 执行，也不保证能访问所有模组私有状态。
