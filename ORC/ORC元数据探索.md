## 简介

最近在学习ORC这种流行的文件存储格式,本文主要从meta信息这个切入点分析ORC这种文件格式

## 元数据dump工具

从hive0.11开始引入了orcfiledump这个工具,具体的执行命令如下

```shell
// Hive version 1.3.0 and later:
hive --orcfiledump [-j] [-p] [-d] [-t] [--rowindex <col_ids>] [--recover] [--skip-dump] 
    [--backup-path <new-path>] <location-of-orc-file-or-directory>
```

关于可选参数的含义,可以执行`hive --orcfiledump --help`去查看，此处不再赘述。

## 探索

### 数据准备

```sql
-- 创建表
create table tmp.orc_test
(id int,
 name string,
 weight decimal(5,2)
);
-- 插入数据
insert into tmp.orc_test
select 1,'a',50.7
union all
select 2,'b',82.4
union all
select 3,'c',80.6;
```

要查看orc文件的meta信息，首先要知道文件所在的hdfs地址,这个我们可以执行`show create table tmp.orc_test`拿到.

### 执行效果

我们执行如下语句`hive --orcfiledump <hdfs location>`,可以看到下面的信息:

![image-20181125134530958](https://ws3.sinaimg.cn/large/006tNbRwly1fxk9bshf0ij31ek0u07nq.jpg)

### 结果分析

由于测试表的数据量过少，因此只有一个数据文件`000000_0`,因此展示的其实为这个文件的meta信息，下面我们来一一分析上面的结果

#### File Version

`File Version:0.12 with HIVE_13083`

先看官网的解释

> The version stored in the Postscript is the lowest version of Hive that is guaranteed to be able to read the file and it stored as a sequence of the major and minor version. This file version is encoded as [0,12].

我所理解就是可以读取此文件的hive最低版本为0.12，涉及的hive jira为HIVE_13083

#### Rows

`Rows: 3`

这个很好理解，就是文件行数为3行

#### Compression

这里记录的compression内容主要有两部分

- `Compression:ZLIB`

我们知道orc文件有常见的几种压缩方式(eg. none, zlib, or snappy)，这里采用的是zlib

- `Compression size: 262144`

这个size指的是compression chunk的最大size，262144byte即为256k

#### Schema

`Type: struct<id:int,name:string,weight:decimal(5,2)`>

这个Type对应的是测试表的DDL，代表了orc存储的格式.需要注意的是orc文件中的所有行必须是同样的schema,在逻辑上这个schema是一个树形结构.对于STRUCT、MAP这种复杂结构，他们是存在子节点的.

![ORC column structure](https://orc.apache.org/img/TreeWriters.png)

上图显示的type tree，对应的就是这样的schema

```sql
create table Foobar (
 myInt int,
 myMap map<string,
 struct<myString : string,
 myDouble: double>>,
 myTime timestamp
);
```



#### Stripe Statistics

![image-20181125145814758](https://ws2.sinaimg.cn/large/006tNbRwly1fxkbfejp2sj315806agn8.jpg)

这个部分为stripe的统计信息，由于文件较小，只存在一个stripe.要看懂上面的统计信息，首先要结合schema来看.

```json
// json格式schema
{
      "columnId": 0,
      "columnType": "STRUCT",
      "childColumnNames": [
        "id",
        "name",
        "weight"
      ],
      "childColumnIds": [
        1,
        2,
        3
      ]
    },
    {
      "columnId": 1,
      "columnType": "INT"
    },
    {
      "columnId": 2,
      "columnType": "STRING"
    },
    {
      "columnId": 3,
      "columnType": "DECIMAL",
      "precision": 5,
      "scale": 2
    }
```

root column的序号为0.同时拥有3个childcolumn,分别为id,name,weight，对应的序号为1,2,3.

这时我们再来看stripe的统计信息

对于所有的字段,统计信息中均有count、hasNull统计，count很好理解就是个数,hasNull这个flag是很重要的,**它用于谓词下推这样的场景**.

其实orc针对不同的字段的类型，会统计不同的信息，我们一一来看

-  integer types(tinyint, smallint, int, bigint)

id字段就为这样的类型，主要统计了min、max、sum,sum为值的累加.统计这样的信息在谓词下推以及聚合场景下均有应用

- strings types

name字段为这样的类型，主要统计了min、max、sum,sum为string长度(length)的累加.统计这样的信息在谓词下推以及聚合场景下均有应用

- Decimal types

weight为此种类型,主要统计了min、max、sum,和integer类似

#### File Statistics

![image-20181125160036938](https://ws1.sinaimg.cn/large/006tNbRwly1fxkd8f830xj30yy05gjsw.jpg)

我们知道orc的file是由stripes组成的,这里由于只有一个stripe组成的.大文件的File Statistics是基于Stripe Statistics的汇总。

#### Stripes

![image-20181125160147625](https://ws4.sinaimg.cn/large/006tNbRwly1fxkd9jp6coj30w00eowmp.jpg)



此处要对照orc文件的物理存储去理解这些统计信息

![ORC file structure](https://ws2.sinaimg.cn/large/006tNbRwgy1fxh2ucyou4j30g40frwho.jpg)

如图所示,单个stripes是由3部分数据组成的,即Index Data、Row Data、Stripes Footer.

对应`Stripe: offset: 3 data: 32 rows: 3 tail: 59 index: 100`这行信息，含义分别如下

- offset:3

  strpes在文件中的开始位置

- Data:32

  数据本身占用的字节数

- Rows:3

  数据行数

- tail:59

  footer占用的字节数

- index:100

  索引数据占用的字节数

##### Stream

理解这些之后再看stream部分

![image-20181125162158461](https://ws1.sinaimg.cn/large/006tNbRwly1fxkdujsppej30ws08ogqq.jpg)

row_index部分很好理解，即字段的index开始和长度

data这部分,column2和3比较特殊,分别有一个LENGTH和SECONDARY的section

- LENTGH存储String类型的长度.

- SECONDARY存储Decimal、timestamp类型的小数或者纳秒数

##### Encodings

![image-20181125162952170](https://ws1.sinaimg.cn/large/006tNbRwly1fxke2rq1rrj30r60420tv.jpg)

这里存储了字段的编码信息，和orc文件的高压缩比密切相关，后面会专门分析相关的内容，先挖个坑

#### File length

`File length: 414 bytes`

很好理解，就是file占用的字节数

#### Padding

`Padding length: 0 bytes`

`Padding ratio: 0%`

padding这个设计是为了防止跨block读写文件，允许创建小的stripes

举例说明:

按照默认的设置,hdfs的block size为256m,stripes size为64m,一个block中分为4个stripes,但是这只是理想的情况，hive中有参数`hive.exec.orc.block.padding.tolerance`，默认值为0.05.代表可以容忍3.2m大小的small stripes填充到单个block,这种策略可以避免跨block的读写操作.

由于我们的测试数据太小，值就为0了 :astonished:

### 后记

本文只是简单梳理了ORC的meta信息，但是ORC的reader怎么利用这些信息优化sql query、orc高压缩比的秘密、orc的config调优、orcV2的新特性等等还是需要继续梳理的，后面慢慢补坑了 :smile:

### 参考链接

- [Hive Configuration Properties](https://cwiki.apache.org/confluence/display/Hive/Configuration+Properties#ConfigurationProperties-ORCFileFormat)

- [ORC Specification v1](https://orc.apache.org/specification/ORCv1/)