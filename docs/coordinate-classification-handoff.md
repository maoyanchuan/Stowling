# 坐标分类功能接入 Mod 交接

这是一次性交接快照，记录于 2026 年 11 月 24 日。接入完成后可删除或归档，
不替代 `AGENTS.md`，也不作为新的长期规范。

## 接入完成（2026 年 11 月 24 日）

- 已接入正式坐标编辑器、鉴权 API、配置保存和游戏分类解析，版本 `0.1.0-alpha.293`。
- 坐标排除只过滤自动标记；重复方块参照在整个分类区过滤后的原始列中一次性收集，
  仅采用剩余一种物品的结果，不链式推断。
- 三种多物品绑定目标追加真实物品 ID；全局和独立绑定组分别作用于各自范围，
  绝对坐标不随单片拓展平移，独立组的 ID 规则覆盖该组生成的单片。
- 关闭状态下保留独立配置、多行物品文本、未完成坐标与规则展开草稿。
- 已补充分类、API、持久化、拓展和正式页面测试；按项目规范未运行测试、检查或游戏验证。
- 主代理已使用 `assemble` 成功构建安装包与源码包；以下内容保留为本次确认依据。

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

resolver 已先采集整个分类区，再由 `v2/category/CategoryZoneClassifier.java` 处理过滤、
原始参照、重复判定和绑定追加，避免跨绑定组参照丢失或扫描顺序依赖。
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
`tests/test_coordinate_editor.py` 的模板预览结构比较例外已移除，正式页面与完整模板比较。
实际接入测试另见 `tests/test_coordinate_classification_editor.py`、
`CategoryZoneClassifierTest.java` 和 `CategoryOptionsPersistenceTest.java`。

补充实际分类测试：花簇支撑与正常草方块分类并存、珊瑚扇重复排除、
无法唯一判断时继续报错、三种绑定目标、继承与独立设置、保存复制及旧配置兼容。
用户未另行授权时，不运行测试、代码检查或游戏验证；Python 测试使用 pytest。

实现前按 `AGENTS.md` 同步当前分支，优先使用已配置代理。
实现后由主代理用 `assemble` 构建，不触发测试／检查；按需提升 `mod_version`，
成功后保留本次安装包与源码包，再按项目规定清理旧 JAR。
使用说明修改原因的 Conventional Commits 提交并正常 push；不提交生成 JAR 或本地数据。

新对话可直接输入：请读取本交接文档和 AGENTS.md，把已确认的坐标分类模板接入 Mod，
保持模板 UI 风格，按项目要求完成真实数据与分类逻辑接入。
