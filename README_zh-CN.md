# Stowling

Stowling 是面向 Minecraft 26.2 的 Fabric 客户端模组，提供命名坐标、角点区域
以及由 Baritone 执行的手动线性任务。

## 功能

- 坐标条目保存名称和 X/Y/Z，并可选填写维度和到达半径。
- 区域条目保存名称以及两个完整 X/Y/Z 角点。
- 任务保存名称、优先级、可选的后台自动重排以及有序模块步骤。
- 动作模块标记为基础 `BASIC`；多动作流程作为“任务预设”，由可复用的
  小模块组合而成。
- 内置模块支持移动、等待掉落物、拾取掉落物、区域收集、扫描区域容器、
  识别容器分类、缓存分类入库和存入拾取物。
- `AUTO_EAT_AT_COORDINATE` 会检查饱食度，在不足时前往食物箱，每次取出一个
  指定食物并吃完，直到恢复至阈值。
- 推荐把处理流程拆成“掉落物入缓存”和“缓存物品分类入库”两个可独立运行任务。
- 粗分类使用物理缓存容器；细分类从缓存取出有目标分类的物品并精确存入。
- 分类区由多个手工绑定组成，每个绑定包含“分类物识别区 → 储存区”。
- 分类区使用统一的自动展示物识别：展示框读取实体内容，展示架读取方块实体
  槽位，普通展示方块转换为对应放置物品。
- 每个储存列必须只解析出一个物品 ID；配置的支撑方块会被忽略，同一列出现
  多个不同物品时会直接报错，不会静默选错箱子。
- 单个储存容器可以配置追加或替换例外。追加规则保留自动分类并增加物品，
  替换规则忽略该容器的自动分类，也用于解决支撑方块与分类物相同的问题。
- `MARKER_CONTAINER_CONTENTS` 规则从识别区的箱子或木桶读取物品缓存；
  双联箱最多可为同一储存箱定义 54 种不同的接收物品。
- 分类模块使用展示物的注册 ID 作为分类。细分类不会把未识别物品塞入其他分类
  容器。
- 存入模块默认只移动当前任务实际拾取并记录下来的物品。
- 同一时间只执行一个任务，等待任务按后台、普通、紧急三档优先级排队。
- 严格更高优先级的任务会中断当前任务。被中断的运行直接作废；轮到它再次
  执行时使用新的运行记录并从第一步重新开始。
- 坐标、任务和步骤 ID 由系统内部生成，界面不显示。
- 保留登录验证、运行状态和只读库存诊断。

旧的整体拾取工作流、库存路由、盘点、打包、制作和调度接口均不再兼容。

## 任务模块

后端通过 `GET /v1/task-modules` 返回模块目录，任务编辑器依据目录自动生成参数
控件。同一目录还会列出监听器，模块参考按“监听器 → 动作模块”展示事件到任务
的流程；监听器不会出现在任务步骤选择器中。

任务列表和运行选择器会区分基础任务、任务预设和自定义组合；现有任务
配置无需迁移。

后台任务可以设置为完成后等待 5 秒自动重新排队。手动运行时可以临时覆盖
本次优先级，不会修改任务保存的默认优先级。

任务触发器独立于任务配置，不占用执行槽位。当前支持掉落物出现、饱食度低于
阈值，以及缓存区域实际收到新物品三种条件。触发器命中后，目标任务会按触发器
指定的优先级加入现有队列。

新任务步骤选择器显示 4 个基础动作模块和 4 个任务预设。旧模块仍可读取和运行，
但不会出现在新步骤的模块选择器中，避免继续创建重复工作流。

- `MOVE_TO_COORDINATE`：前往指定坐标。
- `COLLECT_DROPPED_ITEMS`：从坐标半径或保存的区域收集匹配掉落物。
- `SCAN_CONTAINERS_IN_REGION`：扫描区域容器，并把结果记录到当前任务运行中。
- `EAT_FOOD`：食用玩家当前物品栏中的指定食物。
- `AUTO_EAT_AT_COORDINATE`：组合饱食度监听、移动到坐标、取食和 `EAT_FOOD`；
  饱食度足够时直接跳过，不足时持续进食直到达到阈值。
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE`：解析自动展示物标记，把物品 ID 相同的
  缓存物品存入绑定储存区。
- `REFRESH_CATEGORY_MARKER_CACHE`：打开分类标记箱子或木桶，只读取槽位、
  不移动物品，并更新持久缓存。
- `DEPOSIT_ITEMS_TO_CONTAINERS`：只把本次运行拾取的物品存入上次扫描到的容器。
  该模块不再需要配置参数。
- “按分类区入库”预设内部调用分类规划、缓存取物、分类存款和退回缓存动作。
- “刷新分类容器缓存”预设内部调用标记范围扫描、标记容器读取和索引保存动作。

`WAIT_FOR_DROPPED_ITEMS`、`COLLECT_DROPPED_ITEMS_IN_REGION`、
`MATCH_CONTAINER_CATEGORIES` 和 `SORT_CACHE_ITEMS` 仅作为旧任务兼容模块保留。
掉落物监听器代替等待模块，分类区入库代替原来的两段分类模块。

粗分类任务“掉落物入缓存”：

```text
COLLECT_DROPPED_ITEMS
    area=region:<掉落物区域>
SCAN_CONTAINERS_IN_REGION
    regionId=<缓存区域>
DEPOSIT_ITEMS_TO_CONTAINERS
    无需参数
```

细分类任务“缓存物品分类入库”：

```text
SORT_CACHE_ITEMS_BY_CATEGORY_ZONE
    cacheRegionId=<缓存区域>
    categoryZoneId=<分类区>
```

收集范围也可以写为 `area=coordinate:<坐标>`，使用该坐标保存的半径。
分类模板现在是普通优先级的一次性任务，由缓存更新触发器决定何时运行。

分类区使用 `MARKER_CONTAINER_CONTENTS` 时，在分类入库前按需要加入：

```text
REFRESH_CATEGORY_MARKER_CACHE
    categoryZoneId=<分类区>
```

只有该模块运行后才会更新分类样本缓存。缓存保存在
`config/stowling-category-marker-cache.json`；分类排序不会自动刷新，缺少
缓存时会提示先运行刷新模块。

自动进食模块使用 `minecraft:bread` 形式的物品 ID，并设置食物箱子坐标。
饱食度不足时，模块会打开箱子，只把一个食物移到快捷栏或副手，关闭箱子后
吃完；饱食度仍不足时重复取食。可选阈值默认是 18。

分类区 A 的手工绑定示例：

```text
分类物识别区：7 -34 7 到 18 -34 7
储存区：      7 -34 6 到 18 -34 5
规则：自动展示物精确匹配
忽略支撑方块：minecraft:stone_bricks
容器例外：    8 -34 5，追加，minecraft:oak_planks
```

绑定可以重复添加。识别区可以有 2 格高或 2 格宽，并混合普通方块、带支撑的
展示物、物品展示框和展示架。把统一底座或背景方块 ID 填入忽略列表即可。
每个储存列只能解析出一个不同的展示物品 ID，出现歧义时任务会停止并报告坐标。

容器例外规则绑定到一个储存位置。`ADD` 会保留自动分类并追加物品 ID；
`REPLACE` 会完全忽略该容器的自动分类，只使用配置的物品列表。这样可以
让一个箱子接收少量不同类物品，也可以处理石英等同时作为支撑和分类物的场景。

检测和扫描只覆盖玩家当前维度内已经加载的区块。扫描支持普通容器，不把潜影盒
内部的物品当作独立容器。双联箱子只记录一个代表位置。分类只按物品注册 ID
匹配，因此同一注册 ID 但名称或组件不同的展示物暂时不能区分。分类结果只在
当前任务运行中有效，不会写入配置。缓存区域和目标区域不能重叠。

## 配置

运行配置位于 `config/stowling.json`，只接受 schema 7：

```json
{
  "schemaVersion": 7,
  "coordinates": [],
  "tasks": [],
  "regions": [],
  "categoryZones": [],
  "triggers": []
}
```

文件缺失、损坏或来自旧 schema 时，Stowling 会尽可能创建 `.bak` 安全备份，
然后覆盖为空的新配置。旧数据不会迁移。

缓存触发器只在 `DEPOSIT_ITEMS_TO_CONTAINERS` 实际把物品移入选定缓存区域后
创建分类任务。示例：

```json
{
  "id": "00000000-0000-0000-0000-000000000004",
  "name": "存入缓存后分类",
  "type": "CACHE_UPDATED",
  "targetTaskId": "00000000-0000-0000-0000-000000000002",
  "priority": "NORMAL",
  "intervalTicks": 40,
  "cooldownTicks": 100,
  "cacheRegionId": "00000000-0000-0000-0000-000000000003"
}
```

这里的 `intervalTicks` 是触发防抖时间。连续多次存入同一缓存区域会合并为一次
分类运行；如果分类任务已经排队或正在运行，触发器只保留一次补跑，并在当前
任务和冷却结束后再加入队列。没有新的缓存变化时不会重复创建分类任务。

坐标可以启用显式维度；未启用时任务使用玩家当前所在维度。维度和半径开关关闭时
接口返回 `null`，掉落物模块使用任务内置的搜索半径。任务启动时会冻结步骤、
坐标、区域和当前运行内的扫描结果，因此修改已保存资源只影响下一次运行。

## Web 控制台

启动游戏后打开：

```text
http://127.0.0.1:8765/
```

将 `config/stowling-api.token` 的内容填入连接表单。

页面包括：

- **状态概览**：当前运行、坐标数量、任务数量和运行记录。
- **坐标**：以“坐标点 / 区域 / 分类区”子菜单统一管理。坐标点和区域维护
  可复用几何，分类区维护识别区、储存区和识别规则。
- **任务管理**：使用任务预设，或自行组合基础模块步骤。
- **库存查询**：只读查看已有库存快照。

## HTTP 接口

所有 `/v1/*` 请求都需要 Bearer Token。

```text
GET    /v1/status
GET    /v1/inventory
GET    /v1/task-modules
GET    /v1/category-rules

GET    /v1/coordinates
POST   /v1/coordinates
GET    /v1/coordinates/{uuid}
PUT    /v1/coordinates/{uuid}
DELETE /v1/coordinates/{uuid}

GET    /v1/regions
POST   /v1/regions
GET    /v1/regions/{uuid}
PUT    /v1/regions/{uuid}
DELETE /v1/regions/{uuid}

GET    /v1/category-zones
POST   /v1/category-zones
GET    /v1/category-zones/{uuid}
PUT    /v1/category-zones/{uuid}
DELETE /v1/category-zones/{uuid}

GET    /v1/tasks
POST   /v1/tasks
GET    /v1/tasks/{uuid}
PUT    /v1/tasks/{uuid}
DELETE /v1/tasks/{uuid}
POST   /v1/tasks/{uuid}/runs

GET    /v1/task-triggers
POST   /v1/task-triggers
GET    /v1/task-triggers/{uuid}
PUT    /v1/task-triggers/{uuid}
DELETE /v1/task-triggers/{uuid}

GET    /v1/task-runs
POST   /v1/task-runs/{uuid}/cancel
```

`GET /v1/task-runs` 同时返回 `runs`、`queue` 和 `triggers`。触发器状态包括
`IDLE`、`RUNNING`、`COOLDOWN` 和 `ERROR`。

修改和删除请求必须携带当前 `revision`。版本过期返回 `409`。删除仍被任务引用的
坐标返回 `409`，响应中列出引用任务名称。

## 开发

```powershell
.\gradlew.bat assemble --console=plain --no-daemon
```

主安装包位于 `build/libs/`，文件名不含 `-sources`。
