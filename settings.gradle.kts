/**
 * CI（GitHub Actions）直连官方仓库：阿里云镜像在海外 runner 上解析插件
 * marker 常失败（KSP 等），导致配置阶段即挂。本地开发默认仍走镜像
 * （本机无法直连 central/portal）；CI 置 CI=true 自动跳过镜像。
 * 注意：pluginManagement/dependencyResolutionManagement 块内无法引用顶层 val，
 * 故 isCi 需在各块内分别声明。
 */
pluginManagement {
    repositories {
        // 国内镜像优先，规避本机无法直连 mavenCentral/gradlePluginPortal 的问题（CI 跳过）
        val isCi: Boolean =
            System.getenv("CI")?.isNotEmpty() == true || System.getenv("GITHUB_ACTIONS") == "true"
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val isCi: Boolean =
            System.getenv("CI")?.isNotEmpty() == true || System.getenv("GITHUB_ACTIONS") == "true"
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "tapcreator"
include(":app")