## 背景

最近在做hive字段类型的升级的事情，当分区表需要更改字段类型时，怎么有效的修改字段的类型?(非分区表不再讨论，情况比较简单，这里主要讨论分区表的情形)

## 测试数据

```sql
// 建表
drop table if exists tmp.par_column_type_test;
create table tmp.par_column_type_test
(
     id           bigint                   COMMENT 'ID'
    ,price        decimal(15,3)            COMMENT '价格'
) 	                                       COMMENT '测试批量修改字段类型'
partitioned by(par string comment "分区字段，格式YYYYMMDD")
stored as orc;

// 插入数据测试
INSERT OVERWRITE TABLE tmp.par_column_type_test partition(par = '20181118')
select 201811,4.3123
union all
select 201812,4.3125;

INSERT OVERWRITE TABLE tmp.par_column_type_test partition(par = '20181117')
select 201811,4.3123
union all
select 201812,4.3125;
```

## 场景

价格的精度发生了调整,要由decimal(15,3)更改为decimal(16,4),如何有效的修改字段类型？

### 错误操作

我们尝试直接修改

```sql
alter table tmp.par_column_type_test change price price decimal(16,4);
```

查看表结构，发现price的字段类型已经修改过来了.

![image-20181126201519124](https://ws4.sinaimg.cn/large/006tNbRwgy1fxlq7rdh6wj314e0bwgpq.jpg)

但是单独查看分区数据,发现分区的类型还是decimal(15,3)

![image-20181126210841719](https://ws2.sinaimg.cn/large/006tNbRwgy1fxlrr5s7twj31ds0hsdq3.jpg)

这时因为partition级别也存在单独的meta信息，我们刚才只是修改了table级别的meta信息.如果我们使用presto或者impala这种对类型严格的引擎查询,会直接爆类型不一致的错误.

### 正确操作

如何才能正确修改,hive官网提供了2种方案

#### 级联修改

下面是级联修改的grammar

```sql
// grammar
ALTER TABLE table_name [PARTITION partition_spec] CHANGE [COLUMN] col_old_name col_new_name column_type [COMMENT col_comment] [FIRST|AFTER column_name] [CASCADE|RESTRICT];
```

官方的解释如下

> ALTER TABLE CHANGE COLUMN with CASCADE command changes the columns of a table's metadata, and cascades the same change to all the partition metadata. RESTRICT is the default, limiting column change only to table metadata.

我们可以执行`alter table tmp.par_column_type_test change price price decimal(16,4) CASCADE ;`语句进行级联修改,再看partition的meta,已经修改为decimal(16,4)

![image-20181126210948424](https://ws4.sinaimg.cn/large/006tNbRwgy1fxlrsawpylj31je0j4akq.jpg)

##### 局限性

这种级联操作的局限性在于执行之前不能执行表级别的修改，不然再次执行级联修改仍然不会生效.

#### partition修改

hive官网同时提供了单独修改partition的meta的语法.

例如我们执行`alter table tmp.par_column_type_test partition(par = '20181118')change price price decimal(16,4)`去修改parition级别的meta.这时爆了个错误.😅

![image-20181126205014176](https://ws1.sinaimg.cn/large/006tNbRwgy1fxlr7y2mn4j321s03amzz.jpg)

这个地方是hive本身的一个bug，[HIVE-17309](https://issues.apache.org/jira/browse/HIVE-17309?jql=project%20%3D%20HIVE%20AND%20text%20~%20%22unable%20to%20alter%20partition%22),简单来说就是alter partition时未获取到table对应的database.社区预计在3.0fix掉，也可以通过打patch解决.

替代方案可以通过显式`use database `来解决。

这个相比于上面的级联方法的优势在于即使执行过表级别修改后再执行partition级别修改后仍可以修改。同时当表的partition数量过多时可以支持dynamic partition的方式修改,还是很好用的😂

```sql
SET hive.exec.dynamic.partition = true;
alter table tmp.par_column_type_test partition(par)change price price decimal(16,4)
```

## 后记

修改了字段的类型，但是底层存储的数据本身的type是不会自动修改的。我们看到orc底层的meta信息还是decimal(15,3),**因此切记数据还是要重新刷新的。**

![image-20181126211301562](https://ws3.sinaimg.cn/large/006tNbRwgy1fxlrvnr7rmj32420kqwvj.jpg)

## 参考链接

- [hive manual](https://cwiki.apache.org/confluence/display/Hive/LanguageManual+DDL#LanguageManualDDL-AlterPartition)

- [HIVE-17309](https://issues.apache.org/jira/browse/HIVE-17309?jql=project%20%3D%20HIVE%20AND%20text%20~%20%22unable%20to%20alter%20partition%22)





