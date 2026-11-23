# 项目开发规范

## 代码规范
- 使用 4 空格缩进。
- 每行最多 100 字符。
- 所有函数必须有类型注解。

## 任务 UI 模板
- 新增、修改或重构任务页面、任务模块、设置行、子菜单和下拉控件前，
  必须先读取 `docs/ui/task-ui-prompt.md`，再检查其中指定的标准 HTML 与现有实现。
- `docs/ui/task-settings-standard.html` 是用户确认的唯一可运行 UI 模板。
  保留浅绿／深灰主题、控件排列、运行状态与配置编辑分离、展开动画和选项间距。
- 模板中的任务名称、地区和数值是示例；正式页面使用项目真实字段、接口和业务逻辑。
- 除非本轮用户明确要求调整模板规范，否则按模板实现，不另创一套任务 UI。
  用户明确要求的规范变化，应同步更新模板和项目提示词，避免后续实现回退。
- 模板规则不改变下文的测试授权和交付要求，不自动授权运行测试或代码检查。

## 坐标页面 UI 模板
- 修改正式坐标页面、坐标资源编辑器或分类区设置前，必须先读取：
  `docs/ui/task-ui-prompt.md`、`docs/ui/task-settings-standard.html`、
  `docs/ui/coordinate-management-standard.html`、
  `src/main/resources/stowling-web/workbench.css`、
  `src/main/resources/stowling-web/styles.css`、
  `src/main/resources/stowling-web/coordinates.html`、
  `src/main/resources/stowling-web/coordinates.js`、
  `src/main/resources/stowling-web/category-zones.js`。
- 正式坐标页面的设置行、下拉框、开关、展开箭头、主题和动画，必须参考
  `docs/ui/coordinate-management-standard.html` 实现。
- 模板中的名称、选项说明、坐标和数值只是界面示例；正式页面必须使用真实字段、
  接口和业务逻辑，不得直接复制示例数据。
- 保持模板约定的浅绿／深灰主题、设置行对齐、下拉选中勾号、键盘操作、上下展开、
  200／150ms reveal 动画，以及开关状态与展开状态分离。
- 坐标业务字段变化沿用模板结构；只有通用视觉或交互规范变化时，才同步更新模板、
  `docs/ui/task-ui-prompt.md` 和相关测试。

## 测试与代理分工
- 新功能必须包含测试；Python 测试使用 pytest。
- 未经用户另行要求，不运行测试、代码检查或游戏验证。
- 子代理只编写代码和测试，不运行测试或构建，不安装无关依赖。
- 主代理负责环境准备、计划、接口约定、整合和验收。
- 分派前提供工作目录、工具路径、网络代理、接口与互不重叠的修改范围。

## 修改后的交付流程
- 修改前先同步 GitHub 当前分支；优先使用已配置的网络代理。
- 使用普通 fast-forward pull；有本地改动或分支分歧时先处理，禁止强制覆盖。
- 修改完成后由主代理构建新版安装包；使用 assemble，不触发测试或检查任务。
- 按需更新 mod_version，避免新版与旧版混淆。
- 构建成功后，保留本次生成的安装包和源码包，清理 build/libs 中旧版项目 JAR。
- 删除前核对绝对路径，确保仅在 G:\github\Stowling\build\libs 中操作。
- 构建失败时保留旧包，不宣称新版已交付。
- 使用 Conventional Commits 提交，提交信息说明为什么修改，并正常 push 到 GitHub。
- 不强制推送，不提交生成的 JAR、凭据或本地运行数据。

## Review guidelines
- Don't log PII.
- Verify authentication middleware.
- Check for SQL injection.
