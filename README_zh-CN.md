# Stowling

Stowling 是面向 Minecraft 26.2 的 Fabric 客户端模组，提供命名坐标、角点区域
以及由 Baritone 执行的手动线性任务。

## 功能

- 坐标条目保存名称和 X/Y/Z，并可选填写维度和到达半径。
- 区域条目保存名称以及两个完整 X/Y/Z 角点。
- 任务只保存名称和有序模块步骤。
- 内置模块支持移动、等待掉落物、拾取掉落物、区域收集、扫描区域容器、
  识别容器分类、缓存分类入库和存入拾取物。
- 推荐把处理流程拆成“掉落物入缓存”和“缓存物品分类入库”两个可独立运行任务。
- 粗分类使用物理缓存容器；细分类从缓存取出有目标分类的物品并精确存入。
- 分类区由多个手工绑定组成，每个绑定包含“分类物识别区 → 储存区”。
- 分类区使用统一的自动展示物识别：展示框读取实体内容，展示架读取方块实体
  槽位，普通展示方块转换为对应放置物品。
- 每个储存列必须只解析出一个物品 ID；配置的支撑方块会被忽略，同一列出现
  多个不同物品时会直接报错，不会静默选错箱子。
- 单个储存容器可以配置追加或替换例外。追加规则保留自动分类并增加物品，
  替换规则忽略该容器的自动分类，也用于解决支撑方块与分类物相同的问题。
- 分类模块使用展示物的注册 ID 作为分类。细分类不会把未识别物品塞入其他分类
  容器。
- 存入模块默认只移动当前任务实际拾取并记录下来的物品。
- 同一时间只运行一个任务。
- 坐标、任务和步骤 ID 由系统内部生成，界面不显示。
- 保留登录验证、运行状态和只读库存诊断。

旧的整体拾取工作流、库存路由、盘点、打包、制作和调度接口均不再兼容。

## 任务模块

后端通过 `GET /v1/task-modules` 返回模块目录，任务编辑器依据目录自动生成参数
控件。

- `MOVE_TO_COORDINATE`：前往指定坐标。
- `WAIT_FOR_DROPPED_ITEMS`：等待坐标半径内出现匹配的掉落物。
- `COLLECT_DROPPED_ITEMS`：前往匹配的掉落物并等待拾取完成。
- `COLLECT_DROPPED_ITEMS_IN_REGION`：收集指定区域内的全部匹配掉落物。
- `SCAN_CONTAINERS_IN_REGION`：扫描区域容器，并把结果记录到当前任务运行中。
- `MATCH_CONTAINER_CATEGORIES`：读取附近展示框中的物品，为已扫描容器生成
  本次运行专用的分类映射。
- `SORT_CACHE_ITEMS`：从缓存区域取出有目标分类的物品，按展示框分类精确存入
  目标容器；未匹配、目标已满或取出失败的物品会留在或退回缓存。
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE`：解析自动展示物标记，把物品 ID 相同的
  缓存物品存入绑定储存区。
- `DEPOSIT_ITEMS_TO_CONTAINERS`：只把本次运行拾取的物品存入上次扫描到的容器。

粗分类任务“掉落物入缓存”：

```text
COLLECT_DROPPED_ITEMS_IN_REGION
    regionId=<掉落物区域>
SCAN_CONTAINERS_IN_REGION
    regionId=<缓存区域>
DEPOSIT_ITEMS_TO_CONTAINERS
    scanSource=previous-scan
    itemSource=collected-this-run
    fillMode=nearest-first
```

细分类任务“缓存物品分类入库”：

```text
SCAN_CONTAINERS_IN_REGION
    regionId=<目标存储区域>
MATCH_CONTAINER_CATEGORIES
    scanSource=previous-scan
SORT_CACHE_ITEMS_BY_CATEGORY_ZONE
    cacheRegionId=<缓存区域>
    categoryZoneId=<分类区>
```

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
  "categoryZones": []
}
```

文件缺失、损坏或来自旧 schema 时，Stowling 会尽可能创建 `.bak` 安全备份，
然后覆盖为空的新配置。旧数据不会迁移。

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
- **坐标管理**：新增、修改和删除名称与 X/Y/Z。
- **分类区管理**：手工维护分类识别区、储存区和识别规则。
- **任务管理**：使用“缓存任务”和“分类任务”模板，或自行组合模块步骤。
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

GET    /v1/task-runs
POST   /v1/task-runs/{uuid}/cancel
```

修改和删除请求必须携带当前 `revision`。版本过期返回 `409`。删除仍被任务引用的
坐标返回 `409`，响应中列出引用任务名称。

## 开发

```powershell
.\gradlew.bat assemble --console=plain --no-daemon
```

主安装包位于 `build/libs/`，文件名不含 `-sources`。
