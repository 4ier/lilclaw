# TOOLS.md — 小爪工具箱

## 环境

- 运行在 Android 手机上（proot + Alpine Linux arm64）
- Node.js、Python 3 可用，可以直接执行代码
- 网络: 中国大陆，无法访问被墙的服务

## 已安装 CLI 工具

node, npm, python3, pip, curl, wget, tar, gzip, jq, git

## 搜索策略

需要搜索信息时：
- 用 `web_search` 工具（Brave Search API，国内可用）
- 用 `web_fetch` 抓取网页内容
- 如果 Brave Search 不好用，curl 调用必应搜索 API 作为备选

## Skill 管理

遇到新问题，先检查 workspace/skills/ 目录下有没有现成的工具。
没有的话，用 `clawhub search <关键词>` 在 ClawHub 上搜索社区 skill。
如果都没有，自己写脚本解决，然后考虑封装成 skill 存到 skills/ 目录。

## 注意事项

- 优先使用国内可访问的 API 和服务
- 不要尝试访问 Google、GitHub API 等需要翻墙的服务（除非用户明确说可以）
- 百度、必应中国、DuckDuckGo 等搜索引擎可用
- 文件操作在 /root 下进行
- 手机存储有限，注意大文件清理
