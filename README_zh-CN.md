# Stowling

Stowling 是面向 Minecraft 26.2 的 Fabric 客户端模组，提供命名坐标、区域、分类区、
优先级任务队列和条件监听器。移动与容器交互复用现有游戏接口和 Baritone 集成。

任务管理页采用两栏布局。左侧窄栏上半部统一显示运行、排队和监听中的任务，
按紧急、普通、后台优先级排列；下半部显示本页会话中的任务状态变化日志，
最多保留 300 条，支持导出、清空和暂停滚动。暂停滚动不会暂停任务，重新加载
页面会开始新的日志会话。运行详情与中断收尾恢复操作通过弹窗查看。
右侧上方是只显示标题的空白任务展示区，预留给后续展示；下方提供文件夹管理、
分类筛选、已有任务选择和运行入口，不自动选中任何任务。任务选择页不再加载
任务编辑器、模块参考、新建/模板入口、触发器编辑或旧 `#triggers` 展开逻辑。
后端任务、触发器、文件夹数据以及可复用编辑器源码仍保留。
库存查询导航及网页路由暂时移除，库存 API、数据和页面源文件保留，方便以后恢复。

## 任务模型

任务包含名称、可选的 `folderId` 和有序树形步骤。文件夹 ID 指向实际持久化的
任务文件夹；`null` 表示未分组。每个步骤使用一种类型：

- `ACTION`：执行一个基础模块。
- `IF`：判断条件，执行 `steps` 或 `otherwise`。
- `REPEAT`：条件成立时重复 `steps`，直到条件不成立或达到 `maxIterations`。
  默认上限为 `1024`。
- `CREATE_TASK`：向共享优先级队列创建一个独立任务，不嵌套执行。

支持的条件：

```text
ALWAYS
FOOD_BELOW_THRESHOLD
HAS_CONTAINERS
HAS_CARRIED_ITEMS
HAS_MANAGED_ITEMS
CACHE_HAS_MORE
HAS_MARKER_CONTAINERS
```

`FOOD_BELOW_THRESHOLD` 使用 `foodLevelThreshold`，范围为 `0` 到 `20`。
`IF` 和 `REPEAT` 可使用 `negate=true`。`HAS_MANAGED_ITEMS` 可用
`itemSource=collected/carried/managed` 限定账本，省略时仍检查全部受管物品。

## 基础模块

常用入口为 `CONTAINER_INTERACTION`（容器交互）和独立移动模块。
容器交互按 `operation` 选择 `COLLECT`、`FIND`、`READ`、`WITHDRAW`、`DEPOSIT`。
保存时保留紧凑步骤，排队时统一编译为已有基础动作和循环，并冻结地址与检测器配置；
不新增包含整套复杂流程的执行器。`GET /v1/task-modules` 同时保留基础动作与三个监听器，供旧任务继续使用。
任务选择页只读取目录来翻译运行错误名称，不展示模块参考；可复用编辑器源码
仍保留在仓库中。

动作模块：

```text
CONTAINER_INTERACTION
MOVE_TO_COORDINATE
COLLECT_DROPPED_ITEMS
SCAN_CONTAINERS_IN_REGION
RESOLVE_CONTAINER_CATEGORIES
SELECT_CONTAINER
WITHDRAW_ITEMS
SELECT_STORAGE_TARGET
DEPOSIT_ITEMS_TO_CONTAINERS
WITHDRAW_FOOD
EAT_FOOD
SCAN_CATEGORY_MARKERS
SELECT_MARKER_CONTAINER
READ_CONTAINER
SAVE_CATEGORY_MARKER_CACHE
ASSERT_NO_MANAGED_ITEMS
```

监听器：

```text
DROPPED_ITEMS
FOOD_LEVEL_AT_OR_BELOW
CACHE_UPDATED
```

`WITHDRAW_ITEMS` 当前只支持 `itemSource=classified`。
`DEPOSIT_ITEMS_TO_CONTAINERS` 支持 `collected`、`carried` 和 `managed`。
目录和校验器不会暴露 runtime 无法实际执行的参数选项。

## 统一引用

所有任务引用使用统一编码：

```text
coordinate:<uuid>
region:<uuid>
category-zone:<uuid>
task:<uuid>
output:container
output:storage
output:marker
```

参数键统一为：

- `targetRef`：移动、取出、存入和食物箱交互。
- `areaRef`：坐标半径或区域收集。
- `regionRef`：区域扫描。
- `categoryZoneRef`：分类解析和标记刷新。
- `taskRef`：创建任务及中断任务引用。

加载和保存时，会在语义明确的情况下兼容转换 `coordinateId`、`regionId`、
`cacheRegionId`、`categoryZoneId`、`area=...` 和 `previous-scan` 等旧格式。
已有 schema 7 坐标、区域、分类区和触发器会保留，不会因兼容转换被清空。

## 中断流程

任务可配置以下字段：

```json
{
  "interruptionMode": "DEFAULT",
  "interruptionTaskRef": null,
  "defaultInterruptionFlow": false,
  "requeueAfterInterruption": false
}
```

模式：

- `DEFAULT`：使用标记为 `defaultInterruptionFlow=true` 的共享收尾任务。
- `CUSTOM`：使用 `interruptionTaskRef=task:<uuid>`。
- `NONE`：不执行收尾。

最多只能有一个共享默认收尾任务。该任务必须使用 `NONE`，不能后台重复，
也不能包含 `CREATE_TASK`。

中断收尾占用原运行槽位并共享原运行工作区，不进入普通队列。已经移动的物品会
保留托管记录，收尾失败后可以重试，不会因进程拆分丢失归属。处于
`INTERRUPTION_FAILED` 的运行可通过以下接口恢复：

```text
POST /v1/task-runs/{runId}/retry-interruption
POST /v1/task-runs/{runId}/skip-interruption
```

两个接口沿用控制 API 的 Bearer 认证。运行状态不匹配时返回
`409 invalid_state`。

## 内置流程模板

自动进食：

```text
IF FOOD_BELOW_THRESHOLD
    MOVE targetRef=coordinate
    REPEAT FOOD_BELOW_THRESHOLD
        WITHDRAW_FOOD
        EAT_FOOD
```

拾取并存入任务定义：

1. 添加掉落物检测器，选择检测坐标、检测间隔和创建任务的优先级。
2. 添加容器交互「拾取」，范围选择「沿用掉落物检测器的范围」。
3. 添加容器交互「存入」，选择已保存的地址，物品来源选择「本次拾取的物品」。

检测器只创建排队任务，轮到任务时才拾取。沿用检测器要求同任务只有一个掉落物检测器，
默认继承其物品过滤；未绑定检测器的已存任务可以保留，但不能运行。
单点只处理该点的容器；区域逐箱尝试；分类区按物品匹配容器（标记规则仍需已有缓存）。
箱满或没有匹配容器而留下物品时任务失败，不会存走无关的背包物品。

```text
CONTAINER_INTERACTION operation=COLLECT areaRef=detector
CONTAINER_INTERACTION operation=DEPOSIT targetRef=<已保存地址> itemSource=collected
```

区域存入编译后复用的基础动作：

```text
COLLECT_DROPPED_ITEMS
SCAN_CONTAINERS_IN_REGION
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER
    IF HAS_MANAGED_ITEMS itemSource=collected
        MOVE output:container
        DEPOSIT_ITEMS_TO_CONTAINERS itemSource=collected
ASSERT_NO_MANAGED_ITEMS itemSource=collected
```

分类入库：

```text
SCAN_CONTAINERS_IN_REGION regionRef=<cache>
RESOLVE_CONTAINER_CATEGORIES
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER resetCacheHasMore=true
    REPEAT CACHE_HAS_MORE
        MOVE output:container
        WITHDRAW_ITEMS itemSource=classified
        REPEAT HAS_CARRIED_ITEMS
            SELECT_STORAGE_TARGET
            MOVE output:storage
            DEPOSIT_ITEMS_TO_CONTAINERS itemSource=carried
ASSERT_NO_MANAGED_ITEMS
```

上一轮存入后玩家位于储存容器，因此每次 `CACHE_HAS_MORE` 循环都必须先重新
移动到缓存容器，再执行取出。

分类标记刷新：

```text
SCAN_CATEGORY_MARKERS
REPEAT HAS_MARKER_CONTAINERS
    SELECT_MARKER_CONTAINER
    MOVE output:marker
    READ_CONTAINER
SAVE_CATEGORY_MARKER_CACHE
```

默认收尾模板：

```text
SCAN_CONTAINERS_IN_REGION
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER
    MOVE output:container
    DEPOSIT_ITEMS_TO_CONTAINERS itemSource=managed
ASSERT_NO_MANAGED_ITEMS
```

## 旧配置迁移

已有 schema 7 配置继续有效。以下旧模块会在加载时展开为树形基础步骤：

- `AUTO_EAT_AT_COORDINATE`
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE`
- `SORT_CACHE_ITEMS`
- `REFRESH_CATEGORY_MARKER_CACHE`
- `COLLECT_DROPPED_ITEMS_IN_REGION`
- 无参数或依赖前置扫描的 `DEPOSIT_ITEMS_TO_CONTAINERS`

旧 `SORT_CACHE_ITEMS` 没有显式分类区时，会先执行
`MATCH_CONTAINER_CATEGORIES`，保留原有前置扫描语义。Stowling 不会因为配置中
只有一个分类区就推断该分类区是原任务目的地。

迁移后的分类任务会获得一个确定性的自定义收尾任务，用于把托管物品
退回缓存。再次读取配置不会把新的基础分类任务从 `DEFAULT` 改成 `CUSTOM`，
也不会重复生成收尾任务。

`WAIT_FOR_DROPPED_ITEMS` 和 `MATCH_CONTAINER_CATEGORIES` 仅保留用于旧配置
兼容和前置扫描迁移。

## 任务文件夹

任务文件夹是实际持久化的配置对象，使用平面单层结构。每个任务最多属于一个
文件夹；配置/API 写入会保持任务与文件夹分配一起保存。任务选择页提供文件夹
创建、重命名和解散操作。删除文件夹只解散分组，其中的任务仍会保留，
`folderId` 变为 `null`。文件夹选择可与搜索以及普通任务、中断收尾任务筛选
组合使用。

## 队列和触发器

同一时间只执行一个任务。待执行任务按 `BACKGROUND`、`NORMAL`、`URGENT`
排序。监听器创建的任务和 `CREATE_TASK` 步骤都进入同一个队列。

触发器独立保存，但绑定到一个已保存的目标任务，一个任务可配置多个触发器。
触发器支持掉落物、饱食度和缓存更新。任务选择页会读取触发器记录用于队列摘要
和监听状态，但不会在此页创建、编辑或删除触发器。触发间隔与冷却时间保持原语义。

后台任务可以在完成后重新排队。`requeueAfterInterruption` 控制被中断任务在
收尾完成后是否重新排队。

## 配置

运行时文件为 `config/stowling.json`，schema 版本为 7：

```json
{
  "schemaVersion": 7,
  "coordinates": [],
  "tasks": [],
  "regions": [],
  "categoryZones": [],
  "triggers": [],
  "taskFolders": []
}
```

旧文件缺少新任务或步骤字段时按默认值兼容读取。未知字段、重复步骤 ID、过深
嵌套、悬空类型化引用和无效中断引用会被拒绝。配置写入继续使用修订号冲突保护，
API 修改不会绕过现有保护。

## 构建

使用 Java 25 和仓库自带的 Gradle wrapper：

```powershell
.\gradlew.bat build
```
