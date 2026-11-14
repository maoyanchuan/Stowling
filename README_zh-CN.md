<p align="center">
    <img src="src/main/resources/assets/stowling/icon.svg" alt="Stowling" width="96">
</p>

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
  优先级调度、取消控制以及可安全重启的任务记录。
- 使用 schema 5 保存带优先级、重启、触发器和中断设置的版本化拾取与自动化任务定义。
- 提供带 Bearer 身份验证的回环 REST 控制 API，支持状态、任务、拾取、盘点、库存、
  容器、任务调度和配置重载操作。
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

控制台可以查看运行状态、任务、容器和库存快照，并执行拾取、取消任务、任务调度、
盘点及配置重载。页面每 5 秒自动刷新一次；令牌失效时会提示重新输入。

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
因此该模块会安全终止并提示人工处理，不会伪造成功。导航、等待、拾取、背包检查和容器
转移已经接入真实客户端运行时。

任务编排页面使用以下接口：

```text
GET /v1/automation-modules
GET /v1/task-definitions
POST /v1/task-definitions
GET /v1/task-definitions/{kind}/{id}
PUT /v1/task-definitions/{kind}/{id}
DELETE /v1/task-definitions/{kind}/{id}
POST /v1/task-definitions/{kind}/{id}/enable
POST /v1/task-definitions/{kind}/{id}/disable
POST /v1/task-definitions/{kind}/{id}/run
```

启动任务后会返回工作流 ID、当前步骤、步骤序号和总步骤数。任务检查点保存在 SQLite。
客户端重启后，旧的在途记录会标记为失败并丢弃检查点，不会重放旧点击。

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

### 任务与可复用资源

任务页统一管理拾取和自定义任务，可以按名称或类型查找，每项任务独立保存、删除和运行。
保存不会启用任务；任务参数包含优先级、触发条件、重启开关及中断流程。

任务页的资源管理区维护拾取区域、缓存容器和原仓库基础坐标。球形区域引用命名球心，
长方体区域引用两个角点；区域另设待机点。缓存容器通过命名坐标选择位置。
资源保存使用独立接口，不覆盖任务列表；仍被任务引用的区域、缓存或坐标不能删除。
单项拾取任务组合区域与缓存，保留原有区域顺序、分类时机和缓存策略；每轮有限执行。

旧拾取启动接口继续兼容整个流程或单个区域，统一进入调度器，不绕过优先级。

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

### 定时任务定义

配置 schema 5 会在每个拾取或自动化工作流旁保存调度设置。现有 schema 1-4 配置会自动
迁移。迁移得到以及后续新保存的任务定义默认处于未启用状态，`autoStartOnRestart`
默认为 `false`。

每个任务定义包含一个优先级（`LOW`、`MEDIUM`、`HIGH`），编辑器提供以下四种触发器：

- `MANUAL`：仅在显式运行请求后启动。
- `FIXED_INTERVAL`：每隔一个正数 `intervalSeconds` 触发。
- `DAILY`：按配置时区中的一个或多个 `HH:mm` 时间触发。
- `AFTER_COMPLETION`：成功完成后等待正数 `intervalSeconds` 倒计时，再重新排队。

新“运行拾取”步骤直接配置自定义规则，不再选择拾取流程：从命名坐标中选择待机点、
角点一、角点二，并选择一个或多个缓存容器。两个角点按各轴的最小值与最大值确定长方体，
包含边界，角点顺序不限；所选坐标与缓存必须位于同一维度。无物品持续秒数
（`idleSeconds`，默认 2）控制本轮结束，分类时机独立配置；存放沿用现有分类路由与溢出容器。

可勾选“等待区域有物品再拾取”（`waitForItems`，默认关闭）。等待时每秒检测一次角点范围
内客户端可见的掉落物，不巡逻或主动加载区块；不可观察时显示“等待区域可见”。
等待属于正在执行的任务，可安全中断。每轮有限执行，重复运行由父任务的触发器控制。

旧 `workflowId`/`regionId` 步骤仍可读取、执行，新编辑器不再提供拾取流程下拉。
点击明确的转换按钮后改为独立规则，不会无声改写已有任务。

旧配置的 `ITEMS_PRESENT` 任务触发器仍兼容，编辑时明确显示为旧配置，不会自动改写；
新任务在“运行拾取”步骤内配置有物品才开始的条件。

中断方式可以是 `NONE`、`INLINE` 步骤列表或 `TASK_REFERENCE`。任务引用只能指向
自动化任务；保存前会验证目标是否存在，并拒绝循环引用。

控制服务通过 `taskDefinitions`、`taskDefinition`、`createTaskDefinition`、
`updateTaskDefinition`、`deleteTaskDefinition`、`taskScheduling`、
`enableTaskDefinition`、`disableTaskDefinition` 和 `runTaskDefinition` 管理任务定义。
每个定义使用独立 revision；更新一个任务不会覆盖其他拾取或自动化任务。创建或保存
定义本身不会启用任务。

任务编辑界面可以通过以下接口独立维护可复用的拾取区域和缓存容器，而不会替换工作流：

```text
GET /v1/config/task-resources
PUT /v1/config/task-resources
```

`PUT` 请求体只包含 `revision`、`pickupRegions` 和 `cacheContainers`，不包含也不修改
`pickupWorkflows`。现有拾取与自动化任务定义都会保留。

调度只在 `tickOnClientThread` 中推进。原 `requestPickup` 和 `requestAutomation`
请求会进入同一优先级队列，不能绕过排队中的更高优先级任务。高优先级任务抢占时，
调度器先调用 `requestStop()`，再等待 `pollStop()` 返回 `CLEAN`；返回 `PENDING`
时当前任务继续持有控制权，返回 `UNSAFE` 时停止继续派发。定时任务支持取消运行。
全局暂停/恢复已移除；如需阻止后续调度，请禁用任务定义，需要重新开始时再次运行任务。
被抢占的任务使用新的执行身份从第一步重跑，不恢复流程断点，也无需重新满足原触发条件。
中断流程在确认安全退出后执行，执行期间不再抢占。无法确认安全退出时显示“异常待处理”。

从配置存储读取或刷新任务定义是只读操作，不会自动启用任务、改变运行历史或修改文件。
`recoverPendingPickups` 会把上一个进程遗留的可恢复记录标记为失败并丢弃其检查点，
不会重新执行。跨实例任务仓库同步、后台任务导入和任务运行镜像明确延期，不属于
schema 5 的实现范围。

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
