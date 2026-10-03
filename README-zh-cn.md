# factorio-mcp

[English](README.md) | [简体中文](README-zh-cn.md)

factorio-mcp 是一个 MCP 服务, 让 AI agent 读取 Factorio 世界数据, 执行有限时长的键鼠操作,
并查看和操作游戏界面, 包括菜单和模组窗口. **无需安装模组, 不依赖 RCON, 也不需要游戏内管理员权限.**
它提供通用接口, 理论上可支持任意模组, 也可用于已连接多人服务器的游戏客户端.

**factorio-mcp 通过向 Factorio 进程注入代码来操作客户端. 这种方式在某些场景下可能被视为作弊.
如果你对此有顾虑, 请不要使用 factorio-mcp.**

支持 **Linux x64 和 Windows x64**, 提供 stdio 和 Streamable HTTP 两种传输方式.

![电脑自己在动](images/电脑自己在动.jpg)

已在 Factorio 2.0.77 (包含官方 DLC) 中测试.

## 安装

先选择 **stdio** 或 **Streamable HTTP** 连接方式, 再按下方说明配置.
两种方式均可使用 GitHub Releases 中的下载包, 或通过 npm (npx) 运行.
不同 MCP 客户端的配置格式可能有所不同, 请按客户端要求调整下方的 JSON 示例.

### stdio

从以下两种方式中任选一种, 将对应配置添加到 MCP 客户端.

#### 从 GitHub Releases 下载

从 [GitHub 最新 Release](https://github.com/czp3009/factorio-mcp/releases/latest) 下载与你的操作系统和架构对应的 ZIP 文件,
完整解压到所选目录. 可执行文件和附带的动态链接库需要放在同一目录中.

在 MCP 客户端中添加以下配置, 将 `command` 改为解压后可执行文件的完整绝对路径:

```json
{
  "mcpServers": {
    "factorio-mcp": {
      "command": "/absolute/path/to/factorio-mcp/factorio-mcp",
      "args": [
        "--no-http"
      ]
    }
  }
}
```

Windows 下请填写 `.exe` 文件的路径, 例如 `"command": "C:/path/to/factorio-mcp/factorio-mcp.exe"`.
在 JSON 中使用正斜杠, 可以省去反斜杠的转义.

#### 通过 npm (npx) 使用

安装 Node.js 24 或更高版本, 确保包含 npm, 然后使用以下配置:

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

Windows 下, 如果 MCP 客户端需要通过命令行解释器运行 npx, 请将 `command` 设为 `"cmd"`, 并使用以下参数配置:
`"args": ["/c", "npx", "--yes", "@czp3009/factorio-mcp@latest", "--no-http"]`.

### Streamable HTTP

先通过以下任一方式启动 HTTP 服务, 再配置 MCP 客户端连接到该服务.

#### 从 GitHub Releases 下载

从 [GitHub 最新 Release](https://github.com/czp3009/factorio-mcp/releases/latest) 下载与你的操作系统和架构对应的 ZIP 文件,
完整解压到所选目录. 可执行文件和附带的动态链接库需要放在同一目录中.

运行以下命令, 将示例路径改为解压后可执行文件的完整绝对路径:

```bash
"/absolute/path/to/factorio-mcp/factorio-mcp" --no-stdio --http-port 3000
```

Windows 下使用 PowerShell 时, 请填写 `.exe` 文件的路径, 并在路径前加上 `&`:

```powershell
& "C:\path\to\factorio-mcp\factorio-mcp.exe" --no-stdio --http-port 3000
```

#### 通过 npm (npx) 使用

安装 Node.js 24 或更高版本, 确保包含 npm, 然后运行:

```text
npx --yes @czp3009/factorio-mcp@latest --no-stdio --http-port 3000
```

#### 配置 MCP 客户端

在 MCP 客户端中添加以下配置, 并在使用期间保持 HTTP 服务运行:

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

HTTP 服务仅监听本机地址. 如需更换端口, 请同时修改启动命令和配置中的 URL.

## 使用要求

使用前请启动 Factorio, 等待游戏完成启动加载.

Linux 上需要使用同一用户运行 MCP 和 Factorio, 并允许进程调试. 如果 Yama 阻止 MCP 连接到由 Steam 启动的游戏客户端,
可运行 `sudo sysctl kernel.yama.ptrace_scope=0` 允许同一用户的进程连接. 此设置会在系统重启后失效,
但会改变整个系统的 ptrace 策略, 使用完毕后请恢复原值. 参见 [Linux 内核的 Yama 文档](https://docs.kernel.org/admin-guide/LSM/Yama.html).

请保留游戏的调试信息. Linux 上应使用未剥离调试信息的原始可执行文件;
Windows 上应将与 `factorio.exe` 匹配的 `factorio.pdb` 保留在同一目录中.

更新 factorio-mcp 或移动其文件后, **必须先重启 Factorio, 再使用 MCP**.

如果需要从源码构建, 请准备兼容的 JDK, CMake, Ninja, 以及对应平台的 C++ 构建工具和 SDK.
详见 [开发文档](https://github.com/czp3009/factorio-mcp/blob/master/development.md#build) (英文).

## 工具

| 工具 | 用途 |
|------|------|
| `status` | 查看连接状态, 游戏状态和暂停状态. |
| `attach` | 通过 PID 或进程名连接到正在运行的游戏客户端. |
| `detach` | 断开连接并取消尚未完成的工具调用, 不关闭游戏. |
| `ui_read` | 读取 UI 结构, 文本, 标志和受支持的控件属性值. |
| `ui_action` | 点击控件, 替换文本或向控件发送按键. |
| `screenshot` | 获取包含游戏画面和 UI 的 PNG 截图. |
| `input_bindings` | 查询游戏本体及模组的 control ID 对应的当前键鼠绑定. |
| `input` | 执行有限时长的键鼠组合操作, 包括按住鼠标按钮时移动鼠标. |
| `world_query` | 查询世界对象及其相关属性, 以及玩家, 物品栏和原型目录. |
| `world_overview` | 按游戏原有属性汇总区域内的数据, 或筛选实体并读取指定的详细信息. |
| `chat_read` | 读取本地保留的聊天消息和通知, 支持通过 offset 增量查询或等待新消息. |
| `chat_send` | 以本地玩家身份发送纯文本聊天消息. |

各工具的参数, 示例, 返回值含义和取消规则详见 [tools.md](tools.md) (英文).

## 实际限制

- 使用工具时, 请避免同时手动操作游戏, 否则可能导致错误或意料之外的结果.
- 连接到同一个 MCP 进程的客户端共享与游戏的连接. `detach` 会取消这些客户端尚未完成的工具调用,
  但关闭 HTTP 会话不会断开游戏连接. UI 操作可以触发游戏菜单中的正常功能, 包括退出游戏.
- 工具报告操作完成, 只表示客户端已执行操作, 不保证服务器已接收或达到了预期的游戏效果. 聊天消息也只确认已在本地提交.
  网络延迟和客户端预测回退可能使效果延迟出现或被撤销. 如果后续操作依赖此次结果, 请先观察实际状态.
- UI 和世界查询都有读取上限, 数据不完整时会明确报告. 目前只支持读取部分 UI 属性, 自行绘制的画面不会被还原为结构化控件.
  世界查询可能包含隐藏对象, 不会还原地图上保留的历史信息; 可见性标志也不考虑 UI 遮挡.
- UI 中的图片以结构化引用表示, 不包含图片内容或渲染层信息. 脚本 GUI 控件的原始图标名称可通过 `world_query` 的对象检查功能读取.
- `input` 需要游戏世界处于运行状态. 操作菜单或暂停时的 UI, 请使用 `ui_action`. 目前不支持直接拖动或滚动控件, 也不支持手柄输入.
  已存在但位于可视区域之外的选项仍可直接选取. 世界运行时, 也可通过鼠标输入完成拖动. 在部分已测试的状态下, UI 对滚轮输入的响应不可靠.
- 截图需要 Linux 上的 OpenGL 或 Windows 上的 DirectX, 不包含桌面或 Steam 叠加层. 游戏最小化或停止渲染时,
  截图可能持续等待, 并延迟其他工具调用. 同一时间只能执行一个截图操作, 可通过 `screenshot` 的 `action:"cancel"` 取消.
  频繁截图可能降低游戏性能.
- 工具没有执行超时限制. 可以通过显式 MCP 取消请求停止操作, 但 HTTP 连接断开时不保证自动取消.
- 部分状态或属性可能无法读取. 游戏更新后, MCP 也可能需要更新. 现有测试不代表已覆盖所有模组或自定义渲染内容.
- 聊天查询只能读取本地客户端保留的消息, 无法提供完整的服务器日志. 对象检查仅提供受支持的可读属性和只读方法,
  不支持执行任意 Lua 代码, 也不保证能读取所有模组的私有状态.
