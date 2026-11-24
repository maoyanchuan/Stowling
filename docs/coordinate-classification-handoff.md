# 坐标分类功能接入 Mod 交接

这是一次性交接快照，记录于 2026 年 11 月 24 日。接入完成后可删除或归档，
不替代 `AGENTS.md`，也不作为新的长期规范。

## 当前进度

- 工作目录：`G:\github\Stowling`，分支：`main`。
- UI 基线提交：`50e2cc8`，已推送 GitHub。
- 用户已确认分类逻辑，并要求保持现有浅绿／深灰 UI 风格。
- 提示词标题中的“待用户确认”是模板阶段残留字样，本次方案已获确认。
- 已完成离线模板、交互和测试用例；正式页面、API、配置模型和游戏执行尚未接入新功能。
- 未运行测试、代码检查或游戏验证，也没有为这些模板改动生成新版安装包。
- 下一步：把已确认模板接入真实 Mod，实现保存、读取和实际分类，不能只复制 HTML。

## UI 依据

先读 `AGENTS.md`、`docs/ui/task-ui-prompt.md` 和两份标准模板：

- `docs/ui/coordinate-management-standard.html`：本次坐标页面的完整效果与交互依据。
- `docs/ui/task-settings-standard.html`：共用设置控件的样式依据。

常驻三个下拉框：储存模式、识别模式、储存方式。
“识别规则”默认收起，包含忽略支撑方块、按坐标排除、忽略重复方块，
标题显示启用项数，只控制展开，不设总开关。
“多物品绑定”默认收起并显示规则数，每条规则也可独立展开；新增规则直接展开，
删除按钮放在子菜单底部。指定站立位置保持独立入口。

保持原有对齐、缩进、悬停、下拉勾号、键盘操作及 200／150ms 展开动画。
全局与绑定组独立设置使用同一套控件，关闭或收起时保留草稿。
模板内的名称、坐标和物品清单仅为示例，不得写入真实用户配置。

## 已确认的分类规则

1. **按坐标排除标记**：只跳过指定坐标的自动标记识别。
   例如排除花簇下面的草方块，同时保留其他位置的草方块分类；
   不禁止草方块入库，不使该坐标的指定储存容器失效。
2. **忽略重复方块**：先应用方块 ID 和坐标排除，再收集当前分类区原本只含一种
   摆放方块标记的单片作为参照。对冲突单片排除这些有独立参照的重复方块，
   只有剩下一种物品时才采用结果；剩零种、多种或无参照时仍提示冲突。
   独立分类保留，推断结果不再扩充参照，不能依赖扫描顺序做连锁排除。
   示例：独立台阶单片保留；“珊瑚扇＋平滑石英台阶”单片只识别珊瑚扇。
3. **多物品绑定**：一组物品可绑定到识别物 ID、指定标记坐标或指定容器坐标。
   ID 目标沿用当前作用范围内同类标记对应的储存列；标记坐标只对应一个标记；
   容器目标必须在有效储存区内。物品追加到目标原有分类，仍按各自真实 ID 入库。
   不自行增加分组与独立分类之间的新优先级。
   被坐标排除的标记不能再作为标记目标，但同坐标作为容器目标不受影响。

先完成自动标记过滤、重复判定及目标解析，再追加多物品绑定。
全局继承和组内独立配置沿用现有语义，不覆盖关闭状态下保留的独立草稿。

## 接入位置

正式前端在 `src/main/resources/stowling-web/`：
`coordinates.html`、`coordinate-editor.css`、`coordinate-editor.js`、
`coordinate-editor-model.js`、`category-zone-model.js`。
注意实际生效的是 editor 文件，不能只修改旧的 `coordinates.js` 或 `category-zones.js`。

后端主要入口在 `src/main/java/dev/stowling/`：

- `v2/model/CategoryZoneOptions.java`、`CategoryBindingDefinition.java`：配置与生效字段。
- `v2/api/V2ControlServer.java`：请求字段白名单及反序列化。
- `v2/config/ConfigStore.java`、`ConfigValidator.java`：持久化与校验。
- `v2/control/V2ControlServiceImpl.java`：全局／独立配置编译及已有数据处理。
- `v2/model/CategoryZoneTiler.java`：核对新字段的拓展范围，避免固定坐标规则被意外广播。
- `client/automation/MinecraftCategoryZoneResolver.java`：采集与解析标记。
- `v2/category/ColumnCategoryMatcher.java`、`DirectBlockIdCategoryRule.java`：列匹配与冲突。

现有 resolver 逐绑定组采集并立即解析；重复参照可能位于其他绑定组，
需要先收集同一分类区的原始单片信息，再判定冲突，不能在首个冲突处提前结束。
底层已有 `CategoryContainerOverrideDefinition` 的多 ID 追加／替换能力，可复用追加能力。

模板新增草稿字段：`ignoreMarkerPositions`、`ignoredMarkerPositions`、
`ignoreRepeatedBlocks`、`multiItemBindingsEnabled`、`multiItemBindings`。
绑定规则包含 `name`、`targetType`、`markerId`、`position`、多行文本 `itemIds`；
`targetType` 为 `marker-id`、`marker-position`、`container`。
`expanded` 仅是规则编辑菜单状态。正式接入时区分编辑草稿与执行用 ID 列表，
补齐 API 和配置文件白名单，并为旧数据提供关闭／空列表默认值。

## 测试与交付

参考 `tests/test_coordinate_custom_rules_sample.py`、
`tests/test_coordinate_binding_options_sample.py` 和相关坐标测试。
`tests/test_coordinate_editor.py` 目前为模板预览保留了结构比较例外，
正式页面同步后应移除相应例外并验证真实页面，而不是继续扩大豁免范围。

补充实际分类测试：花簇支撑与正常草方块分类并存、珊瑚扇重复排除、
无法唯一判断时继续报错、三种绑定目标、继承与独立设置、保存复制及旧配置兼容。
用户未另行授权时，不运行测试、代码检查或游戏验证；Python 测试使用 pytest。

实现前按 `AGENTS.md` 同步当前分支，优先使用已配置代理。
实现后由主代理用 `assemble` 构建，不触发测试／检查；按需提升 `mod_version`，
成功后保留本次安装包与源码包，再按项目规定清理旧 JAR。
使用说明修改原因的 Conventional Commits 提交并正常 push；不提交生成 JAR 或本地数据。

新对话可直接输入：请读取本交接文档和 AGENTS.md，把已确认的坐标分类模板接入 Mod，
保持模板 UI 风格，按项目要求完成真实数据与分类逻辑接入。
