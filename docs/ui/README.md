# 任务与坐标 UI 标准

[公共标准](settings-ui-standard.md) 统一主题、控件、动画、键盘操作与状态边界。
下面两个可运行示例共用模板目录中的公共资源，保留任务和坐标各自的布局与密度。

- [任务卡片模板](task-settings-standard.html)：任务标题、设置行、子菜单和实验性设置。
- [坐标管理模板](coordinate-management-standard.html)：资源列表、坐标编辑与分类区绑定组。

直接在仓库中用浏览器打开，不需要启动游戏或安装依赖；可切换浅绿和深灰主题。
模板不连接真实接口，示例数据与演示状态不能移植为正式业务逻辑。

[项目提示词](task-ui-prompt.md) 规定布局、主题、开关语义、展开动画和下拉选项规则。
根目录 `AGENTS.md` 已要求 AI 在处理任务 UI 前读取它，无需每次重新提供截图。
不支持自动读取 `AGENTS.md` 的工具，可将该提示词全文作为项目指令。

公共配色、控件与交互分别维护在 `settings-theme.css`、`settings-controls.css`
和 `settings-controls.js`，位于 `docs/ui/shared/`。
保留模板相对路径及其引用的仓库目录即可离线预览，两个模板共用一份配色快照。
正式页面仍使用原有资源，尚未接入这次合并。

`tests/test_task_settings_sample.py` 覆盖主题、离线资源、可访问标签和控件引用。
`tests/test_task_settings_sample_interactions.py` 覆盖任务与配置解耦、旧状态迁移、
下拉键盘操作、菜单弹出方向、动画中断和主菜单动画。
`tests/test_shared_settings_controls.py` 覆盖两个模板共用资源后的真实浏览器行为。
按项目规定，运行测试和视觉验证需遵守用户授权。
