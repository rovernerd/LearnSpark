## 简介

最近在学习SparkSql的源码,对sql的词法、语法分析使用的为ANTLR，因此需要对ANTLR进行了解.

### ANTLR

ANTLR（AnotherToolforLanguageRecognition）是目前非常活跃的语法生成工具，用Java语言编写，基于LL（*）解析方式，使用自上而下的递归下降分析方法。

### 应用

ANTLR的应用非常广泛，Hibernate与WebLogic都使用ANTLR解析HQL语言，NetBeansIDE中基于ANTLR解析C++，Twitter搜索模块依赖于ANTLR，Hive、Presto和SparkSQL等大数据引擎的SQL编译模块也都是基于ANTLR构建的。这样看来，ANTLR还是很强大的

## 安装ANTLR

### Java

由于ANTLR是使用Java写的，因此java是必须要安装的，java的安装方式就不再赘述了);

### 下载Jar

我们将ANTLR的jar放在`/usr/local/lib`下，使用curl命令下载jar.

```
cd /usr/local/lib
curl -0 https://www.antlr.org/download/antlr-4.0-complete.jar
```

### 添加CLASSPATH

```
# ANTLR
export CLASSPATH=".:/usr/local/lib/antlr-4.0-complete.jar:$CLASSPATH"
```

### 验证

检查ANTLR是否正确安装，执行`java -jar /usr/local/lib/antlr-4.0-complete.jar`,如下如图信息则代表上述步骤成功了.

![image-20181117165135865](https://ws3.sinaimg.cn/large/006tNbRwly1fxb5qxw5faj315w0iy7gh.jpg)

### 设置别名

如果每次调用ANTLR都是用上面的命令行是非常麻烦的,可以设置上面的shell命令为antlr4

```
alias antlr4='java -jar /usr/local/lib/antlr-4.0-complete.jar'
```

## 开始使用

### HelloWorld

下面我们使用ANTLR,从一个HelloWorld小测试开始.

- 创建名为Hello.g4的文件，输入如下内容:

```ant
grammar Hello;
r : 'hello' ID ;
ID : [a-z]+ ;
WS : [\t\r\n]+ -> skip ;
```

- 执行`antlr4 Hello.g4`,发现目录下多了如下的文件

![image-20181117171627670](https://ws2.sinaimg.cn/large/006tNbRwly1fxb6gtu0iyj30yw08k42k.jpg)

- 执行`javac *.java`编译刚才生成的java文件
- 执行TestRig

> ANTLR在运行库中提供了一个名为TestRig的方便方便的调试工具。它可以详细列出一个语言类应用程序在匹配输入文本过程中的信息，这些输入文本可以来自文件或者标准输入。TestRig使用Java的反射机制来调用编译后的识别程序

设置别名 `alias grun='java org.antlr.v4.runtime.misc.TestRig'`

- 测试识别功能

  首先执行`grun Hello r  -tokens`进入TestRig，然后输入`hello neil`回车，然后`control+D`终止.

![image-20181117173914368](https://ws1.sinaimg.cn/large/006tNbRwly1fxb74htqz3j30uc05qmyr.jpg)

### ANTLR元语言

ANTLR是一个能够生成其他程序的程序



















