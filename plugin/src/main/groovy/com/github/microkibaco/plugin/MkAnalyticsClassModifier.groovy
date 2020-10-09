package com.github.microkibaco.plugin

import groovyjarjarasm.asm.ClassReader
import groovyjarjarasm.asm.ClassVisitor
import groovyjarjarasm.asm.ClassWriter
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.utils.IOUtils

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.regex.Matcher

class MkAnalyticsClassModifier {
    // 将修改的 .class 文件放到一个HashMap对象中
    private static HashSet<String> exclude = new HashSet<>();
    static {
        exclude = new HashSet<>()
        // 过滤.class文件1: android.support 包下的文件
        exclude.add('android.support')

        // 过滤.class文件2: 我们sdk下的.class文件
        exclude.add('com.github.microkibaco.asm_sdk')
    }


    /**
     * 1. 使用 ASM 的 ClassReader 类读取 .class 的字节数组并加载类
     * 2. 使用 ClassVisor "拜访" 类 并进行修改符合特定条件的方法
     * @param jarFile jar文件
     * @param tempDir 文件目录
     * @param nameHex 文件hash值
     * @return 字节数组
     */
    static File modifyJar(File jarFile, File tempDir, boolean nameHex) {
        /**
         * 读取原 jar
         */
        def file = new JarFile(jarFile, false)

        /**
         * 设置输出到的 jar
         */
        def hexName = ""
        if (nameHex) {
            hexName = DigestUtils.md5Hex(jarFile.absolutePath).substring(0, 8)
        }
        def outputJar = new File(tempDir, hexName + jarFile.name)
        // 1. 使用 jarOutOutput 相关 API 对jar 进行解压
        JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(outputJar))
        Enumeration enumeration = file.entries()
        while (enumeration.hasMoreElements()) {
            JarEntry jarEntry = (JarEntry) enumeration.nextElement()
            InputStream inputStream = null
            try {
                inputStream = file.getInputStream(jarEntry)
            } catch (Exception e) {
                e.printStackTrace()
                return null
            }
            String entryName = jarEntry.getName()
            if (entryName.endsWith(".DSA") || entryName.endsWith(".SF")) {
                //ignore
            } else {
                String className
                JarEntry jarEntry2 = new JarEntry(entryName)
                jarOutputStream.putNextEntry(jarEntry2)

                byte[] modifiedClassBytes = null
                byte[] sourceClassBytes = IOUtils.toByteArray(inputStream)
                if (entryName.endsWith(".class")) {

                    className = entryName.replace(Matcher.quoteReplacement(File.separator), ".")
                            .replace(".class", "")

                    if (isShouldModify(className)) {
                        // modifyClass 修改 .class 文件
                        modifiedClassBytes = modifyClass(sourceClassBytes)
                    }
                }
                // 进行打包
                if (modifiedClassBytes == null) {
                    modifiedClassBytes = sourceClassBytes
                }
                jarOutputStream.write(modifiedClassBytes)
                jarOutputStream.closeEntry()
            }
        }
        jarOutputStream.close()
        file.close()
        return outputJar
    }

/**
 * 1. 获取 .class 文件对应的 className ==> 包名 , 类名
 * 2. 获取 .class 文件字节数组
 * 3. 调用 modifyClass 方法进行修改
 * 4. 修改后的byte 数组 生成 .class 文件
 * @param srcClass
 * @return 字节数组* @throws IOException
 */
    private static byte[] modifyClass(byte[] srcClass) throws IOException {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        ClassVisitor classVisitor = new MkAnalyticsClassVisitor(classWriter)
        ClassReader cr = new ClassReader(srcClass)
        cr.accept(classVisitor, ClassReader.SKIP_FRAMES)
        return classWriter.toByteArray()
    }

    /**
     * 判断是否需要修改
     * @param className 类对象
     * @return boolean
     */
    protected static boolean isShouldModify(String className) {
        Iterator<String> iterator = exclude.iterator()
        while (iterator.hasNext()) {
            String packageName = iterator.next()
            // 提高编译效率
            if (className.startsWith(packageName)) {

                return false
            }
        }

        // 过滤.class文件3: R.class 及其子类
        if (className.contains('R$') ||
                // 过滤.class文件4: R2.class 及其子类
                className.contains('R2$') ||
                className.contains('R.class') ||
                className.contains('R2.class') ||
                // 过滤.class文件5: BuildConfig.class
                className.contains('BuildConfig.class')) {
            return false
        }

        return true
    }

    /**
     * 修改 .class文件
     * @param dir 文件夹
     * @param classFile 文件
     * @param tempDir 备份文件夹
     * @return 文件对象
     */
    static File modifyClassFile(File dir, File classFile, File tempDir) {
        File modified = null
        try {
            String className = path2ClassName(classFile.absolutePath.replace(dir.absolutePath + File.separator, ""))
            byte[] sourceClassBytes = IOUtils.toByteArray(new FileInputStream(classFile))
            byte[] modifiedClassBytes = modifyClass(sourceClassBytes)
            if (modifiedClassBytes) {
                modified = new File(tempDir, className.replace('.', '') + '.class')
                if (modified.exists()) {
                    modified.delete()
                }
                modified.createNewFile()
                new FileOutputStream(modified).write(modifiedClassBytes)
            }
        } catch (Exception e) {
            e.printStackTrace()
            modified = classFile
        }
        return modified
    }

    static String path2ClassName(String pathName) {
        pathName.replace(File.separator, ".").replace(".class", "")
    }
}