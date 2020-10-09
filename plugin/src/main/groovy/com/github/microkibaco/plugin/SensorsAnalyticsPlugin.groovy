package com.github.microkibaco.plugin

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * @author 杨正友(小木箱)于 2020/10/9 14 17 创建
 * @Email: yzy569015640@gmail.com
 * @Tel: 18390833563
 * @function description:
 */
class SensorsAnalyticsPlugin implements Plugin<Project> {
    void apply(Project project) {

        SensorsAnalyticsExtension extension = project.extensions.create("sensorsAnalytics", SensorsAnalyticsExtension)

        boolean disableSensorsAnalyticsPlugin = false
        Properties properties = new Properties()
        if (project.rootProject.file('gradle.properties').exists()) {
            properties.load(project.rootProject.file('gradle.properties').newDataInputStream())
            disableSensorsAnalyticsPlugin = Boolean.parseBoolean(properties.getProperty("sensorsAnalytics.disablePlugin", "false"))
        }

        if (!disableSensorsAnalyticsPlugin) {
            AppExtension appExtension = project.extensions.findByType(AppExtension.class)
            appExtension.registerTransform(new SensorsAnalyticsTransform(project, extension))
        } else {
            println("------------您已关闭了小木箱插件--------------")
        }
    }
}