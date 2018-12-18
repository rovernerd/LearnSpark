

# 简介

本文主要介绍Hive Statistic体系的一些基本知识

# 设计目的

statistic体系设计的一个重要的目的就是针对查询的优化，特别是基于CBO的优化策略。同时在某些场景下用户的查询甚至可以直接查询statstic的内容，而不是执行一个复杂的查询。

## statistc内容

根据官网的介绍Statistic体系主要包含3个部分的内容:Table and Partition Statistics、Column Statistics、Top K Statistics.

### Table and Partition Statistics

table和partition级别的statistic是Hive最早支持的statistic(hive 0.7.0 by [HIVE-1361](https://issues.apache.org/jira/browse/HIVE-1361))。目前的数据主要是存放在Hive的metastore中.

#### Statistics信息

Table和Partition级别的Statistics主要包含如下几个指标(来自官网)

| 名称              | 描述                                         |
| ----------------- | -------------------------------------------- |
| **numFiles**      | 数据集文件个数                               |
| **totalSize**     | 数据集所占的存储，根据文件格式不同有不同的值 |
| **rawDataSize**   | 数据集占用的存储，未压缩状态                 |
| **numRows**       | 数据集的行数                                 |
| **numPartitions** | 分区表分区的个数,hive 2.3会引入              |

#### Table Statistics

##### 使用

创建测试表dev.test_statistic_t

```sql
create table dev.test_statistic_t
(id int ,
 name string);
```

执行`desc formatted dev.test_statistic_t;`,可以看到`Table Parameters`这一部分已经出现了Table的Statistics信息

![image-20181209114328565](https://ws3.sinaimg.cn/large/006tNbRwly1fy0ch3xdsaj313w0u0n7w.jpg)

因为表中并没有数据，因此这些值均为零，我们插入数据,查看已经存在统计信息.可能大家会有疑惑，为什么totalSize比rawDataSize还要大呢？这是因为我们使用了ORC的文件格式，在文件内部存储了meta信息，具体可以参考之前的blog([ORC元数据探索](https://github.com/rovernerd/LearnSpark/blob/master/ORC/ORC元数据探索.md))

```sql
insert overwrite table dev.test_statistic_t
select 1,'neil'
union all
select 2,'jack';
```

![image-20181209115705333](https://ws1.sinaimg.cn/large/006tNbRwly1fy0cva0r6bj318q08cjtf.jpg)

我们尝试再次插入数据，看是否会增加？

```sql
insert into  table dev.test_statistic_t
select 3,'neil'
union all
select 4,'jack';
```

![image-20181209120905509](https://ws3.sinaimg.cn/large/006tNbRwly1fy0d7t60c0j311w080dhl.jpg)

如上图所示，统计信息确实发生了变化.

##### 存储

我们前面所看到的数据就存储在hive的metastore中(一般是mysql),具体的表为`TABLE_PARAMS`,可以执行sql进行查询

```sql
select a.tbl_id,
	   a.tbl_name,
       b.param_key,
	   b.param_value
from `TBLS` a 
left join TABLE_PARAMS b on a.tbl_id = b.tbl_id
where tbl_name = 'test_statistic_t' 
```

从结果可以看到，我们看到的数据就存储在`param_key`和`param_value`中

![image-20181209121718817](https://ws3.sinaimg.cn/large/006tNbRwly1fy0dg9scruj30te07eabi.jpg)

##### 实现

我们来分析一下Hive Table Statistics的实现过程.以hive的执行引擎为MR为例，当每个mapper读取source table的数据时，会统计它所读取的rows信息，并将他们存储在一个临时的存储中(fs、mysql、hbase等).当最后的mr任务执行完毕之后,会将这些信息进行汇总放入hive的metastore。这两个过程分别为publish和Aggregate。

hive提供了StatsPublisher、StatsAggregator这两个接口,用户可以实现对应这两个接口去支持不同的存储.目前已经实现的为FSStatsPublisher和FSStatsAggregator，即数据临时存储在文件系统中.这里对应的参数为`set hive.stats.dbclass = fs`.

##### 参数配置

这时候要分两种情况:新建表和已存在表.

- 新建表

新建表当你配置参数`set hive.stats.autogather=``ture``;`时，当执行hive的DML语句时，会自动的搜集statistic信息，并存储至Hive的metastore中。默认情况下hive.stats.autogather是true的状态

ps:当使用`load data`语句时，并不会搜集statistic.

- 已存在表

已存在的表需要执行`ANALYSE TABLE`语句显式的执行statistic搜集

```shell
ANALYZE TABLE [db_name.]tablename [PARTITION(partcol1[=val1], partcol2[=val2], ...)]  -- (Note: Fully support qualified table name since Hive 1.2.0, see HIVE-10007.)
  COMPUTE STATISTICS 
  [FOR COLUMNS]          -- (Note: Hive 0.10.0 and later.)
  [CACHE METADATA]       -- (Note: Hive 2.1.0 and later.)
  [NOSCAN];
```

我们来模拟这样一个场景,移除表dev.test_statistic_t的一个数据文件.再来查看表的statistic

![image-20181209143758558](https://ws3.sinaimg.cn/large/006tNbRwly1fy0hip5mw3j318408kack.jpg)

可以看到numFiles为2，其实在hdfs目录下已经只有一个文件，这是因为我们删除文件并没有触发MR任务，所以并没有自动的搜集statistic。这时我们执行`analyze table dev.test_statistic_t `  进行显式的搜集，再来看结果.可以发现statistic已经变成新的了

![image-20181209144235248](https://ws1.sinaimg.cn/large/006tNbRwly1fy0hnfqfbbj30ym07wjtr.jpg)



#### Partition Statistics

##### 使用

因为partition的statistics主要针对分区表，我们创建一个分区测试表

```sql
// 创建表
create table dev.test_statistic_p
(id int ,
 name string)
PARTITIONED BY (par string);
// 插入数据
insert overwrite table dev.test_statistic_p partition(par = '20181207')
select 1,'neil'
union all
select 2,'jack';
```

查看`desc formatted dev.test_statistic_p;`

![image-20181209151036753](https://ws3.sinaimg.cn/large/006tNbRwly1fy0ign3b3ej31e00twakf.jpg)

发现在table param中并没有相关的信息。这时因为partition表的statistic信息记录在partition的param中.

此时需要执行`desc formatted dev.test_statistic_p  partition(par = '20181107')`进行查看    

![image-20181209151852064](https://ws2.sinaimg.cn/large/006tNbRwly1fy0ip64s4mj31ka0t2dt5.jpg)

##### 存储

同样的partition的statistic也是存储在hive的metastore中，只不过表换成了PARTITION_PARAMS,可以执行下面的语句进行查看

```SQL
SELECT 
	   A.TBL_ID,
	   A.TBL_NAME,
       B.PART_NAME,
	   C.PARAM_KEY,
	   C.PARAM_VALUE
FROM `TBLS` A 
LEFT JOIN  `PARTITIONS` B ON A.TBL_ID = B.TBL_ID
LEFT JOIN  `PARTITION_PARAMS` C ON B.PART_ID = C.PART_ID
WHERE A.TBL_NAME = 'test_statistic_p'  AND PART_NAME = 'par=20181207'
```

明细结果

![image-20181209153355460](https://ws1.sinaimg.cn/large/006tNbRwly1fy0j4ud06tj310q07qwhp.jpg)

##### 实现

实现和table statistic类似，此处不再赘述

##### 参数配置

`set hive.stats.autogather=ture`对分区表也是生效的，默认会自动搜集statistic。不同的为analyze的使用，对于分区表进行analyze要指定分区，即使需要对所有分区进行analyze时，也要写成如下的形式

```sql
analyze table dev.test_statistic_p partition(par) compute statistics;
```

#### Columns Statistics

其实column的statistic也是分为table和partition的，我们以table的column statistic进行介绍，partition的情况时类似的.

##### 使用

创建测试表

```sql
-- 创建表
create table dev.test_statistic_c
(id int ,
 name string,
 weight decimal(4,2),
 is_boy boolean );
 
-- 测试数据
insert overwrite table dev.test_statistic_c
select 1,'neil',75.2,'ture'
union all
select 2,'jack',80.3,'false'
union all
select 3,'tom',69.3,'ture';

```

这里hive本身并不会自动的搜集statistic信息,需要手动执行analyze语句进行搜集。

```sql
analyze table dev.test_statistic_c compute statistics for columns;
```

执行analyze语句会触发一个mr任务去计算这些列的数据.

可以使用` desc `



https://issues.apache.org/jira/browse/HIVE-7060



`desc formatted dev.test_statistic_t id` 

##### 存储

同样以column statistics为例说明，先来看存储的表结构.

```sql
CREATE TABLE `TAB_COL_STATS` (
  `CS_ID` bigint(20) NOT NULL,
  `AVG_COL_LEN` double DEFAULT NULL,
  `COLUMN_NAME` varchar(767) CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
  `COLUMN_TYPE` varchar(128) CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
  `DB_NAME` varchar(128) CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
  `BIG_DECIMAL_HIGH_VALUE` varchar(255) CHARACTER SET latin1 COLLATE latin1_bin DEFAULT NULL,
  `BIG_DECIMAL_LOW_VALUE` varchar(255) CHARACTER SET latin1 COLLATE latin1_bin DEFAULT NULL,
  `DOUBLE_HIGH_VALUE` double DEFAULT NULL,
  `DOUBLE_LOW_VALUE` double DEFAULT NULL,
  `LAST_ANALYZED` bigint(20) NOT NULL,
  `LONG_HIGH_VALUE` bigint(20) DEFAULT NULL,
  `LONG_LOW_VALUE` bigint(20) DEFAULT NULL,
  `MAX_COL_LEN` bigint(20) DEFAULT NULL,
  `NUM_DISTINCTS` bigint(20) DEFAULT NULL,
  `NUM_FALSES` bigint(20) DEFAULT NULL,
  `NUM_NULLS` bigint(20) NOT NULL,
  `NUM_TRUES` bigint(20) DEFAULT NULL,
  `TBL_ID` bigint(20) DEFAULT NULL,
  `TABLE_NAME` varchar(128) CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
  PRIMARY KEY (`CS_ID`),
  KEY `TAB_COL_STATS_N49` (`TBL_ID`),
  CONSTRAINT `TAB_COL_STATS_FK1` FOREIGN KEY (`TBL_ID`) REFERENCES `TBLS` (`TBL_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1
```

















