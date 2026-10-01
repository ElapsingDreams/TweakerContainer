# 多版本构建说明

本模组现在一棵仓库里同时维护多个 Minecraft 版本，每个版本一个独立子项目，互不影响。

## 目录结构

```
tweakercontainer/
├── settings.json                 版本清单（往这里加一行就多一个版本）
├── settings.gradle               按清单为每个版本建子项目
├── build.gradle                  声明 Loom 插件版本，提供 buildAll
├── gradle.properties             与具体 MC 版本无关的东西（mod 版本号、Loom 版本等）
├── src/                          1.21.6–1.21.8 这一档的源码（共享源码放在这一版）
└── versions/
    ├── 1.21.8-fabric/
    │   ├── gradle.properties     这一档的 MC / yarn / loader / fabric-api / malilib / litematica 版本
    │   └── build.gradle          这一档的构建（源码指向仓库根目录的 src/）
    └── 26.2-fabric/
        ├── gradle.properties
        ├── build.gradle
        └── src/                  26.2 专属源码（与 1.21.x 那份各改各的）
```

## 构建

| 目的 | 命令 |
|---|---|
| 构建全部版本 | `gradlew buildAll` |
| 只构建 1.21.6–1.21.8 | `gradlew :1.21.8-fabric:build` |
| 只构建 26.2 | `gradlew :26.2-fabric:build` |

产物在 `versions/<版本>/build/libs/`：

- `tweakercontainer-2.2.5-1.21.7.jar`（覆盖 1.21.6–1.21.8）
- `tweakercontainer-2.2.5-26.2.jar`

文件名里带的是这个构建对应的 MC 版本；**jar 内部记录的 mod 版本始终是干净的 `a.b.c`**（现在是 `2.2.5`）。
版本号规则：`a.b` 不轻易动（要完全没 bug 才升 `b`），日常只升 `c`。

## 环境要求

- **JDK 21 以上**跑 Gradle 本身。
- **26.2 要求跑 Gradle 的那个 JVM 是 Java 25**（Loom 会直接检查）。本机没有装 JDK 25，
  用的是启动器自带的运行时：

  ```powershell
  $env:JAVA_HOME = 'C:\Users\Envision\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon'
  .\gradlew.bat :26.2-fabric:build
  ```

  用 Java 21 跑 `:1.21.8-fabric:build` 没问题；`buildAll` 会因为要构建 26.2 而需要 Java 25。
- Gradle wrapper 是 **9.5.0**（Loom 1.17.21 要求 ≥ 9.5）。

## 两个版本为什么用不同的 Loom 插件

Minecraft 26.1 起官方 jar **不再混淆**，工具链也跟着分成两套：

| | 1.21.x（有混淆） | 26.2（未混淆） |
|---|---|---|
| 插件 id | `fabric-loom`（= `fabric-loom-remap`） | `net.fabricmc.fabric-loom` |
| 映射 | 需要（本模组仍用 yarn） | 不需要，也不该写 `mappings` |
| 依赖写法 | `modImplementation` | 普通 `implementation` |
| 产物任务 | `remapJar` | 普通 `jar` |

另外 26.2 侧 Fabric API 要**按模块**声明（umbrella 那个 jar 里是嵌套模块，未重映射的 Loom 变体不会展开它），
见 `versions/26.2-fabric/build.gradle` 里的 `fabricApi.module(...)`。

## 当前状态

- 1.21.6–1.21.8：功能完整，构建通过，一直在游戏里用。
- 26.2：源码已移植（malilib 0.29 的新 data 层、26.2 的 GUI/渲染/注册表 API 都已适配），
  `gradlew :26.2-fabric:build` 能出包。**但还没有在 26.2 游戏里实测过功能行为**。
