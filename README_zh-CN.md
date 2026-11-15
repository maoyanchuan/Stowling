# Stowling

Stowling 是面向 Minecraft 26.2 的 Fabric 客户端模组，提供命名坐标、角点区域
以及由 Baritone 执行的手动线性任务。

## 功能

- 坐标条目保存名称和 X/Y/Z，并可选填写维度和到达半径。
- 区域条目保存名称以及两个完整 X/Y/Z 角点。
- 任务只保存名称和有序模块步骤。
- 内置模块支持移动、等待掉落物、拾取掉落物、扫描区域容器和存入拾取物。
- 容器扫描和存入是两个独立模块；存入模块直接使用上一次扫描结果，不再重复扫描。
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
- `SCAN_CONTAINERS_IN_REGION`：扫描区域容器，并把结果记录到当前任务运行中。
- `DEPOSIT_ITEMS_TO_CONTAINERS`：只把本次运行拾取的物品存入上次扫描到的容器。

一个完整的掉落物入箱任务示例：

```text
WAIT_FOR_DROPPED_ITEMS     coordinateId=<掉落点>
COLLECT_DROPPED_ITEMS      coordinateId=<掉落点>
SCAN_CONTAINERS_IN_REGION  regionId=<存储区域>
DEPOSIT_ITEMS_TO_CONTAINERS
    scanSource=previous-scan
    itemSource=collected-this-run
    fillMode=nearest-first
```

检测和扫描只覆盖玩家当前维度内已经加载的区块。扫描支持普通容器，不把潜影盒
内部的物品当作独立容器。

## 配置

运行配置位于 `config/stowling.json`，只接受 schema 7：

```json
{
  "schemaVersion": 7,
  "coordinates": [],
  "tasks": [],
  "regions": []
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
- **任务管理**：组合模块步骤、保存任务、手动运行和取消。
- **库存查询**：只读查看已有库存快照。

## HTTP 接口

所有 `/v1/*` 请求都需要 Bearer Token。

```text
GET    /v1/status
GET    /v1/inventory
GET    /v1/task-modules

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
