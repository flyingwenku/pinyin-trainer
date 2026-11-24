// ===== 环境层：根构建脚本 =====
// 约定：只声明插件版本，不写任何应用相关逻辑。

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
