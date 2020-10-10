> 阅读本篇可能需要的预备知识 [《ASM4 使用手册(中文版)》](https://www.yuque.com/mikaelzero/asm),本文涉及代码已经上传[Github](https://github.com/MicroKibaco/auto_track_transform),欢迎star一波~

### 《ASM 字节码插桩》大纲
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/15cb20725649447c8cb08f61af3b4313~tplv-k3u1fbpfcp-watermark.image)

### 背景和疑问

&emsp;&emsp;在 Android 中,你可能经常听某位中台大佬说 无痕埋点 , Hook ,apm监控,编译器动态修改代码等名词,小伙伴通常都知道 AspectJ 可以通过切面织入相关代码,但殊不知 就连小小的 Lambada 语法在自定义 Plugin 都无法实现。</br></br>&emsp;&emsp;更何况其他兼容问题,有没有一个相对完美的选择,实现全埋点呢?有没有最优质的技术选择向应用程序中插入调试或性能监视代码同时保证应用程序的运行速度。</br></br>&emsp;&emsp;好吧,不拐外抹角啦,今天就带大家详细聊聊一款轻量级AOP设计ASM。


## 1.0 关键技术
&emsp;&emsp;了解 ASM 之前 首先得了解 APP 的打包流程,这里推荐大家看一下邓凡平的 [《深入理解Android虚拟机》](https://item.jd.com/12510921.html),Android应用打包流程大概分为以下7个阶段:
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/4d48bf4e88dd4e1499d83a21f987f61a~tplv-k3u1fbpfcp-watermark.image)
   - 1. aapt 打包资源文件 阶段
   - 2. aidl 转 java 文件 阶段
   - 3. Java 编译（Compilers）生成.class文件 阶段
   - 4. dex（生成dex文件）阶段
   - 5. apkbuilder（生成未签名apk）阶段
   - 6. Jarsigner（签名）阶段
   - 7. zipalign（对齐） 阶段
   
&emsp;&emsp;具体每个阶段做了哪些工作,可以参考[浅谈Android打包流程](https://juejin.im/post/6844903850453762055#heading-6)一文,我们只需要关注第四阶段 生成.dex之前的工作即可,因为在这个阶段我们能拦截到所有的.class文件,然后借助插件,就可以遍历.class文件的所有方法,再根据一定的条件找到需要的目标方法,最后修改并保存,就可以插入我们需要的代码了。</br></br>&emsp;&emsp;那么我们所说的插件到底是什么呢?在Google从 Android Gradle 1.5 开始就提供了 Transform Api,通过Transform Api,允许三方 Plugin形式,在应用程序打包生成 .dex 前编译过程中操作 .class 文件。</br></br>&emsp;&emsp;我们要做的工作就是 自定义 Transform, 迭代 .class 文件所有的方法,然后修改在特定的listener中插入埋点代码,最后对源文件替换,达到织入代码的目的,那么Transform到是什么呢?
## 1.1 Gradle Transform
&emsp;&emsp;回到什么一个问题,什么是Transform?Google官方文档是这么翻译的
> A transform receives input as a collection **TransformInput**, which is composed of JarInputs and DirectoryInputs. Both provide information about the QualifiedContent.Scopes and QualifiedContent.ContentTypes associated with their particular content.The output is handled by **TransformOutputProvider** which allows creating new self-contained content, each associated with their own Scopes and Content Types. The content handled by **TransformInput/Output** is managed by the transform system, and their location is not configurable.It is best practice to write into as many outputs as Jar/Folder Inputs have been received by the transform. Combining all the inputs into a single output prevents downstream transform from processing limited scopes.

&emsp;&emsp;简单理解就是: 用来修改 .class 文件的一套标准 API,目的是把 .class 文件 转换成目标 字节码文件,其实你可以简单的把它和文件输入流和输出流类比起来,只不过IO体系里面操作的是流对象而Transform操作的是文件对象。</br></br>&emsp;&emsp;记住两个核心的API:TransformInput 和 TransformOutputProvider;TransformInput代表的是输入文件抽象接口,它有两个比较重要的方法

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/edb250ff871346369dc358b2b52d736f~tplv-k3u1fbpfcp-watermark.image)

&emsp;&emsp;获取DirectoryInput集合和获取JarInput集合,其中: DirectoryInput是以源码方式参与项目编译所有目录结构及其目录下的源文件;而JarInput是以jar包方式参与项目编译的所有jar包和远程包。</br></br>&emsp;&emsp;TransformOutputProvider代表的是输出文件抽象接口,里面有一个核心接口方法getContentLocation主要是获取输出路径信息的

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/728e26894863412ea7f7d01420bd57c6~tplv-k3u1fbpfcp-watermark.image)
&emsp;&emsp;getContentLocation方法有几个比较重要的参数name,type,scopes和format,其中name代表该 Transform 对应的 Task 的名称。
 
 ![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/e49d5f553e7141458fbebb5d70a4e19e~tplv-k3u1fbpfcp-watermark.image)
 
&emsp;&emsp;QualifiedContent.ContentType代表的是Transform需要处理的数据类型;里面有两个默认枚举参数: CLASSES 和 PESOURCES。
![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/925c9ba906e84ecc8f3374fe03d17d58~tplv-k3u1fbpfcp-watermark.image)
&emsp;&emsp;CLASSES 代表 需要处理编译后的字节码,可能是jar 也可能是 目录。PESOURCES代表处理标准的 java 资源,scopes 也是一个比较有意思的枚举类![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7d315bf802d24a15af4b3924d27fc65c~tplv-k3u1fbpfcp-watermark.image),用来指定 Transform 的作用域,其中有七个枚举对象:PROJECT,SUB_PROJECTS,PROJECT_LOCALDEPS,SUB_PROJECTS_LOCAL_OEPS,EXTERNAL_LIBRARIES,PROVIDED_ONLY和TESTED_CODE。</br></br>&emsp;&emsp;PROJECT只处理当前项目,SUB_PROJECTS,只处理子项目,PROJECT_LOCALDEPS只处理当前项目的本地依赖,例如: jar , arr。SUB_PROJECTS_LOCAL_OEPS只处理子项目的本地依赖。例如: jar , arr,EXTERNAL_LIBRARIES只处理外包的依赖库,PROVIDED_ONLY只处理本地或远程以 provided 形式引入的依赖库。而TESTED_CODE指的是测试代码。format 是用来格式化内容的。</br></br>&emsp;&emsp;至此TransformOutputProvider就介绍完了。纸上得来终觉浅，绝知此事要躬行。我们简单实现一下Transform吧

#### 第一步: 新建一个 Project
里面自动生成一个主 moudle,即: app
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/1fc3273a789d4648be3663bc479dd8b2~tplv-k3u1fbpfcp-watermark.image)
#### 第二步: 新建一个命名为 plugin 的 moudle

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/59a785b9c9e1423484040ed76869ee4e~tplv-k3u1fbpfcp-watermark.image)

#### 第三步: 清空 plugin/build.gradle 文件的内容,然后修改里面的内容

![](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/3ef23a01e1ca40dd8a0c4bb3de491f94~tplv-k3u1fbpfcp-watermark.image)


#### 第四步: 删除 plugin.src/main 目录下所有的文件
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/b4969c0ed7c3468ca5650b6b5582fed0~tplv-k3u1fbpfcp-watermark.image)

#### 第五步: 新建 groovy 目录
![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/b52e74e9d2524002a3a0eb05a4d48b18~tplv-k3u1fbpfcp-watermark.image)

#### 第六步: 创建 Transform 类
![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/90d20b09d7324683895654c51a5b4028~tplv-k3u1fbpfcp-watermark.image)

#### 第七步: 新建 Plugin,创建 .properties

![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7810680f09084b7a8ddac33e78460af3~tplv-k3u1fbpfcp-watermark.image)

#### 第八步: 执行 plugin 的 uploadArchives 任务构建 plugin
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/f0fa35e1499d4ba4ae7a139cdf7c8e19~tplv-k3u1fbpfcp-watermark.image)

#### 第九步: 修改项目根目录下的 buid.gradle 文件,添加对插件的依赖

![](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/39d5a9d62b7b4f87bdc64a84b9e50c79~tplv-k3u1fbpfcp-watermark.image)

#### 第十步: 在 app/build.gradle 文件声明使用插件

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/c80876c07af542edafc4fb25236f923a~tplv-k3u1fbpfcp-watermark.image)

#### 第十一步: 构建应用程序
<br/>&emsp;&emsp;至此,一个简单的 Gradle Transform 的实例 就已经完成了

## 1.2 ASM 基础知识

如果说Transform只是前菜,那么今天的主菜应该是ASM,什么是ASM呢?官方是这么解释的。

> ASM is an all purpose Java bytecode manipulation and analysis framework. It can be used to modify existing classes or to dynamically generate classes, directly in binary form. ASM provides some common bytecode transformations and analysis algorithms from which custom complex transformations and code analysis tools can be built. ASM offers similar functionality as other Java bytecode frameworks, but is focused on performance. Because it was designed and implemented to be as small and as fast as possible, it is well suited for use in dynamic systems (but can of course be used in a static way too, e.g. in compilers).

 &emsp;&emsp;ASM其实是一个功能比较齐全的 Java 字节码操作和分析框架,通过ASM,我们可以动态生成类或增强类的既有类的功能,ASM可以直接生成二进制的.class文件,也可以被类在加载入 java 虚拟机 </br></br>&emsp;&emsp;之前动态改变现有类的行为,Java 的二进制被存储在严格格式定义的 .class 文件里,这些字节码拥有足够的元数据信息用来表示类的所有元素,包括类的名称,方法,属性以及Java  字节码指令。</br></br>&emsp;&emsp;ASM 从字节码文件读入这些信息后能改变 类的行为,分析类的信息,甚至根据具体要求生成新的类。ASM涉及了五个比较核心的类: ClassReader,ClassWriter,MethodVisitor,ClassVistor和AdiviveAdapter。</br></br>&emsp;&emsp;ClassReader 主要是 用来解析编译过的 .class  字节码文件,ClassWriter 主要是 用来重新构建编译后的类,比如修改类名,属性以及方法,甚至可以生产新的类名字节码。</br></br>&emsp;&emsp;MethodVisitor主要是方法访问类,有几个重要的方法:onMethodEnter,visitEnd和visitAnnotation。onMethodEnter主要是进入方法时插入字节码,visitEnd主要是退出方法前可以退出字节码,visitAnnotation可以在这里通过注解的方式操作字节码。</br></br>&emsp;&emsp;ClassVistor负责 "拜访" 类成员信息 其中包括 在类的注解,类的构造方法,类的字段,类的方法,静态代码块</br>&emsp;&emsp;ClassVistor 有几个比较重要的方法,重点需要了解的有: visit和visitMehtod;
 ![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/9eea9b4ba7084d7e87f66a989b0ae44d~tplv-k3u1fbpfcp-watermark.image)
 visit有6个比较重要的参数: version,access,name,signature,signatureName和interfaces。version代表的是JDK的版本,版本对应表格如下:
 ![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2ca724b52edf49cbafc02817f8c989b5~tplv-k3u1fbpfcp-watermark.image)
 access代表类的修饰符,具体修饰符含义如下:
 ![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/c989fbdbf637435ab50ef3220f6d7de5~tplv-k3u1fbpfcp-watermark.image)
 name代表的是类的名称;signature是泛型信息;signatureName是当前类所继承的父类;interfaces是类所实现的接口列表,在java中,一个类是可以实现多个不同的接口,因此该参数是一个数组类型。下面我们看一下ClassVistor的样例代码:
 
 ```java
 class MkAnalyticsClassVisitor extends ClassVisitor implements Opcodes {
    private final static String SDK_API_CLASS = "com/github/microkibaco/asm_sdk/SensorsDataAutoTrackHelper"

    private String[] mInterfaces
    private ClassVisitor classVisitor

    private HashMap<String, MkAnalyticsMethodCell> mLambdaMethodCells = new HashMap<>()

    MkAnalyticsClassVisitor(final ClassVisitor classVisitor) {
        super(Opcodes.ASM6, classVisitor)
        this.classVisitor = classVisitor
    }

    private
    static void visitMethodWithLoadedParams(MethodVisitor methodVisitor, int opcode, String owner, String methodName, String methodDesc, int start, int count, List<Integer> paramOpcodes) {
        for (int i = start; i < start + count; i++) {
            methodVisitor.visitVarInsn(paramOpcodes[i - start], i)
        }
        methodVisitor.visitMethodInsn(opcode, owner, methodName, methodDesc, false)
    }

    /**
     *  visit 可以拿到关于 .class 的所有信息,比如: 当前类所实现的接口列表
     * @param version JDK的版本
     * @param access 类的修饰符
     * @param name 类的名称
     * @param signature 当前类所继承的父类
     * @param superName
     * @param interfaces 类所实现的接口列表,在java中,一个类是可以实现多个不同的接口,因此该参数是一个数组类型
     */
    @Override
    void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces)
        mInterfaces = interfaces
    }

    /**
     * 可以拿到关于 method所有的信息,比如: 方法名 方法的参数描述
     * @param access 类的修饰符
     * @param name 类的名称
     * @param desc 方法签名
     * @param signature 类签名
     * @param exceptions 异常信息
     * @return MethodVisitor
     */
    @Override
    MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions)

        return methodVisitor
    }

    /**
     * 获取方法参数下标为 index 的对应 ASM index
     * @param types 方法参数类型数组
     * @param index 方法中参数下标，从 0 开始
     * @param isStaticMethod 该方法是否为静态方法
     * @return 访问该方法的 index 位参数的 ASM index
     */
    int getVisitPosition(Type[] types, int index, boolean isStaticMethod) {
        if (types == null || index < 0 || index >= types.length) {
            throw new Error("getVisitPosition error")
        }
        if (index == 0) {
            return isStaticMethod ? 0 : 1
        } else {
            return getVisitPosition(types, index - 1, isStaticMethod) + types[index - 1].getSize()
        }
    }
}
 ```

上面的 visitMethod中,可以对特定的方法镜像修改,修改方法的时候需要用到"拜访"方法所以信息 MethodVistitor

 ```java
 methodVisitor = new MkAnalyticsDefaultMethodVisitor(methodVisitor, access, name, desc) {
            boolean isSensorsDataTrackViewOnClickAnnotation = false

            /**
             * 退出方法前可以退出字节码
             */
            @Override
            void visitEnd() {
                super.visitEnd()

                if (mLambdaMethodCells.containsKey(nameDesc)) {
                    mLambdaMethodCells.remove(nameDesc)
                }
            }

            /**
             * 进入方法时插入字节码
             */
            @Override
            protected void onMethodEnter() {
                super.onMethodEnter()
                }
/**
 * 可以在这里通过注解的方式操作字节码
 * @param s 访问的注解名
 * @param b 是否方法
 * @return  methodVisitor
 */
            @Override
            groovyjarjarasm.asm.AnnotationVisitor visitAnnotation(String s, boolean b) {
                if (s == 'Lcom/sensorsdata/analytics/android/sdk/SensorsDataTrackViewOnClick;') {
                    isSensorsDataTrackViewOnClickAnnotation = true
                }

                return super.visitAnnotation(s, b)
            }
        }
 ```
visitMehtod 方法也有几个比较重要的参数: access,name,desc,signature和exceptions。access代表的是方法的修饰符,具体修饰符含义如下:
![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7d90b147ed4c436cb99d852150dc16cc~tplv-k3u1fbpfcp-watermark.image)

name表示方法名;desc表示方法签名,具体符号类型如下:
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/9c4a08cfb6184f2787c85c80e5d9fdda~tplv-k3u1fbpfcp-watermark.image)
方法参数列表对应的方法签名如下:
![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/b229168530ce42e5b2cb4ea932c93677~tplv-k3u1fbpfcp-watermark.image)
signature表示泛型相关信息;exceptions表示将会抛出异常,如果方法不会抛出异常,该参数为空。最后我们看一下AdiviveAdapter,它实现 MethodVistor 接口,主要负责 "拜访" 方法的信息,用来进行具体方法


 ```java
class MkAnalyticsDefaultMethodVisitor extends AdviceAdapter {

    MkAnalyticsDefaultMethodVisitor(MethodVisitor mv, int access, String name, String desc) {
        super(Opcodes.ASM6, mv, access, name, desc)
    }

    /**
     * 表示 ASM 开始扫描这个方法
     */
    @Override
    void visitCode() {
        super.visitCode()
    }

    @Override
    void visitMethodInsn(int opcode, String owner, String name, String desc) {
        super.visitMethodInsn(opcode, owner, name, desc)
    }

    @Override
    void visitAttribute(Attribute attribute) {
        super.visitAttribute(attribute)
    }

    /**
     * 表示方法输出完毕
     */
    @Override
    void visitEnd() {
        super.visitEnd()
    }

    @Override
    void visitFieldInsn(int opcode, String owner, String name, String desc) {
        super.visitFieldInsn(opcode, owner, name, desc)
    }

    @Override
    void visitIincInsn(int var, int increment) {
        super.visitIincInsn(var, increment)
    }

    @Override
    void visitIntInsn(int i, int i1) {
        super.visitIntInsn(i, i1)
    }

    /**
     * 该方法是 visitEnd 之前调用的方法，可以反复调用。用以确定类方法在执行时候的堆栈大小。
     * @param maxStack
     * @param maxLocals
     */
    @Override
    void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack, maxLocals)
    }

    @Override
    void visitVarInsn(int opcode, int var) {
        super.visitVarInsn(opcode, var)
    }

    @Override
    void visitJumpInsn(int opcode, Label label) {
        super.visitJumpInsn(opcode, label)
    }

    @Override
    void visitLookupSwitchInsn(Label label, int[] ints, Label[] labels) {
        super.visitLookupSwitchInsn(label, ints, labels)
    }

    @Override
    void visitMultiANewArrayInsn(String s, int i) {
        super.visitMultiANewArrayInsn(s, i)
    }

    @Override
    void visitTableSwitchInsn(int i, int i1, Label label, Label[] labels) {
        super.visitTableSwitchInsn(i, i1, label, labels)
    }

    @Override
    void visitTryCatchBlock(Label label, Label label1, Label label2, String s) {
        super.visitTryCatchBlock(label, label1, label2, s)
    }

    @Override
    void visitTypeInsn(int opcode, String s) {
        super.visitTypeInsn(opcode, s)
    }

    @Override
    void visitLocalVariable(String s, String s1, String s2, Label label, Label label1, int i) {
        super.visitLocalVariable(s, s1, s2, label, label1, i)
    }

    @Override
    void visitInsn(int opcode) {
        super.visitInsn(opcode)
    }

    @Override
    AnnotationVisitor visitAnnotation(String s, boolean b) {
        return super.visitAnnotation(s, b)
    }

    @Override
    protected void onMethodEnter() {
        super.onMethodEnter()
    }

    /**
     * 使用 onMethodExit 这样就不会影响到应用程序原有点击事件的响应速度
     * @param opcode
     */
    @Override
    protected void onMethodExit(int opcode) {
        super.onMethodExit(opcode)
    }
}
```

但是我在使用过程遇到了这个坑,具体原因还要审核一下Plugin是否导包有误
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/81a8bc89daad4ab4992fd0f381c2ad35~tplv-k3u1fbpfcp-watermark.image)

## 1.3 ASM 原理

刚刚长篇大论说了ASM的使用以及简单API介绍,那么ASM实施过程是怎样的呢,主要是分为三个步骤
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/0d2f0a06ecf64d07810ffdee6907ebd8~tplv-k3u1fbpfcp-watermark.image)

- 步骤一: 定义一个 Gradle Plugin 。 然后注册一个 Transform 对象。 在 transform 方法里,可以分别遍历 目录 和 jar 包
- 步骤二: 遍历当前应用程序所有的 .class文件,就可以找到满足特定条件的.class 文件和相关方法
- 步骤三: 修改相应方法以动态插入字节码
## 1.4 ASM 埋点方案
下面以自动采集Android 的 Button 空间的点击事件为例 ,纤细介绍该方案的实现步骤。对于其他控件点击事件有空再补充

### 第一步:  新建一个 Project
![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/c5edadd65e8042818d34d5ca36f1e310~tplv-k3u1fbpfcp-watermark.image)
### 第二步:  创建 sdk module

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/596aad62f5244d3d8869bcd8e34dd399~tplv-k3u1fbpfcp-watermark.image)

### 第三步:  编写埋点SDK

 ```java
/**
 * @author 杨正友(小木箱)于 2020/10/9 20 19 创建
 * @Email: yzy569015640@gmail.com
 * @Tel: 18390833563
 * @function description:
 */
@Keep
public class SensorsDataAPI {
    private final String TAG = this.getClass().getSimpleName();
    public static final String SDK_VERSION = "1.0.0";
    private static SensorsDataAPI INSTANCE;
    private static final Object  LOCK = new Object();
    private static Map<String, Object> mDeviceInfo;
    private String mDeviceId;

    @Keep
    @SuppressWarnings("UnusedReturnValue")
    public static SensorsDataAPI init(Application application) {
        synchronized (LOCK) {
            if (null == INSTANCE) {
                INSTANCE = new SensorsDataAPI(application);
            }
            return INSTANCE;
        }
    }

    @Keep
    public static SensorsDataAPI getInstance() {
        return INSTANCE;
    }

    private SensorsDataAPI(Application application) {
        mDeviceId = SensorsDataPrivate.getAndroidID(application.getApplicationContext());
        mDeviceInfo = SensorsDataPrivate.getDeviceInfo(application.getApplicationContext());
    }

    /**
     * Track 事件
     *
     * @param eventName  String 事件名称
     * @param properties JSONObject 事件属性
     */
    @Keep
    public void track(@NonNull final String eventName, @Nullable JSONObject properties) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("event", eventName);
            jsonObject.put("device_id", mDeviceId);

            JSONObject sendProperties = new JSONObject(mDeviceInfo);

            if (properties != null) {
                SensorsDataPrivate.mergeJSONObject(properties, sendProperties);
            }

            jsonObject.put("properties", sendProperties);
            jsonObject.put("time", System.currentTimeMillis());

            Log.i(TAG, SensorsDataPrivate.formatJson(jsonObject.toString()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
 ```

### 第四步:  在sdk module里新建 AutoTrackHelper.java 工具类

我们新增trackViewOnClick(View view),主要是ASM插入埋点代码
 ```java
    /**
     * View 被点击，自动埋点
     *
     * @param view View
     */
    @Keep
    public static void trackViewOnClick(View view) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("$element_type", SensorsDataPrivate.getElementType(view));
            jsonObject.put("$element_id", SensorsDataPrivate.getViewId(view));
            jsonObject.put("$element_content", SensorsDataPrivate.getElementContent(view));

            Activity activity = SensorsDataPrivate.getActivityFromView(view);
            if (activity != null) {
                jsonObject.put("$activity", activity.getClass().getCanonicalName());
            }

            SensorsDataAPI.getInstance().track("$AppClick", jsonObject);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
 ```
 
### 第五步:  添加依赖关系

![](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/ac0018e98790474bb1cc03d03d0bffc8~tplv-k3u1fbpfcp-watermark.image)

### 第六步:  初始化埋点SDK

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/8bf45be95f344d9195d8cb3b2033a5ba~tplv-k3u1fbpfcp-watermark.image)
### 第七步:  声明自定义的Application

![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/b241a4bf10aa48b0a87cc20565519f56~tplv-k3u1fbpfcp-watermark.image)

### 第八步:  新建一个Android Lib 叫做 Plugin

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2db07812a4af4b3ea4258b8f337c9299~tplv-k3u1fbpfcp-watermark.image)

### 第九步:  清空 build.gradle 文件的内容,然后修改如下内容

![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/8aca5b9e2cc64335a0b0ad7b34c5cc99~tplv-k3u1fbpfcp-watermark.image)

### 第十步:  创建 groovy 目录

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2db07812a4af4b3ea4258b8f337c9299~tplv-k3u1fbpfcp-watermark.image)

### 第十一步: 新建 Transform 目录

 ```java
 /**
 * @author 杨正友(小木箱)于 2020/10/9 22 08 创建
 * @Email: yzy569015640@gmail.com
 * @Tel: 18390833563
 * @function description:
 */
class MkAnalyticsTransform extends Transform {
    private static Project project
    private MkAnalyticsExtension sensorsAnalyticsExtension

    MkAnalyticsTransform(Project project, MkAnalyticsExtension sensorsAnalyticsExtension) {
        this.project = project
        this.sensorsAnalyticsExtension = sensorsAnalyticsExtension
    }

    @Override
    String getName() {
        return "MkAnalytics"
    }

    /**
     * 需要处理的数据类型，有两种枚举类型
     * CLASSES 代表处理的 java 的 class 文件，RESOURCES 代表要处理 java 的资源
     * @return
     */
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    /**
     * 指 Transform 要操作内容的范围，官方文档 Scope 有 7 种类型：
     * 1. EXTERNAL_LIBRARIES        只有外部库
     * 2. PROJECT                   只有项目内容
     * 3. PROJECT_LOCAL_DEPS        只有项目的本地依赖(本地jar)
     * 4. PROVIDED_ONLY             只提供本地或远程依赖项
     * 5. SUB_PROJECTS              只有子项目。
     * 6. SUB_PROJECTS_LOCAL_DEPS   只有子项目的本地依赖项(本地jar)。
     * 7. TESTED_CODE               由当前变量(包括依赖项)测试的代码
     * @return
     */
    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        _transform(transformInvocation.context, transformInvocation.inputs, transformInvocation.outputProvider, transformInvocation.incremental)
    }

    void _transform(Context context, Collection<TransformInput> inputs, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException, TransformException, InterruptedException {
        if (!incremental) {
            outputProvider.deleteAll()
        }

        /**Transform 的 inputs 有两种类型，一种是目录，一种是 jar 包，要分开遍历 */
        inputs.each { TransformInput input ->
            /**遍历目录*/
            input.directoryInputs.each { DirectoryInput directoryInput ->
                /**当前这个 Transform 输出目录*/
                File dest = outputProvider.getContentLocation(directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
                File dir = directoryInput.file

                if (dir) {
                    HashMap<String, File> modifyMap = new HashMap<>()
                    /**遍历以某一扩展名结尾的文件*/

                    dir.traverse(type: FileType.FILES, nameFilter: ~/.*\.class/) {
                        File classFile ->
                            if (MkAnalyticsClassModifier.isShouldModify(classFile.name)) {
                                File modified = null
                                if (!sensorsAnalyticsExtension.disableAppClick) {
                                    modified = MkAnalyticsClassModifier.modifyClassFile(dir, classFile, context.getTemporaryDir())
                                }
                                if (modified != null) {
                                    /**key 为包名 + 类名，如：/cn/sensorsdata/autotrack/android/app/MainActivity.class*/
                                    String ke = classFile.absolutePath.replace(dir.absolutePath, "")
                                    modifyMap.put(ke, modified)
                                }
                            }
                    }
                    // 将输入目录下的所有 .class 文件 拷贝到输出目录
                    FileUtils.copyDirectory(directoryInput.file, dest)
                    modifyMap.entrySet().each {
                        Map.Entry<String, File> en ->
                            File target = new File(dest.absolutePath + en.getKey())
                            if (target.exists()) {
                                target.delete()
                            }
                            // 将HashMap 中修改过的 .class 文件拷贝到输出目录,覆盖之前拷贝的 .class 文件(原 .class文件)
                            FileUtils.copyFile(en.getValue(), target)
                            en.getValue().delete()
                    }
                }
            }

            /**遍历 jar*/
            input.jarInputs.each { JarInput jarInput ->
                String destName = jarInput.file.name

                /**截取文件路径的 md5 值重命名输出文件,因为可能同名,会覆盖*/
                def hexName = DigestUtils.md5Hex(jarInput.file.absolutePath).substring(0, 8)
                /** 获取 jar 名字*/
                if (destName.endsWith(".jar")) {
                    destName = destName.substring(0, destName.length() - 4)
                }

                /** 获得输出文件*/
                File dest = outputProvider.getContentLocation(destName + "_" + hexName, jarInput.contentTypes, jarInput.scopes, Format.JAR)
                def modifiedJar = null;
                if (!sensorsAnalyticsExtension.disableAppClick) {
                    modifiedJar = MkAnalyticsClassModifier.modifyJar(jarInput.file, context.getTemporaryDir(), true)
                }
                if (modifiedJar == null) {
                    modifiedJar = jarInput.file
                }
                FileUtils.copyFile(modifiedJar, dest)
            }
        }
    }
}

```

MkAnalyticsTransform 继承 Transform 。 在 Transform 里, 会分别遍历 目录和 jar。实现的相关抽象方法,与之前我们实现的 Gradle Transform 样例一致,具体的话可以跳回去看上文介绍。

#### A. 遍历目录

分别遍历目录里面每一个 .class 文件,首先通过MkAnalyticsClassModifier.isShouldModify 方法简单过滤一下肯定不需要的 .class 文件。 isShouldModify 方法实现逻辑比较简单


 ```java
   
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
  ```
  
  比如我们可以简单过滤 如下: .class 文件
  
   - android.supoort包下的文件
   - 我们 SDK 的 .class 文文件
   - R.classs 及其子类
   - R2.class 及其子类(ButterKnife生成)
   - BuildConfig.class
   
   之所以要过滤一些文件,主要是为了提高编译效率。
   
   

#### B. 遍历 jar

![](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/29c6dd53adb945a9bb15bc3c7d183a35~tplv-k3u1fbpfcp-watermark.webp)

### 第十二步:  定义 Plugin
![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/a22e7193271d4f0f872456e64c0a8e8b~tplv-k3u1fbpfcp-watermark.webp)
### 第十三步: 新建properites 文件

![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7b716bb44bf44054b9519d3880f9daea~tplv-k3u1fbpfcp-watermark.webp)
### 第十四步: 构建插件

![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/4fda34b919724e8aa998ac558d54dfd8~tplv-k3u1fbpfcp-watermark.webp)
### 第十五步: 添加对插件的依赖

![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/0408c4e408d94abd9ce87e83fb14133a~tplv-k3u1fbpfcp-watermark.webp)
### 第十六步: 在应用程序使用插件

![](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/3dac29ee806249c686a321634a3b5f86~tplv-k3u1fbpfcp-watermark.webp)
### 第十七步: 构建应用程序

确认app/build/intermediates/transforms/MknalyticeAutoTrack/debug/是否有生成新的 .class 文件 有没有插入新的字节码

## 1.5 ASM 存在的风险点
### 无法采集android:onClick 属性绑定的点击事件

#### 第一步: 新增一个注解 @SensorsDataTrckViewOnClick 
![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/6b01c31601e24b3c84aecc3f05022f1e~tplv-k3u1fbpfcp-watermark.webp)

#### 第二步: visitMethod 注解标记
在前面定义的 MethodVistor 类里, 有一个叫 visitMethod 方法,该方法是扫描到方法注解声明的时进行调用。判断一下当前扫描到的注解是否为我们自定义的注解类型,如果是则做个标记,然后在 visitMethod 判断是否有这个标记,如果有,则埋点字节码。visitAnnotation 的实现如下: 
![](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2e218a3d339c41f9b458d80da62ceabf~tplv-k3u1fbpfcp-watermark.webp)

#### 第三步: visitAnnotation 注解特殊处理

在 visitAnnotation 方法里,我们判断一下当前扫描的注解(即第一个参数 s)是否是我们自定义的 @SensorsDataTrckViewOnClick 注解类型,如果是,就做个标记,即

![](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/92289b88449b4cd7aab9820a2d9fb26d~tplv-k3u1fbpfcp-watermark.webp)

#### 第四步:  SensorsDataTrckViewHelper.tacakViewOnClick(view)  插入字节码
在onMethodExit方法里,如果 isSensorsDataTrackViewOnClickAnnotation 为 true,则说明该方法加了  @SensorsDataTrckViewOnClick 注解。如果被注解的方法有且只有一个View类型的参数,那我们就插入埋点代码,即插入代码SensorsDataTrckViewHelper.tacakViewOnClick(view) 对应的字节码

![](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7b382965469a458abfce1730bf78fe90~tplv-k3u1fbpfcp-watermark.webp)

最后在 android: onClick 属性绑定方法上使用我们自定义的注解标记一下,即 @SensorsDataTrckViewOnClick 

## 1.6 总结

本文 以App打包流程为基石 引入了 Gradle Transform ,并手把手教大家写了一个简单的 Transform Demo,通过 ASM +Gradle Transform 实现了Button全埋点组件,让大家更好理解 ASM 原理,当然该埋点组件存在一些不足,如: 不支持 AlerDialog MenuItem CheckBox SeekBar Spinner RattingBar TabHost ListView GridView 和 ExpendableListView 这个需要大家去扩展。ASM优势不用多说,实际开发中一般可用于大图监测,卡顿时间精准测量,日志上报等等。可以说是完美填补了AspectJ的不足
