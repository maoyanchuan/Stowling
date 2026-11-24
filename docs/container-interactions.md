# 全局容器交互模块

`MinecraftContainerInteractions` 是客户端容器操作的公共入口。
分类、普通存入、缓存取出、潜影盒打包与标记读取均使用它。

## 调用约定

- 在客户端线程调用，通过已有导航流程打开目标容器。
- `ready(menu)` 在服务器完整内容初始化后返回 true。不要仅凭界面出现或槽位数量判断就绪。
- `slots(menu, player)` 返回玩家或容器侧的槽位索引。
- `click(...)` 校验当前菜单归属后提交原版点击，支持快捷移动。
- `moveExact(..., source, destination, amount)` 精确搬运指定数量；调用前鼠标必须为空。
  目标需要有足够容量并允许放入相同组件的物品，部分搬运的源槽位必须允许回填。
- `ContainerClickPlan.exact(sourceCount, amount)` 提供不依赖游戏的点击规划。
- `ContainerInteractionBatch` 把一个精确物品键的多次操作组合为一波，最多 8 次搬运、64 次点击。
  下一种物品在上一波数量核对完成后继续处理。

## 数量与停止

调用方保留物品过滤、任务归属、背包预留空间与分类数量上限。
每波记录操作前的玩家和容器物品数量，完成后核对双方差值与鼠标状态，核对后才入账。
普通存入保留现有的部分接收核对和光标归还流程。
存取仍等待槽位稳定；原版客户端预测不会作为独立的网络确认消息使用。
有在途操作时先等待存取流程确认完成，再停止或交给统一收尾。

空盒归还通过 `MinecraftStorageAccess.returnEmptyBox` 执行。入口箱可能被漏斗持续抽空，
因此每波归还后重开原容器，等待新菜单完整初始化，再按服务器背包中空盒的减少量确认。
确认时扣除同时拾取造成的背包增量，要求鼠标为空，接收量必须落在本波计划范围内。
服务器撤销的存入不记账，部分接收只记实际数量；普通存取仍使用原有的双方数量核对。

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
