<p align="center">
    <img src="src/main/resources/assets/stowling/icon.svg" alt="Stowling" width="96">
</p>

# Stowling

[English](README.md) | 简体中文

Stowling 是一个面向 Minecraft 26.2 的 Fabric 客户端模组，用于实现拾取、容器转移、
库存盘点和潜影盒打包自动化。它使用 Baritone 进行导航，可在普通客户端或 HeadlessMC
客户端中运行。

当前已实现的功能包括：

- 基于 Baritone 的导航和地面物品收集，容器操作与打包使用显式模块；兼容旧存储路由和溢出处理。
- 对箱子、木桶、漏斗和潜影盒菜单执行经服务端确认的物品转移。
- 盘点散装、盒装、未知物品以及潜影盒中的嵌套内容。
- 支持按物品数量或占用率阈值配置潜影盒打包工作单元。
- 使用 SQLite 持久化任务、恢复检查点、容器和库存快照。
- 坐标与范围页统一管理坐标点、球形/立方体半径和角点区域；任务只选择名称。
- 提供网页版模块化任务编排器，包含十个可复用机器人模块、持久化工作流、任务进度、
  优先级调度、取消控制以及可安全重启的任务记录。
- 使用 schema 6 保存带优先级、重启、触发器和中断设置的版本化拾取与自动化任务定义。
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

旧存储路由仍要求配置溢出容器，路由中未匹配的物品进入该容器而不是丢弃。
纯 `RUN_PICKUP` 步骤不会隐式分类或存放收集到的物品。

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

打开 `http://127.0.0.1:8765/tasks.html`，可以把以下十个机器人模块组合成可复用任务：

- `NAVIGATE`：通过 `coordinateId` 前往已保存的命名坐标。
- `WAIT`：等待 `seconds` 指定的秒数。
- `RUN_PICKUP`：通过 `coordinateId` 选择已保存范围，半径与几何形状在坐标库配置；
  支持 `idleSeconds` 与 `waitForItems`。
- `TAKE_ITEMS`：从 `coordinateId` 取出 `itemId`/`count`，可选填 `containerType`。
- `PUT_ITEMS`：向 `coordinateId` 放入 `itemId`/`count`，可选填 `containerType`。
- `TRANSFER_ITEMS`：按 `itemId`/`count` 在 `sourceCoordinateId` 与 `destinationCoordinateId`
  之间搬运，两端分别支持可选的容器类型。
- 检查实时玩家背包。
- 从最新且有效的新鲜容器库存中获取缺少原料。
- 执行制作步骤。
- `PACK_ITEMS`：使用显式工位坐标把物品打包到潜影盒。

`PACK_ITEMS` 已接入真实运行时，不是拾取的隐式副作用。该模块指定
`sourceCoordinateId`、`boxCoordinateId`、`outputCoordinateId`、`fallbackCoordinateId`，
以及 `itemId`、`count`、`completionTrigger`、`completionThreshold`，分别引用来源、
盒子供应、输出和兜底位置，并按物品数量或占用率阈值完成打包。

纯拾取不会隐式前往待机点、向缓存存放、分类或打包。需要移动、待机、容器存取、搬运或
打包时，应分别添加对应模块。任务页只选择已保存的区域和坐标，不提供共享缓存、待机点
或角点编辑。

当前版本的制作模块采用保守策略：普通客户端没有稳定、可等待服务端确认的制作事务，
因此该模块会安全终止并提示人工处理，不会伪造成功。导航、等待、拾取、背包检查、容器
转移和打包已经接入真实客户端运行时。

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

### 坐标与范围

打开 `http://127.0.0.1:8765/coordinates.html`，新增、删除、改名和排序命名坐标，
并编辑维度与 X/Y/Z。坐标 ID 创建后保持不变，显示名称可以修改。
在同一坐标编辑卡片内选择类型：

- **坐标点**（`POINT`）：单个 XYZ，供移动、容器等模块引用。
- **半径**：选择立方体（`CUBE`）或球（`SPHERE`），并填写 1–128 格半径。
  XYZ 是中心；立方体各轴范围为中心 ± 半径，球按到中心的距离判断。
- **区域**（`AREA`）：填写同维度的两个角点；顺序不限，执行时归整各轴最小/最大范围。
  第一角点仍保留为坐标锚点，不因第二角点反向而改变。

坐标配置保留原 `position`，新增 `type`、按类型适用的数字 `radius` 和 `secondCorner`。
坐标草稿独立保存；任务里的“运行拾取”只选择已保存半径范围或区域的名称，不显示
XYZ、维度，也不在任务里编辑半径、形状或区域。未保存的范围不能被任务选择。
在已经打开的任务页点击“刷新状态与资源”可重新读取另一个页面保存的配置，
不会清空未保存任务草稿；自动状态刷新不反复加载资源或重绘编辑器。
页面不恢复独立的拾取区域面板。

页面显示坐标名称、维度与 XYZ。坐标保存时同步更新已有引用。删除坐标时，
页面只列出实际关联的区域、旧缓存和旧拾取任务。确认后立即原子保存删除并保留
旧配置备份，刷新后不会恢复；其他未保存的坐标修改仍留在草稿中，不会一起提交。
取消不作更改；未保存的新点只从草稿移除。自定义任务的直接引用、间接资源引用或
中断步骤引用仍会阻止删除，不会顺带删除自定义任务。保存或删除失败会显示实际原因。

新安装不再生成示例坐标、区域、缓存、拾取流程、库存路由或盘点计划。已有配置在
启动时移除旧 `north-farm`、`north-cache` 和默认拾取流程关联链，写回配置并备份。
用户自己创建的资源保留；若自定义任务确实依赖这套旧资源，则保留关联链，避免
静默删除用户任务。历史示例仅保存在测试数据中。

页面不配置待机点或缓存容器；范围统一定义在坐标条目中。已有区域、任务、工作流
和缓存配置继续兼容读取和执行，不删除旧流程需要的区域，也不增加独立区域管理页面。

坐标和任务分别保存，不再使用跨任务与资源的“保存全部”。配置写入经过验证并
原子完成，旧配置保留为 `.bak` 备份；调度运行中或调度器不安全时禁止保存。

命名坐标使用以下接口：

```text
GET /v1/config/coordinate-points
PUT /v1/config/coordinate-points
```

坐标更新支持可选的 `removeLegacyReferencesFor` 数组，仅填写本次明确确认且实际删除
的已保存坐标 ID。不携带该字段的旧请求保留严格引用保护；版本冲突或仍有任务引用时，
整个保存被拒绝，不会先删除部分资源。

### 任务与兼容入口

任务页统一管理任务定义，可以按名称或类型查找，每项任务独立保存、删除和运行。
保存不会启用任务；任务参数包含优先级、触发条件、重启开关及中断流程。
拾取步骤只使用已保存的范围 `coordinateId`；半径和角点区域都在坐标库中配置。
任务页不再管理共享缓存、待机点、角点或旧仓库基础坐标；已有旧配置保持兼容。

旧拾取 API 继续兼容整个流程或其中一个区域，统一进入调度器，不绕过优先级。
以下接口是兼容入口，不是新模块化拾取编辑器：

```text
GET /v1/config/pickup
PUT /v1/config/pickup
GET /v1/pickup-workflows
POST /v1/tasks/pickup
```

启动整个旧流程时提交：

```json
{"workflowId":"farm_patrol"}
```

只运行旧流程内一个区域时提交：

```json
{"workflowId":"farm_patrol","regionId":"north_farm"}
```

旧工作流的区域顺序、分类时机和缓存策略通过兼容配置与 API 保留，不在新页面编辑。
更新模组 jar 后必须完全退出并重新启动客户端，新版本才会生效。

控制页面本身只能从回环地址访问，静态资源不需要令牌即可加载，但所有 `/v1/*` API
仍必须在每次请求中携带正确的 Bearer Token。

### 定时任务定义

配置 schema 6 在既有调度设置外增加命名坐标范围类型。现有 schema 1-5 配置会自动
迁移。迁移得到以及后续新保存的任务定义默认处于未启用状态，`autoStartOnRestart`
默认为 `false`。

每个任务定义包含一个优先级（`LOW`、`MEDIUM`、`HIGH`），编辑器提供以下四种触发器：

- `MANUAL`：仅在显式运行请求后启动。
- `FIXED_INTERVAL`：每隔一个正数 `intervalSeconds` 触发。
- `DAILY`：按配置时区中的一个或多个 `HH:mm` 时间触发。
- `AFTER_COMPLETION`：成功完成后等待正数 `intervalSeconds` 倒计时，再重新排队。

新“运行拾取”步骤通过 `coordinateId` 选择已保存半径范围或角点区域，选项和摘要只显示
名称。坐标、半径、形状和区域统一到“坐标与范围”页面配置。每次运行重新读取已保存
范围，修改会在下一轮生效；未保存的坐标草稿不会出现在任务选项中。

升级 schema 1-5 时，旧纯拾取步骤的 `coordinateId` + `radius` 或 `regionId` 会迁移为坐标库
中的范围，并更新步骤引用，不改变原拾取边界。任务 ID 与原坐标锚点保留；同一坐标被
不同半径任务使用时，额外范围使用独立命名资源，不会覆盖另一任务的范围。
旧工作流仍需要的区域保留。启动时原子写回升级配置，并保留旧配置备份。

支持无物品持续秒数 `idleSeconds`（默认 2）和 `waitForItems`（默认关闭）。
目标范围持续无物品达到设定秒数后，本轮结束。拾取只负责收集物品；后续移动、待机、
存放、搬运或打包必须显式添加对应模块。

可勾选“等待区域有物品再拾取”。等待时每秒检测一次所选球形或长方体区域内客户端
可见的掉落物，不巡逻或主动加载区块；不可观察时显示“等待区域可见”。等待属于正在
执行的任务，可安全中断。每轮有限执行，重复运行由父任务的触发器控制。

旧步骤、`workflowId`/`regionId` 工作流引用和缓存配置仍可读取、执行，新编辑器不提供
旧工作流或缓存编辑。只有点击明确的转换操作并确认后，旧步骤才改为纯 `RUN_PICKUP`
模式；打开或保存任务不会无声转换。转换后不会保留隐式待机移动、缓存存放、分类或
打包行为，需要这些动作时应另加模块。

旧配置的 `ITEMS_PRESENT` 任务触发器仍兼容，编辑时明确显示为旧配置，不会自动改写；
新任务在“运行拾取”步骤内配置有物品才开始的条件。

中断方式可以是 `NONE`、`INLINE` 步骤列表或 `TASK_REFERENCE`。任务引用只能指向
自动化任务；保存前会验证目标是否存在，并拒绝循环引用。

控制服务通过 `taskDefinitions`、`taskDefinition`、`createTaskDefinition`、
`updateTaskDefinition`、`deleteTaskDefinition`、`taskScheduling`、
`enableTaskDefinition`、`disableTaskDefinition` 和 `runTaskDefinition` 管理任务定义。
每个定义使用独立 revision；更新一个任务不会覆盖其他拾取或自动化任务。创建或保存
定义本身不会启用任务。

“坐标与区域”页通过既有资源接口独立保存区域：

```text
GET /v1/config/task-resources
PUT /v1/config/task-resources
```

`GET` 快照返回 `revision`、`pickupRegions`、`cacheContainers` 和 `pickupWorkflows`。
区域保存前先读取最新快照，`PUT` 只提交该 `revision`、编辑后的 `pickupRegions` 和
原样保留的旧 `cacheContainers`。页面不编辑缓存，不丢弃旧资源，也不替换工作流或
任务定义；版本冲突会明确提示，不会无声覆盖。

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
schema 6 的实现范围。

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
