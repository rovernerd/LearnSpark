## 背景

工作中有使用到hive的view，产生一个疑惑，如果这个view的一个字段来源于2个hive table的相同字段，且字段类型不一致，那么view表的类型将是怎么样的？下面来模拟一下这个场景

## 测试

创建测试表

```sql
// 测试表a
// 创建
drop table dev.table_a_test;
create table dev.table_a_test
(id string,
 value bigint
);
// 数据插入
insert into dev.table_a_test
select 'a',14;

// 测试表b
// 创建
drop table dev.table_b_test;
create table dev.table_b_test
(id string,
 value decimal(16,4)
);
// 数据插入
insert into dev.table_b_test 
select 'a',15.2123;
insert into dev.table_b_test 
select 'b',15.2123;


// 创建view
drop view dev.table_test_v;
CREATE VIEW IF NOT EXISTS dev.table_test_v
(
id 
,value
)
AS
SELECT
a.id,
if(b.id is not null,b.value,a.value) as value
from dev.table_a_test a 
left join dev.table_b_test b 
on a.id = b.id ;
```

查看view的value字段类型`desc  dev.table_test_v `,可以发现value的类型为decimal(23,4)。这个23是怎么得到的？因为bigint的最大位数为19位，加上小数位数4位，就为23位了。这样看来view的类型推断还是很精准的。

![image-20181218193511854](https://ws1.sinaimg.cn/large/006tNbRwly1fyb4op9v7hj30ra06oq43.jpg)

再来测试下别的情况,类型组合如下:

| a表类型       | b表类型       | view类型      |
| ------------- | ------------- | ------------- |
| bigint        | string        | string        |
| bigint        | decimal(24,4) | decimal(24,4) |
| decimal(16,4) | decimal(14,5) | decimal(17,5) |

## 总结

总结一下hive的view会根据原表的字段类型进行一个类型的推断(数值类型的话会取精度高且位数多的那个)，保证数据出现异常。









