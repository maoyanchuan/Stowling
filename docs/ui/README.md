# 任务 UI 标准模板

[task-settings-standard.html](task-settings-standard.html) 是用户确认的唯一可运行模板。
直接用浏览器打开即可，不需要启动游戏或安装依赖；右上角可切换浅绿和深灰主题。
本页仅模拟任务开始／关闭与设置交互，不连接真实任务接口。

[项目提示词](task-ui-prompt.md) 规定布局、主题、开关语义、展开动画和下拉选项规则。
根目录 `AGENTS.md` 已要求 AI 在处理任务 UI 前读取它，无需每次重新提供截图。
不支持自动读取 `AGENTS.md` 的工具，可将该提示词全文作为项目指令。

后续修改以这份 HTML 为基准；不要再复制一份模板或逐个模块重新看图猜样式。
模板中的名称、地区和数值仅用于展示控件，正式任务使用真实业务内容。

`tests/test_task_settings_sample.py` 覆盖主题、离线资源、可访问标签和控件引用。
`tests/test_task_settings_sample_interactions.py` 覆盖任务与配置解耦、旧状态迁移、
下拉键盘操作、菜单弹出方向、动画中断和主菜单动画。
按项目规定，仅在用户要求时运行测试。
