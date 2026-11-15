# Stowling

Stowling 是面向 Minecraft 26.2 的 Fabric 客户端模组。当前版本只提供命名坐标点
和由 Baritone 执行的手动线性移动任务。

## 功能

- 坐标条目保存名称和 X/Y/Z，并可选填写维度和到达半径。
- 任务只保存名称和有序模块步骤。
- 首个内置模块为 `MOVE_TO_COORDINATE`。
- 同一时间只运行一个任务。
- 坐标、任务和步骤 ID 由系统内部生成，界面不显示。
- 保留登录验证、运行状态和只读库存诊断。

旧拾取工作流、维度、半径/区域、容器、库存路由、盘点、打包、制作和调度接口
均不再兼容。

## 配置

运行配置位于 `config/stowling.json`，只接受 schema 7：

```json
{
  "schemaVersion": 7,
  "coordinates": [],
  "tasks": []
}
```

文件缺失、损坏或来自旧 schema 时，Stowling 会尽可能创建 `.bak` 安全备份，
然后覆盖为空的新配置。旧数据不会迁移。

坐标可以启用显式维度；未启用时移动任务使用玩家当前所在维度。维度和半径开关
关闭时接口返回 `null`，由任务解释为空维度不限制、空半径使用任务内置到达容差。
任务启动时会冻结步骤和目标坐标快照，因此修改坐标只影响下一次运行。

## Web 控制台

启动游戏后打开：

```text
http://127.0.0.1:8765/
```

将 `config/stowling-api.token` 的内容填入连接表单。

页面包括：

- **状态概览**：当前运行、坐标数量、任务数量和运行记录。
- **坐标管理**：新增、修改和删除名称与 X/Y/Z。
- **移动任务**：组合模块步骤、保存任务、手动运行和取消。
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
