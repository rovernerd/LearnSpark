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

ANTLR是一个能够生成其他程序的程序,ANTLR语法本身又遵循了一种专门用来描述其他语言的语法，我们称之为ANTLR元语言。

#### 语法分析

如何理解ANTLR进行语法分析的过程？我们可以将它拆解为两个阶段，词法分析和真正的语法分析

##### 词法分析

第一阶段为词法分析，就好比说我们在阅读一篇英文文章时，并不会按照一个个字母去进行阅读，而是把他们组合为一个个的单词，这个过程就相当于词法分析的过程.

> 将字符聚集为单词或者符号（词法符号，token）的过程称为词法分析（lexicalanalysis）或者词法符号化（tokenizing）。我们把可以将输入文本转换为词法符号的程序称为词法分析器（lexer）。词法分析器可以将相关的词法符号归类，例如INT（整数）、ID（标识符）、FLOAT（浮点数）等。当语法分析器不关心单个符号，而仅关心符号的类型时，词法分析器就需要将词汇符号归类。词法符号包含至少两部分信息：词法符号的类型（从而能够通过类型来识别词法结构）和该词法符号对应的文本。

##### 真正的语法分析

通过第一阶段的词法分析，语句会被识别为一个个词法符号，通过ANTLR的语法分析器会生成一种名为语法分析树或者句法树的数据结构。引用下图来描述语句的整个解析过程.

![image-20181118131158720](https://ws3.sinaimg.cn/large/006tNbRwly1fxc50pwfszj30uc08gmz1.jpg)

为什么使用数结构来描述语法分析的结果，主要有两方面的原因

- 开发者熟知数这种数据结构
- 后续的步骤中便于处理

#### 分析流程

词法分析器处理字符序列并将生成的词法符号提供给语法分析器，语法分析器根据这些信息来检查语法的正确性并建造出一棵语法分析树.这个过程对应的ANTLR类是CharStream、Lexer、Token、Parser，以及ParseTree。连接词法分析器和语法分析器的“管道”就是TokenStream。

### ANTLR组成

ANTLR的jar包中存在两个关键部分:ANTLR工具和ANTLR运行库API(运行时语法分析).当我们讲对一个语法运行ANTLR时,我们指的是运行ANTLR工具，即org.antlr.v4.Tool类来生成一些代码(词法分析器和语法分析器).运行库是一个由若干类和方法组成的库，这些类和方法是自动生成的代码(如Parser、Lexer和Token)运行所必须的.

因此，我们使用ANTLR的一般步骤为:

**首先对一个语法运行ANTLR，然后将生成的代码与jar包中的运行库一起编译，最后将编译好的代码和运行库放在一起运行.**

## 构建语言类应用程序

### 编写语法文件

#### 规则介绍

- 语法文件通常以grammar关键字开头
- 语法名要和语法文件的文件名一致
- 词法分析器的规则必须以大写字母开头,语法分析器的规则必须用小写字母开头
- |代表可选的分支

```
grammar ArrayInit;

init : '{' value (','value)* '}' ; // 必须匹配至少一个value

value : init 
	  | INT
	  ;

INT : [0-9]+ ;
WS  : [\t\r\n]+ -> skip ; 
```

### 程序测试

执行如下的语句

```shell
antlr4 ArrayInit.g4
javac *.java
grun ArrayInit init -tokens
{99,1,12}
EOF
```

可以得到如下的结果

![image-20181119223624634](https://ws4.sinaimg.cn/large/006tNbRwly1fxdqybdq1uj30r009ywix.jpg)

结果解析

- 每一行为一个语法符号,从0开始计数
- 以@1这行为例,@1代表为第2个语法符号，1:2代表第1到2字符,<4>代表类型为INT.

同时可以使用-gui参数执行语法识别 `grun ArrayInit init -gui`,可以得到如下的语法符号

![image-20181119224221171](https://ws2.sinaimg.cn/large/006tNbRwly1fxdr4jy8mtj30dq0fwgm8.jpg)



















































