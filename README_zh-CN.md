# Stowling

<img src="src/main/resources/assets/stowling/icon.png" width="64" height="64" alt="">

Stowling 是面向 Minecraft 26.2 的 Fabric 客户端模组，提供命名坐标、区域、分类区、
优先级任务队列和条件监听器。移动与容器交互复用现有游戏接口和 Baritone 集成。

网页采用统一的深色任务卡片：名称和说明在左，开关、参数与展开按钮在右。
任务内的缓存、后台分类作为子功能配置；启用开关后展开对应参数，折叠不清空输入。
已保存的自动分类任务可在卡片标题右侧直接切换监听；当前任务有未保存修改时，
需先保存再切换。关闭监听不会取消已经排队的任务。

母版中的识别区、储存区默认显示坐标摘要，点击右侧箭头展开。操作站位区使用独立
子开关，可选功能关闭时保留编辑内容但不提交；再次开启可继续编辑。窄屏下参数排列
在名称下方，任务运行与日志仍保留独立区域。界面重排不改变已有配置结构和控制 API。

背包管理由客户端运行时中的全局 `PlayerInventoryManager` 负责，每个客户端 tick 都会
读取真实背包。首次成功读取时建立随身物品基准；物品账本通过 `PlayerInventoryAccount`
按运行 ID 区分归属，不随单次任务结束重建基准。全局状态保留在当前客户端会话内，
重启后重新建立基准，不会自动清走启动时已经携带的物品。

任务通过 `TaskExecutionContext.inventory()` 调用全局服务。`SYNC_PLAYER_INVENTORY`
模块核对全局保留物品和所有任务账本，将未归属物品纳入当前任务；旧模块
`SYNC_COLLECTED_INVENTORY` 仍兼容。收集和后台分类均调用统一的同步、循环补存和
最终校验流程；旧版任务加载时自动补齐。最终校验发现的新物品会在报错前登记到当前
账本，交给收尾流程处理。结束时仍有物品的账本会保留，并可由后续清理任务接手。

全物品监听启用时，空闲运行器发现背包有未归属物品或待接手账本，会以后台优先级
调用已启用的收集任务送回缓存；无需等待地面再次出现掉落物。多个全物品任务启用时，
使用配置顺序中的第一个启用收集任务。补存到缓存的物品会继续触发关联的后台分类。

分类到潜影盒存储位时，任务按物品类别分批搬运，并预留空盒和现场整理用的背包槽位。
每次分类前，先逐个打开配置区域内的缓存容器，清点实际槽位，不会移动库存。
整轮盘点成功后才取物；缓存区域未完整加载或有箱子无法读取时，任务报告盘点未完成。
选定目标箱后，先用当前维度的 Chest Tracker 记录估算是否需要带空盒，再前往分类箱。
记录只用于备料，实际容量以开箱后为准。打包使用随身物品和目标箱的超额散装，
不再返回缓存补料；只允许满盒但本批凑不足时，剩余物品留待后续批次处理。
挖掘临时潜影盒前，玩家会先走到盒子同层的相邻站位。
同组有多个箱子时，优先尝试库存记录显示可接收本批物品、且站位较近的箱子。
大箱子作为一个存储候选，寻路和开箱可走任意一半，但仍遵守操作站位区域。
实箱无法访问或无法接收时，分类会继续尝试同组其他候选。
本批物品无法存入而退回缓存时，只暂缓退回的品类；同一个缓存箱的其他品类和后续缓存箱
仍会继续处理，不会因一批退回就结束本次巡访。

后续取物模块可调用 `recordWithdrawn(account, items)` 登记主动取出，调用
`releaseToPlayer(account, items)` 将交付给玩家的数量转为全局保留物品。
其他任务的同步步骤不会认领这些物品；成功交付后也不会被空闲清理重新送回缓存。

分类区自动铺满支持“自动识别并铺满”：填写母版和总选区后，自动推断 X/Z 方向，
将沿该方向框成整排的母版提取成一格宽的单片，再以步长 1 连续铺满总选区。
保存前会预览方向和片数；特殊间距仍可手动选择方向与步长。已有配置保持原设置，
切换到“自动识别并铺满”后应用自动计算。整排母版含容器例外时需先整理为单片或使用手动模式。

母版中的每个分类物可选配“操作站位区域”，按玩家脚下坐标填写一格或台阶范围。
展开时，该区域与识别区、储存区一起平移，因此每片都有相对固定的操作站位，
无需逐个箱子填写。站位区可以在总选区外；自动提取整排母版时，等宽的整排站位区也会
提取成对应单片。未启用时保留原有寻路方式。

配置站位区后，储存容器只会从对应区域内访问。寻路会在区域内筛选交互距离可达的目标，
到达后再次检查玩家脚下位置和真实交互距离；存入、取出、读取的开箱操作也会复核站位。
若指定区域无法到达或够不到箱子，任务会失败，不会退回箱子正下方等区域外位置开箱。

任务管理页采用两栏布局。左侧窄栏上半部统一显示运行、排队和监听中的任务，
按紧急、普通、后台优先级排列。下半部显示任务状态与步骤进度，最多保留 300 条。
下载图标可导出本次运行的诊断日志，清空图标可清除已显示的进度记录。
清空后只追加新的进度变化，滚动到底部时会跟随最新记录。
通过运行队列中的「运行详情」可打开弹窗查看运行记录和中断收尾恢复操作。

右侧是固定单步移动任务工作台。上方集中显示已保存任务，默认收起为包含名称、
目标和优先级的紧凑任务条；点开后可设置任务名称、手动 XYZ 或已保存坐标、
运行优先级，并支持保存或加入队列。收起再展开不会丢失未保存输入，切换到其他
任务前会确认是否丢弃修改。下方任务选择只保留任务模板入口，选择后在上方打开
未保存草稿，保存后转为已保存任务条。这里只编辑完整定义为单个 `ACTION`、模块为
`MOVE_TO_COORDINATE` 的任务；复杂流程及不支持的移动配置不会被部分改写。
手动与已保存坐标输入在切换来源时分别保留，但保存时只序列化当前来源。
新建任务默认名称自动避开所有已保存任务（包括本页不展示的复杂任务），
例如“移动到坐标 2”。手动填写重名时会提示修改，不覆盖已有任务。

移动任务使用玩家脚下的方块坐标判断到达。手动 XYZ 和所有已保存坐标都必须
到达目标方块，不再把坐标资源的半径当作移动到达范围；坐标半径仍可供掉落物
检测等其他模块使用。无法到达时报告失败，不把路径提前停止当作完成。
容器输出引用仍采用容器交互范围，不受精确坐标移动规则影响。

移动资源由独立加载器读取 `GET /v1/tasks` 和 `GET /v1/coordinates`。监控加载器仍只读
`GET /v1/task-modules` 与 `GET /v1/task-runs`，连接后两个加载器并行启动。新任务使用
`POST /v1/tasks` 保存，编辑使用携带快照版本号的 `PUT /v1/tasks/{id}`，加入队列使用
`POST /v1/tasks/{id}/runs` 和空对象 `{}`。创建响应新增精确的 `taskId`，同时保留
`revision` 与 `tasks`；页面不再按名称或新旧 ID 差集猜测刚创建的任务。页面不会创建
坐标点，也不恢复文件夹和触发器配置接口。

后端任务、触发器和文件夹数据以及可复用编辑器源码仍保留。库存查询导航及网页
路由暂时移除，库存 API、数据和页面源文件保留，方便以后恢复。

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
监控只读取目录来翻译运行错误名称。固定移动任务选择器另行读取完整任务列表，
可复用编辑器源码仍保留在仓库中，但不在任务页展示。

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
文件夹；配置/API 写入会保持任务与文件夹分配一起保存。删除文件夹只解散分组，
其中的任务仍会保留，`folderId` 变为 `null`。文件夹数据仍供后端使用，
当前网页不提供文件夹管理或筛选控件。

## 队列和触发器

同一时间只执行一个任务。待执行任务按 `BACKGROUND`、`NORMAL`、`URGENT`
排序。监听器创建的任务和 `CREATE_TASK` 步骤都进入同一个队列。

触发器独立保存，但绑定到一个已保存的目标任务，一个任务可配置多个触发器。
触发器支持掉落物、饱食度和缓存更新。网页不再直接读取触发器配置，运行快照
仍会向队列提供监听状态。触发间隔与冷却时间保持原语义。

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
