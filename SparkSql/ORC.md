



| Config property         | Value |
| ----------------------- | ----- |
| hive.default.fileformat | ORC   |
|                         |       |
|                         |       |





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













#### 参考链接

- [ORC Specification v0](https://orc.apache.org/specification/ORCv0/)

