<p align="center">
    <img src="src/main/resources/assets/stowling/icon.png" width="128" height="128" alt="">
</p>

<p align="center">
    <a href="README_en.md"><kbd>English</kbd></a>
</p>

Stowling 是一款用于自动分类和搬运物品的 Minecraft Fabric 模组。

自动分类任务的缓存设置支持“打包优先”和“分类优先”，容量阈值默认为 80%，可在
1%～100% 之间调整。占用率按全部缓存容器的槽位汇总计算，装好的潜影盒只占一格。
达到阈值后，打包优先会将投料装盒并存回缓存，盒子也放不下时再分类腾空间；
分类优先则先存好本批投料，再执行分类。装盒需要在公共设施中配置空潜影盒供应。

## Minecraft 26.2 必装模组

- [Fabric API 0.161.0+26.2](https://modrinth.com/mod/fabric-api/version/ewUK83HI)
- [Baritone 1.19.0 (Fabric)](https://github.com/cabaletta/baritone/releases/tag/v1.19.0)
- [Chest Tracker（非官方移植版） 2.8.4+26.2](https://modrinth.com/mod/chest-tracker-port/version/NLV8VOf4)
- [YACL 3.9.7+26.2-fabric](https://modrinth.com/mod/yacl/version/3.9.7%2B26.2-fabric)
