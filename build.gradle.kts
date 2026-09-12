// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

// 发布配置：POM 里的公共信息只写这一处（坐标和版本在各模块里声明，见各自的 group/version）。
// 目前只用于发到本机仓库：./gradlew publishToMavenLocal
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    // 中文直接写在构建脚本里：properties 文件按 ISO-8859-1 解码，中文放那儿会乱码
                    description.set("TRouter：多模块安卓工程的路由框架（页面之间解耦、构建期校验）")
                    url.set("https://github.com/Tyeeee/TRouter")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("Tyeeee")
                            name.set("Tyeeee")
                            url.set("https://github.com/Tyeeee")
                        }
                    }
                    scm {
                        url.set("https://github.com/Tyeeee/TRouter")
                        connection.set("scm:git:git://github.com/Tyeeee/TRouter.git")
                    }
                }
            }
        }
    }
}
