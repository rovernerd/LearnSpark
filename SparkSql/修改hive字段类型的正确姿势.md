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

##### 局限性

这种修改的方式是将分区表已存在的所有分区的字段类型进行更改,如果历史的分区中不存在这个字段，执行是会报错的.

`Error while processing statement: FAILED: Execution Error, return code 1 from org.apache.hadoop.hive.ql.exec.DDLTask. Invalid column reference xxx`

因此增加字段的语法建议使用级联的方式进行新增,即

```
ALTER TABLE table_name 
  [PARTITION partition_spec]                 -- (Note: Hive 0.14.0 and later)
  ADD|REPLACE COLUMNS (col_name data_type [COMMENT col_comment], ...)
  [CASCADE|RESTRICT]                         -- (Note: Hive 1.1.0 and later)
```

### 类型不一致

#### 问题一

使用presto查询数据无法查询(数据重刷后,hive可以查询到正确数据类型的数据)

![image-20181129113544734](https://ws2.sinaimg.cn/large/006tNbRwgy1fxos23umb5j326q09mn6a.jpg)

#### 问题二

元数据不一致的隐患，别的查询系统使用不排除数据异常产生(spark之前查询有报错，目前没有复现😓)



## 后记

修改了字段的类型，但是底层存储的数据本身的type是不会自动修改的。我们看到orc底层的meta信息还是decimal(15,3),**因此切记数据还是要重新刷新的。**

![image-20181126211301562](https://ws3.sinaimg.cn/large/006tNbRwgy1fxlrvnr7rmj32420kqwvj.jpg)

### 数据修复

下面sql可以查询表字段类型和分区字段类型不一致的信息

```sql
select
a.owner,
g.name,
a.tbl_name,
a.tbl_id,
a.sd_id as table_sd_id,
b.part_id,
b.part_name,
b.sd_id as part_sd_id,
c.cd_id,
d.column_name as par_name,
d.type_name as par_type_name,
f.column_name as table_name,
f.type_name as table_type_name
from `TBLS` a 
left join `PARTITIONS` b 
on a.tbl_id = b.tbl_id
left join SDS c 
on b.sd_id = c.sd_id
left join `COLUMNS_V2` d 
on c.cd_id = d.cd_id
left join SDS e 
on a.sd_id = e.sd_id
left join COLUMNS_V2 f 
on e.cd_id = f.cd_id
left join DBS g 
on a.db_id = g.db_id
where  d.column_name = f.column_name 
-- and d.type_name <> f.type_name   
and tbl_name = 'xxx';
```



## 参考链接

- [hive manual](https://cwiki.apache.org/confluence/display/Hive/LanguageManual+DDL#LanguageManualDDL-AlterPartition)

- [HIVE-17309](https://issues.apache.org/jira/browse/HIVE-17309?jql=project%20%3D%20HIVE%20AND%20text%20~%20%22unable%20to%20alter%20partition%22)





