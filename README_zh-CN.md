# Stowling

[English](README.md) | 简体中文

Stowling 是一个面向 Minecraft 26.2 的 Fabric 客户端模组，用于实现拾取、容器转移、
库存盘点和潜影盒打包自动化。它使用 Baritone 进行导航，可在普通客户端或 HeadlessMC
客户端中运行。

当前已实现的功能包括：

- 基于 Baritone 的拾取导航、地面物品收集、存储路由和溢出处理。
- 对箱子、木桶、漏斗和潜影盒菜单执行经服务端确认的物品转移。
- 盘点散装、盒装、未知物品以及潜影盒中的嵌套内容。
- 支持按物品数量或占用率阈值配置潜影盒打包工作单元。
- 使用 SQLite 持久化任务、恢复检查点、容器和库存快照。
- 支持独立的命名坐标库，以及引用这些坐标的球形/长方体拾取区域、缓存容器和
  多区域工作流。
- 提供网页版模块化任务编排器，包含九个可复用机器人模块、持久化工作流、任务进度、
  暂停/恢复/取消控制以及重启检查点。
- 提供带 Bearer 身份验证的回环 REST 控制 API，支持状态、任务、拾取、盘点、库存、
  容器、暂停、恢复和配置重载操作。
- 提供客户端内 HeadlessMC 控制命令，以及 Windows 启动、状态、停止、重试和日志轮换
  脚本。

## 工具链

- Java 25
- 通过仓库内置 wrapper 使用 Gradle 9.8.2
- Minecraft 26.2
- Fabric Loader 0.20.1
- Fabric API 0.161.0+26.2
- Loom 1.19.4
- Baritone API for Fabric 1.19.0

官方 Fabric API 和 Baritone 版本固定在 `gradle.properties` 与 `libs/README.md` 中。
Baritone jar 仅用于编译，不会嵌入 Stowling 的 jar。

## 开发

运行 Java 测试和风格检查：

```powershell
.\gradlew.bat test checkstyleMain checkstyleTest
```

运行统一验收入口：

```powershell
pytest
```

构建重映射后的模组：

```powershell
.\gradlew.bat build
```

输出 jar 位于 `build/libs/`。

## 配置

运行时配置路径为 `config/stowling.json`。首次启动时，如果该文件不存在，Stowling 会
写入内置的 `src/main/resources/stowling.example.json` 配置。持续时间以整数秒序列化。
验证会拒绝重复 ID、无效的容器或维度引用、无效的打包阈值，以及优先级相同的物品路由
冲突。

溢出容器为必填项。未匹配的物品必须路由到该容器，而不能丢弃。

## Web 控制台

Stowling 启动后会随本地 REST 服务提供中文 Web 控制台。在运行游戏的同一台电脑上打开：

```text
http://127.0.0.1:8765/
```

首次打开页面时，将游戏实例中 `config/stowling-api.token` 的内容粘贴到页面并点击
“验证并连接”。令牌只保存在当前标签页的 `sessionStorage` 中，不会写入 URL、Cookie、
项目配置或日志。页面的“清除令牌”按钮可立即移除当前会话中的令牌。

控制台可以查看运行状态、任务、容器和库存快照，并执行拾取、取消任务、暂停、恢复、
盘点及配置重载。页面每 10 秒自动刷新一次；令牌失效时会提示重新输入。

### 任务编排

打开 `http://127.0.0.1:8765/tasks.html`，可以把以下机器人能力组合成可复用任务：

- 前往命名坐标。
- 等待指定秒数。
- 运行完整拾取流程或其中一个拾取区域。
- 从容器取物到玩家背包。
- 把玩家背包中的物品放入容器。
- 在两个容器之间搬运物品。
- 检查实时玩家背包。
- 从最新且有效的新鲜容器库存中获取缺少原料。
- 执行制作步骤。

当前版本的制作模块采用保守策略：普通客户端没有稳定、可等待服务端确认的制作事务，
因此该模块会暂停并提示人工恢复，不会伪造成功。导航、等待、拾取、背包检查和容器
转移已经接入真实客户端运行时。

任务编排页面使用以下接口：

```text
GET /v1/automation-modules
GET /v1/config/automation-workflows
PUT /v1/config/automation-workflows
POST /v1/tasks/automation
```

启动任务后会返回工作流 ID、当前步骤、步骤序号和总步骤数。任务检查点保存在 SQLite。
如果客户端重启后找不到正在进行的容器操作，任务会暂停并要求人工核对，不会重放旧点击。

### 命名坐标库

打开“命名坐标”页面后，可以新增、删除、改名和排序命名坐标，并直接修改维度与
X/Y/Z。坐标 ID 创建后保持不变，显示名称可以自由修改。所有修改先保留为草稿，
点击“保存全部”后原子写入配置并生成 `.bak` 备份。

拾取区域、长方体角点和缓存容器通过坐标选择器引用命名坐标，不再重复编辑实际
坐标值。修改命名坐标后，引用它的待机点、区域形状和缓存位置会在保存时同步更新。
删除仍被区域或缓存引用的坐标会被拒绝，并列出引用者；跨维度引用同样无法保存。

对应接口为：

```text
GET /v1/config/coordinate-points
PUT /v1/config/coordinate-points
```

### 拾取配置

打开“拾取配置”页面后，可以直接编辑并永久保存以下资源：

- **区域**：新增、删除、改名和排序拾取区域。球形区域选择命名坐标作为球心并设置
  半径；长方体区域选择两个包含边界的角点，并单独选择机器人待机点。
- **缓存**：新增、删除或改名缓存容器，设置维度，并通过命名坐标选择容器位置。
  一个流程可以选择多个缓存，运行时会按流程策略选择可用缓存。
- **流程**：组合一个或多个区域和缓存，设置区域优先级、空闲时间、分类时机和结束方式。

页面内的修改先保留为草稿。确认无误后点击“保存全部”，配置会原子写入
`config/stowling.json`，旧配置自动备份为 `config/stowling.json.bak`。如果存在活动或
暂停的任务，页面会拒绝保存；请先取消或结束任务。删除仍被流程引用的区域或缓存也会
被拒绝，并显示引用它的流程。

启动任务时先选择流程，然后选择“运行整个流程”或流程内的一个区域。运行整个流程会按
优先级巡逻全部区域；选择单个区域时仍会使用所属流程的缓存、分类和结束策略。

对应接口为：

```text
GET /v1/config/pickup
PUT /v1/config/pickup
GET /v1/pickup-workflows
POST /v1/tasks/pickup
```

启动整个流程时提交：

```json
{"workflowId":"farm_patrol"}
```

只运行流程内一个区域时提交：

```json
{"workflowId":"farm_patrol","regionId":"north_farm"}
```

更新模组 jar 后必须完全退出并重新启动客户端，新版本才会生效。

原“坐标配置”面板继续管理仓库、溢出箱和装盒工位坐标；拾取区域与缓存改由新的
“命名坐标”和“拾取配置”页面管理。

控制页面本身只能从回环地址访问，静态资源不需要令牌即可加载，但所有 `/v1/*` API
仍必须在每次请求中携带正确的 Bearer Token。

## HeadlessMC 操作

可以在不启动 HeadlessMC 的情况下检查 Windows 管理脚本：

```powershell
.\scripts\Start-StowlingHeadless.ps1 -DryRun
.\scripts\Start-StowlingHeadless.ps1 -Status
.\scripts\Stop-StowlingHeadless.ps1
```

自动化测试不会启动 HeadlessMC，也不会连接 Minecraft 服务。使用真实账号和真实服务器
进行冒烟测试仍需要外部安装 HeadlessMC、准备兼容的运行时模组、提供账号凭据，并拥有
目标服务器的访问权限。本仓库不提供这些凭据或 HeadlessMC 安装。

## 安全边界

身份验证令牌、Microsoft 账号数据、聊天内容和其他个人身份信息绝不能记录到日志中，也
不能存储在模组配置里。REST 控制监听器绑定到 `127.0.0.1`，并使用存储在
`config/stowling-api.token` 中的 Bearer 令牌。

## 质量规则

- 使用 4 个空格缩进
- 每行最多 100 列
- 所有方法都使用显式类型
- Java 行为使用 JUnit 测试，pytest 作为顶层验收命令
- 使用 Conventional Commits，提交正文说明改动原因
