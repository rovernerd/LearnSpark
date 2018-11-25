hive的config配置在HiveConf文件中













| Config property                   | Value                                                        | Comment                                                      |
| --------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| hive.default.fileformat           | ORC                                                          |                                                              |
| hive.exec.orc.default.stripe.size | `256*1024*1024` (268,435,456) in 0.13.0;                         `64*1024*1024` (67,108,864) in 0.14.0 |                                                              |
| hive.exec.orc.default.block.size  | `256*1024*1024` (268,435,456)                                |                                                              |
| hive.exec.orc.default.compress    | ZLIB                                                         |                                                              |
| **hive.exec.orc.split.strategy**  | HYBRID                                                       | What strategy [ORC](https://cwiki.apache.org/confluence/display/Hive/LanguageManual+ORC) should use to create splits for execution. The available options are "BI", "ETL" and "HYBRID".The HYBRID mode reads the footers for all files if there are fewer files than expected mapper count, switching over to generating 1 split per file if the average file sizes are smaller than the default HDFS blocksize. ETL strategy always reads the ORC footers before generating splits, while the BI strategy generates per-file splits fast without reading any data from HDFS. |



orc split 

> Currently, ORC generates splits based on stripe offset + stripe length.
>
> This means that the splits for all columnar projections are exactly the same size, despite reading the footer which gives the estimated sizes for each column.
>
> This is a hold-out from FileSplit which uses getLen() as the I/O cost of reading a file in a map-task.
>
> RCFile didn't have a footer with column statistics information, but for ORC this would be extremely useful to reduce task overheads when processing extremely wide tables with highly selective column projections.

https://issues.apache.org/jira/browse/HIVE-7428

**https://issues.apache.org/jira/browse/HIVE-10114**



















![ORC file structure](https://ws2.sinaimg.cn/large/006tNbRwgy1fxh2ucyou4j30g40frwho.jpg)





ORC特性

ORC files include light weight indexes that include the minimum and maximum values for each column in each set of 10,000 rows and the entire file. Using pushdown filters from Hive, the file reader can skip entire sets of rows that aren’t important for this query.









#### Alter Table/Partition Concatenate

Version information



In Hive release [0.8.0](https://issues.apache.org/jira/browse/HIVE-1950) RCFile added support for fast block level merging of small RCFiles using concatenate command. In Hive release [0.14.0](https://issues.apache.org/jira/browse/HIVE-7509) ORC files added support fast stripe level merging of small ORC files using concatenate command.

```
`ALTER TABLE table_name [PARTITION (partition_key = 'partition_value' [, ...])] CONCATENATE;`
```

If the table or partition contains many small RCFiles or ORC files, then the above command will merge them into larger files. In case of RCFile the merge happens at block level whereas for ORC files the merge happens at stripe level thereby avoiding the overhead of decompressing and decoding the data.



### Column Statistics

The goal of the column statistics is that for each column, the writer records the count and depending on the type other useful fields. For most of the primitive types, it records the minimum and maximum values; and for numeric types it additionally stores the sum. From Hive 1.1.0 onwards, the column statistics will also record if there are any null values within the row group by setting the hasNull flag. The hasNull flag is used by ORC’s predicate pushdown to better answer ‘IS NULL’ queries.



For integer types (tinyint, smallint, int, bigint), the column statistics includes the minimum, maximum, and sum. **If the sum overflows long at any point during the calculation, no sum is recorded.**





### ORC文件分析工具

```shell
// Hive version 1.3.0 and later:
hive --orcfiledump [-j] [-p] [-d] [-t] [--rowindex <col_ids>] [--recover] [--skip-dump] 
    [--backup-path <new-path>] <location-of-orc-file-or-directory>
```

可选参数含义

| 参数 | 作用                             |      |
| ---- | -------------------------------- | ---- |
| -t   | dunp出orc文件数据而不是orc元数据 |      |
|      |                                  |      |
|      |                                  |      |





测试一下这个工具的作用，执行准备工作如下

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

#### 执行效果

我们执行如下语句`hive --orcfiledump <hdfs location>`,可以看到下面的信息:

![image-20181125134530958](https://ws3.sinaimg.cn/large/006tNbRwly1fxk9bshf0ij31ek0u07nq.jpg)

#### 结果分析

由于测试表的数据量过少，因此只有一个数据文件`000000_0`,因此展示的其实为这个文件的meta信息，下面我们来分析上面的结果

##### File Version

`File Version:0.12 with HIVE_13083`

先看官网的解释

> The version stored in the Postscript is the lowest version of Hive that is guaranteed to be able to read the file and it stored as a sequence of the major and minor version. This file version is encoded as [0,12].

我所理解就是可以读取此文件的hive最低版本为0.12，设计的hive jira为HIVE_13083

#### Rows

`Rows: 3`

这个很好理解，就是文件行数为3行

- Compression:ZLIB

我们知道orc文件有常见的几种压缩方式(eg. none, zlib, or snappy)，这里采用的是zlib

- Compression size: 262144

这个size指的是compression chunk的最大size，262144byte即为256k

- Type: struct<id:int,name:string,weight:decimal(5,2)>

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



- Stripe Statistics

![image-20181125145814758](https://ws2.sinaimg.cn/large/006tNbRwly1fxkbfejp2sj315806agn8.jpg)

这个部分为stripe的统计信息，由于文件较小，只存在一个stripe.要看懂上面的统计信息，首先要结合schema来看.

```json
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





- File Statistics:













#### 参考链接

- [ORC Specification v0](https://orc.apache.org/specification/ORCv0/)
- [How Map and Reduce operations are actually carried out](https://wiki.apache.org/hadoop/HadoopMapReduce)

