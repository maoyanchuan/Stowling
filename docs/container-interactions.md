# 全局容器交互模块

`MinecraftContainerInteractions` 是客户端容器操作的公共入口。
分类、普通存入、缓存取出、潜影盒打包与标记读取均使用它。

## 调用约定

- 在客户端线程调用，通过已有导航流程打开目标容器。
- `ready(menu)` 在服务器完整内容初始化后返回 true。不要仅凭界面出现或槽位数量判断就绪。
- `slots(menu, player)` 返回玩家或容器侧的界面槽位索引。
- `inventoryIndex(menu, slot)` 校验真实玩家背包归属，再映射到 0—35 的固定索引。
- `transferSlots` 过滤锁定玩家栏位；公共点击和精确搬运在发包前再次校验来源与目标。
  容器到玩家的快速移动在有锁定格时拆为未锁定目标的精确搬运，避免原版分配到锁定空格。
  自动进食有专用使用入口，允许消耗指定锁定格食物并还原位置，不解除栏位锁。
- 锁定存储仅保存 `schemaVersion: 2` 与 `lockedSlots`；旧文件备份为 `.v1.bak`，
  等待首个完整 36 格背包快照后迁移，同类物品当前所在格全部转为整格锁定。
- `click(...)` 校验当前菜单归属后提交原版点击，支持快捷移动。
- `moveExact(..., source, destination, amount)` 精确搬运指定数量；调用前鼠标必须为空。
  目标需要有足够容量并允许放入相同组件的物品，部分搬运的源槽位必须允许回填。
- `ContainerClickPlan.exact(sourceCount, amount)` 提供不依赖游戏的点击规划。
- `ContainerInteractionBatch` 把同一精确物品键或显式启用的多键操作组合为一波，
  最多 36 次搬运、64 次点击。每波数量核对完成后再继续搬运。

## 数量与停止

调用方保留物品过滤、任务归属、背包预留空间与分类数量上限。
每波记录操作前的玩家和容器物品数量，完成后核对双方差值与鼠标状态，核对后才入账。
普通缓存存入从真实未锁定格生成候选，逐格使用原版快速移动，允许箱子只接收部分。
每格作为独立批次，记录菜单对象、完整同步代数、来源界面槽位和背包索引。
当前 Minecraft 26.2 的成功预测可能不产生槽位更新；普通存入点击使用不匹配的状态版本，
由原版 `handleContainerClick` 处理点击后回传完整容器状态，不关闭或重新寻路。
`ContainerMenuMixin.initializeContents` 保存服务器快照并推进该菜单对象的同步代数。
只有该批菜单的新完整服务器快照可确认接收量；以服务器箱内增量记录本格实际存入，
客户端预测、轮询 tick、拾取回执与背包总量下降都不是普通缓存存入的确认条件。
服务器回滚记零接收；超时或菜单失效停止任务，未确认操作不记为交付。
新增未锁定物品由下一批重新读取，继续复用已打开缓存；错误后保留余物，不重试满箱收尾。
存取仍等待槽位稳定；原版客户端预测不会作为独立的网络确认消息使用。
有在途操作时先等待存取流程确认完成，再停止或交给统一收尾。

空盒归还通过 `MinecraftStorageAccess.returnEmptyBoxes` 批量执行，允许混合多种颜色和组件。
同一次访问尽量存完该目标可接收的任务空盒；缓存批次受剩余留存配额限制，箱满后再换箱。
批次内持有同一个安全手持槽位，重新开箱核对时继续复用，存取结束或安全停止后才恢复。
入口箱可能被漏斗持续抽空，
因此每波归还后重开原容器，等待新菜单完整初始化，再按服务器背包中空盒的减少量确认。
确认时扣除同时拾取造成的背包增量，要求鼠标为空，接收量必须落在本波计划范围内。
服务器撤销的存入不记账，部分接收只记实际数量；其他有明确业务数量的存取保留原有双方数量核对。

完整数据初始化由 `ContainerMenuMixin` 监听 `initializeContents`。
同步记录绑定菜单对象，防止重复使用的窗口编号继承旧窗口状态。

## 参考

参考以下项目的处理时机和职责划分，代码按本项目接口独立实现：

- [TweakerMore 自动容器处理](https://github.com/Fallen-Breath/tweakermore/tree/master/src/main/java/me/fallenbreath/tweakermore/impl/features/autoContainerProcess)：
  容器完整数据初始化后执行自动处理；`autoFillContainer` 调用批量移动，
  `autoCleanContainer` 的原功能是批量丢弃。本模块提供任务可控的存取原语。
- [Tweakeroo InventoryUtils](https://github.com/maruohon/tweakeroo/blob/ornithe/1.12.2/src/main/java/tweakeroo/util/InventoryUtils.java)：
  公共物品操作工具与业务功能分离。
- [ChestTracker](https://github.com/JackFred2/ChestTracker)：
  按实际容器界面及内容维护记录；本项目继续使用已接入的 Chest Tracker 库存来源。

此模块不增加对 TweakerMore、Tweakeroo 或 Item Scroller 的运行时依赖。
