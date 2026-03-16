# Workshop Native

一个面向 Android 的 Steam 创意工坊下载工具，支持匿名探索公开工坊内容，也支持登录 Steam 后访问“我的内容”和下载已购/共享游戏的创意工坊条目。

## 功能

- 匿名访问公开工坊游戏与条目
- Steam 登录、已保存账号切换
- 我的内容：已购买游戏、家庭共享游戏
- 创意工坊浏览、筛选、搜索、分页
- 下载中心：排队、暂停、继续、重试、取消、单条删除
- 下载详情：展示下载链路、网络路径、节点地址、源地址等诊断信息
- 收藏常用工坊游戏
- 设置页：下载位置、并发、匿名优先、CDN 策略、更新检查
- GitHub Release 更新检查

## 技术栈

- Kotlin
- Jetpack Compose
- Hilt
- ViewModel + StateFlow
- Repository
- Room
- DataStore
- WorkManager
- OkHttp

## 项目结构

- `app/src/main/java/com/slay/workshopnative/ui`：界面与页面状态
- `app/src/main/java/com/slay/workshopnative/data`：数据模型、仓储、会话与本地存储
- `app/src/main/java/com/slay/workshopnative/worker`：后台下载任务
- `app/src/main/java/com/slay/workshopnative/core`：下载与通用工具
- `app/src/main/java/com/slay/workshopnative/update`：GitHub 更新检查

## 构建要求

- JDK 17
- Android SDK 35
- Android Studio Ladybug 或兼容版本

## 本地构建

如果本地没有 `local.properties`，可以直接用环境变量指定 SDK：

```bash
ANDROID_HOME=<your-android-sdk> \
ANDROID_SDK_ROOT=<your-android-sdk> \
./gradlew :app:assembleDebug
```

代码检查：

```bash
ANDROID_HOME=<your-android-sdk> \
ANDROID_SDK_ROOT=<your-android-sdk> \
./gradlew :app:lintDebug
```

## 更新配置

项目内已经接入 GitHub Release 更新检查，但当前仓库占位还未替换。

提交到你自己的 GitHub 仓库后，请修改：

- `app/build.gradle.kts`

将下面两个占位值替换为真实仓库信息：

- `UPDATE_GITHUB_OWNER`
- `UPDATE_GITHUB_REPO`

## 说明

- 项目不依赖自建后端。
- 仓库已排除本地构建产物、截图、缓存和机器本地配置。
- 默认下载位置为系统下载目录下的 `steamapps`，也支持用户自定义根目录。
