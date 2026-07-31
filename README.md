Paper [![Paper Build Status](https://img.shields.io/github/actions/workflow/status/PaperMC/Paper/build.yml?branch=main)](https://github.com/PaperMC/Paper/actions)
[![Discord](https://img.shields.io/discord/289587909051416579.svg?label=&logo=discord&logoColor=ffffff&color=7389D8&labelColor=6A7EC2)](https://discord.gg/papermc)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/papermc?label=GitHub%20Sponsors)](https://github.com/sponsors/PaperMC)
[![Open Collective](https://img.shields.io/opencollective/all/papermc?label=OpenCollective%20Sponsors)](https://opencollective.com/papermc)
===========

### 自动构建paper server.jar指南
https://github.com/zx1447/Pr-2026/blob/main/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java

1：fork本项目

2：在Actions菜单允许 `I understand my workflows, go ahead and enable them` 按钮

3：在`paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java`文件里 95到111 中添加需要的环境变量，不需要的留空，保存后Actions会自动构建

4：等待7分钟左右，在右侧的Release里下载server.jar文件
