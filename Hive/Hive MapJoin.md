### 简介

本文记录一下一次MapJoin异常的问题排查记录，顺便也对mapjoin进行梳理

### 场景模拟

首先我们来模拟一下问题的场景，简单来说就是大表join小表的场景，我们知道在这样的情况下，当小表足够小(由参数`hive.mapjoin.smalltable.filesize`控制,默认大小为25m),hive会走mapjoin来避免shuffle这个比较expensive的过程.

```sql
// 执行sql

create table dev.table_test_w;
as 
select 
a.kdt_id,
b.kdt_id,
c.kdt_id,
d.kdt_id
from dev.table_big a 
left join dev.table_samll_1 b on a.kdt_id = b.kdt_id
left join dev.table_small_2 c on a.kdt_id = c.kdt_id
left join dev.table_small_3 d on a.kdt_id = d.kdt_id;
```

错误log

```
2018-12-20 04:24:01,049 INFO - Query ID = app_20181220042359_9e5852a3-9cf7-4e77-b025-672c36782b2a
2018-12-20 04:24:01,050 INFO - Total jobs = 1
2018-12-20 04:24:05,679 INFO - SLF4J: Class path contains multiple SLF4J bindings.
2018-12-20 04:24:05,679 INFO - SLF4J: Found binding in [jar:file:/opt/hive/lib/log4j-slf4j-impl-2.4.1.jar!/org/slf4j/impl/StaticLoggerBinder.class]
2018-12-20 04:24:05,680 INFO - SLF4J: Found binding in [jar:file:/opt/hadoop/share/hadoop/common/lib/slf4j-log4j12-1.7.5.jar!/org/slf4j/impl/StaticLoggerBinder.class]
2018-12-20 04:24:05,680 INFO - SLF4J: See http://www.slf4j.org/codes.html#multiple_bindings for an explanation.
2018-12-20 04:24:05,682 INFO - SLF4J: Actual binding is of type [org.apache.logging.slf4j.Log4jLoggerFactory]
2018-12-20 04:24:07,155 INFO - 2018-12-20 04:24:07	Starting to launch local task to process map join;	maximum memory = 477626368
2018-12-20 04:24:09,030 INFO - 2018-12-20 04:24:09	Processing rows:	200000	Hashtable size:	194098	Memory usage:	107118904	percentage:	0.224
2018-12-20 04:24:09,110 INFO - 2018-12-20 04:24:09	Processing rows:	300000	Hashtable size:	294098	Memory usage:	138293136	percentage:	0.29
2018-12-20 04:24:09,215 INFO - 2018-12-20 04:24:09	Processing rows:	400000	Hashtable size:	394098	Memory usage:	169531232	percentage:	0.355
2018-12-20 04:24:10,203 INFO - 2018-12-20 04:24:10	Processing rows:	500000	Hashtable size:	494098	Memory usage:	162578568	percentage:	0.34
2018-12-20 04:24:10,371 INFO - 2018-12-20 04:24:10	Processing rows:	600000	Hashtable size:	594098	Memory usage:	190419384	percentage:	0.399
2018-12-20 04:24:10,638 INFO - 2018-12-20 04:24:10	Processing rows:	700000	Hashtable size:	694098	Memory usage:	210998680	percentage:	0.442
2018-12-20 04:24:10,785 INFO - 2018-12-20 04:24:10	Processing rows:	800000	Hashtable size:	794098	Memory usage:	248304736	percentage:	0.52
2018-12-20 04:24:12,394 INFO - 2018-12-20 04:24:12	Processing rows:	900000	Hashtable size:	894098	Memory usage:	264883312	percentage:	0.555
2018-12-20 04:24:12,449 INFO - 2018-12-20 04:24:12	Processing rows:	1000000	Hashtable size:	994098	Memory usage:	291558720	percentage:	0.61
2018-12-20 04:24:12,699 INFO - 2018-12-20 04:24:12	Processing rows:	1100000	Hashtable size:	1094098	Memory usage:	311281144	percentage:	0.652
2018-12-20 04:24:12,767 INFO - 2018-12-20 04:24:12	Processing rows:	1200000	Hashtable size:	1194098	Memory usage:	339487776	percentage:	0.711
2018-12-20 04:24:15,041 INFO - 2018-12-20 04:24:15	Processing rows:	1300000	Hashtable size:	1294098	Memory usage:	358012320	percentage:	0.75
2018-12-20 04:24:15,114 INFO - 2018-12-20 04:24:15	Processing rows:	1400000	Hashtable size:	1394098	Memory usage:	385965840	percentage:	0.808
2018-12-20 04:24:15,183 INFO - 2018-12-20 04:24:15	Processing rows:	1500000	Hashtable size:	1494098	Memory usage:	413181640	percentage:	0.865
2018-12-20 04:25:51,838 INFO - Exception in thread "Thread-0" java.lang.OutOfMemoryError: GC overhead limit exceeded
2018-12-20 04:25:51,838 INFO - at java.util.logging.LogManager.reset(LogManager.java:1321)
2018-12-20 04:25:51,838 INFO - at java.util.logging.LogManager$Cleaner.run(LogManager.java:239)
2018-12-20 04:26:01,208 INFO - Execution failed with exit status: 3
2018-12-20 04:26:01,208 INFO - Obtaining error information
2018-12-20 04:26:01,208 INFO - 
2018-12-20 04:26:01,208 INFO - Task failed!
2018-12-20 04:26:01,209 INFO - Task ID:
2018-12-20 04:26:01,209 INFO - Stage-18
2018-12-20 04:26:01,209 INFO - 
2018-12-20 04:26:01,209 INFO - Logs:
2018-12-20 04:26:01,209 INFO - 
2018-12-20 04:26:01,221 INFO - FAILED: Execution Error, return code 3 from org.apache.hadoop.hive.ql.exec.mr.MapredLocalTask
2018-12-20 04:26:01,832 INFO - Done. Returned value was: 3
2018-12-20 04:26:01,833 ERROR - Bash command failed
```

可以看到最终报错的原因是OOM.

### 分析

#### 剖开

借用网上的一张关于map join流程的图片，问题便出现在MR Local Task这个过程中

![Hive MapJoin](https://ws1.sinaimg.cn/large/006tNbRwly1fye4pel1s4j30js0hz0x0.jpg)

Local Work的过程主要有下面几步

- table scan小表
- 在内存中构建hashtable
- 写hashtable file到磁盘
- 将hashtable file上传到hdfs
- 加载hashtable file到分布式缓存

从上面的报错日志来看,OOM出现在内存中构建hashtable这个过程中。



先来看下Local Task的相关过程

通过参数`hive.exec.submit.local.task.via.child`来执行是直接在driver的进程中直接执行还是另起一个JVM执行LocalTask.默认为true,即另起一个child JVM取跑local task.

官方对于这个参数的解释

> Determines whether local tasks (typically mapjoin hashtable generation phase) runs in 
> separate JVM (true recommended) or not.Avoids the overhead of spawning new JVM, but can lead to out-of-memory issues.

相关的代码

```java
@Override
public int execute(DriverContext driverContext) {
  if (conf.getBoolVar(HiveConf.ConfVars.SUBMITLOCALTASKVIACHILD)) {
    // send task off to another jvm
    return executeInChildVM(driverContext);
  } else {
    // execute in process
    return executeInProcess(driverContext);
  }
}
```

看`executeInChildVM`  的代码,在客户端本地会在启动一个  ` ExecDriver`,在这个driver中进行小表的扫描和hashtable的构建。

启动ExecDriver的过程

```java
// Run ExecDriver in another JVM
executor = Runtime.getRuntime().exec(cmdLine, env, new File(workDir));
```

接下来看整个执行过程

- MapredLocalTask.execute
- executeInChildVM(driverContext)
- executor = Runtime.getRuntime().exec(cmdLine, env, new File(workDir))
- ExecDriver.main()
- ed.executeInProcess(new DriverContext())
- startForward(null)
- startForward(inputFileChangeSenstive, null)
- 执行operator(TableScan/HashTableSinkOperator)

在启动ExecDriver这一个过程,需要执行java -jar，执行的cmd命令格式如下

```shell
/opt/hadoop/bin/hadoop jar 
/opt/hive/lib/hive-common-2.1.1.jar 
org.apache.hadoop.hive.ql.exec.mr.ExecDriver 
-localtask 
-plan 
file:/tmp/app/4ed247ca-31a2-4ee7-8985-aefe519f6ce3/hive_2018-12-28_17-53-44_294_5009425881462039040-11/-local-10007/plan.xml   
-jobconffile file:/tmp/app/4ed247ca-31a2-4ee7-8985-aefe519f6ce3/hive_2018-12-28_17-53-44_294_5009425881462039040-11/-local-10008/jobconf.xml
```

从源码来看,执行到`HashTableSinkOperator`要进行内存的检查

```java

/*
   * This operator only process small tables Read the key/value pairs Load them into hashtable
   */
  @Override
  public void process(Object row, int tag) throws HiveException {
    ...  
    ...
    MapJoinPersistableTableContainer tableContainer = mapJoinTables[alias];
    MapJoinRowContainer rowContainer = tableContainer.get(key);
    if (rowContainer == null) {
      if(value.length != 0) {
        rowContainer = new MapJoinEagerRowContainer();
        rowContainer.addRow(value);
      } else {
        rowContainer = emptyRowContainer;
      }
      rowNumber++;
      if (rowNumber > hashTableScale && rowNumber % hashTableScale == 0) {
        memoryExhaustionHandler.checkMemoryStatus(tableContainer.size(), rowNumber);
      }
      tableContainer.put(key, rowContainer);
    } else if (rowContainer == emptyRowContainer) {
      rowContainer = rowContainer.copy();
      rowContainer.addRow(value);
      tableContainer.put(key, rowContainer);
    } else {
      rowContainer.addRow(value);
    }
```

当rowNumber > hashTableScale && rowNumber % hashTableScale == 0进行一次内存检查，这里的rowNumber即表的行数，hashTableScale即参数`hive.mapjoin.check.memory.rows`(内存检查的频度，默认10w).

再来看`checkMemoryStatus`做了什么

```java
public void checkMemoryStatus(long tableContainerSize, long numRows)
  throws MapJoinMemoryExhaustionException {
    long usedMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    double percentage = (double) usedMemory / (double) maxHeapSize;
    String msg = Utilities.now() + "\tProcessing rows:\t" + numRows + "\tHashtable size:\t"
        + tableContainerSize + "\tMemory usage:\t" + usedMemory + "\tpercentage:\t" + percentageNumberFormat.format(percentage);
    console.printInfo(msg);
    if(percentage > maxMemoryUsage) {
      throw new MapJoinMemoryExhaustionException(msg);
    }
   }
```

可以看到会对usedMemory/maxHeapSize和值maxMemoryUsage(参数`hive.mapjoin.localtask.max.memory.usage`,默认值0.9)进行比较，当超过时，抛出异常`MapJoinMemoryExhaustionException`,整个过程和我们的报错日志一致(每10w行进行一次内存检查,在0.9之前直接报错)

在来看我们模拟的场景,我们使用的文件存储为ORC格式，众所周知这种文件格式有着很高的压缩比.以上面的表为例,未压缩状态下为75m,压缩完仅为0.5m左右.同时呢，在构建hashtable的过程中也是要占用内存的。因此，将commonjoin转化为mapjoin是存在一定的风险的.



hive有没有考虑过这种问题呢？其实是有的,我们再来看执行计划

![image-20181222140009530](https://ws3.sinaimg.cn/large/006tNbRwly1fyfhhe4epmj310o0u0n7g.jpg)



Stage7即为MR local task,但是他还有一个backup stage，即Stage1,Stage1就是common join。也就是说当mapjoin stage执行失败后，会进行commonjoin的重试.但是上面的sql为什么没有进行common join的重试就直接失败了呢？

#### 深入

这个时候就要出现另外的一对参数了,我们来看一下官方的解释

> ##### hive.auto.convert.join.noconditionaltask
>
> - Default Value: `true`
> - Added In: 0.11.0 with [HIVE-3784](https://issues.apache.org/jira/browse/HIVE-3784) (default changed to true with [HIVE-4146](https://issues.apache.org/jira/browse/HIVE-4146))
>
> Whether Hive enables the optimization about converting common join into mapjoin based on the input file size. If this parameter is on, and the sum of size for n-1 of the tables/partitions for an n-way join is smaller than the size specified by hive.auto.convert.join.noconditionaltask.size, the join is directly converted to a mapjoin (there is no conditional task).
>
> ##### hive.auto.convert.join.noconditionaltask.size
>
> - Default Value: `10000000`
> - Added In: 0.11.0 with [HIVE-3784](https://issues.apache.org/jira/browse/HIVE-3784)
>
> If hive.auto.convert.join.noconditionaltask is off, this parameter does not take effect. However, if it is on, and the sum of size for n-1 of the tables/partitions for an n-way join is smaller than this size, the join is directly converted to a mapjoin (there is no conditional task). The default is 10MB.

简单来说当noconditionaltask设置为true时,如果你的sql中的n-1张表的大小之和小于某个阈值(默认为10m)时,hive就会认为这个join可以直接转化为mapjoin，不需要backup.

目前集群的noconditionaltask设置为ture,同时案例中的3个小表大小之和小于10m，因此就中招了，汗～



具体来看代码,这段处理逻辑在`CommonJoinTaskDispatcher`类的processCurrentTask中

```java
try {
      long aliasTotalKnownInputSize =
          getTotalKnownInputSize(context, currWork, pathToAliases, aliasToSize);

      Set<Integer> bigTableCandidates = MapJoinProcessor.getBigTableCandidates(joinDesc
          .getConds());

      // no table could be the big table; there is no need to convert
      if (bigTableCandidates.isEmpty()) {
        return null;
      }

      // if any of bigTableCandidates is from multi-sourced, bigTableCandidates should
      // only contain multi-sourced because multi-sourced cannot be hashed or direct readable
      bigTableCandidates = multiInsertBigTableCheck(joinOp, bigTableCandidates);

      Configuration conf = context.getConf();

      // If sizes of at least n-1 tables in a n-way join is known, and their sum is smaller than
      // the threshold size, convert the join into map-join and don't create a conditional task
      boolean convertJoinMapJoin = HiveConf.getBoolVar(conf,
          HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASK);
      int bigTablePosition = -1;
      if (convertJoinMapJoin) {
        // This is the threshold that the user has specified to fit in mapjoin
        long mapJoinSize = HiveConf.getLongVar(conf,
            HiveConf.ConfVars.HIVECONVERTJOINNOCONDITIONALTASKTHRESHOLD);

        Long bigTableSize = null;
        Set<String> aliases = aliasToWork.keySet();
        for (int tablePosition : bigTableCandidates) {
          Operator<?> parent = joinOp.getParentOperators().get(tablePosition);
          Set<String> participants = GenMapRedUtils.findAliases(currWork, parent);
          long sumOfOthers = Utilities.sumOfExcept(aliasToSize, aliases, participants);
          if (sumOfOthers < 0 || sumOfOthers > mapJoinSize) {
            continue; // some small alias is not known or too big
          }
          if (bigTableSize == null && bigTablePosition >= 0 && tablePosition < bigTablePosition) {
            continue; // prefer right most alias
          }
          long aliasSize = Utilities.sumOf(aliasToSize, participants);
          if (bigTableSize == null || bigTableSize < 0 || (aliasSize >= 0 && aliasSize >= bigTableSize)) {
            bigTablePosition = tablePosition;
            bigTableSize = aliasSize;
          }
        }
      }

      currWork.setLeftInputJoin(joinOp.getConf().isLeftInputJoin());
      currWork.setBaseSrc(joinOp.getConf().getBaseSrc());
      currWork.setMapAliases(joinOp.getConf().getMapAliases());

      if (bigTablePosition >= 0) {
        // create map join task and set big table as bigTablePosition
        MapRedTask newTask = convertTaskToMapJoinTask(currTask.getWork(), bigTablePosition);

        newTask.setTaskTag(Task.MAPJOIN_ONLY_NOBACKUP);
        newTask.setFetchSource(currTask.isFetchSource());
        replaceTask(currTask, newTask);

        // Can this task be merged with the child task. This can happen if a big table is being
        // joined with multiple small tables on different keys
        if ((newTask.getChildTasks() != null) && (newTask.getChildTasks().size() == 1)) {
          mergeMapJoinTaskIntoItsChildMapRedTask(newTask, conf);
        }

        return newTask;
      }

      long ThresholdOfSmallTblSizeSum = HiveConf.getLongVar(conf,
          HiveConf.ConfVars.HIVESMALLTABLESFILESIZE);
      for (int pos = 0; pos < joinOp.getNumParent(); pos++) {
        // this table cannot be big table
        if (!bigTableCandidates.contains(pos)) {
          continue;
        }
        // deep copy a new mapred work from xml
        // Once HIVE-4396 is in, it would be faster to use a cheaper method to clone the plan
        MapredWork newWork = SerializationUtilities.clonePlan(currTask.getWork());

        // create map join task and set big table as i
        MapRedTask newTask = convertTaskToMapJoinTask(newWork, pos);

        Operator<?> startOp = joinOp.getParentOperators().get(pos);
        Set<String> aliases = GenMapRedUtils.findAliases(currWork, startOp);

        long aliasKnownSize = Utilities.sumOf(aliasToSize, aliases);
        if (cannotConvert(aliasKnownSize, aliasTotalKnownInputSize, ThresholdOfSmallTblSizeSum)) {
          continue;
        }

        // add into conditional task
        listWorks.add(newTask.getWork());
        listTasks.add(newTask);
        newTask.setTaskTag(Task.CONVERTED_MAPJOIN);
        newTask.setFetchSource(currTask.isFetchSource());

        // set up backup task
        newTask.setBackupTask(currTask);
        newTask.setBackupChildrenTasks(currTask.getChildTasks());

        // put the mapping task to aliases
        taskToAliases.put(newTask, aliases);
      }
    } catch (Exception e) {
      throw new SemanticException("Generate Map Join Task Error: " + e.getMessage(), e);
    }
```

当出现上述的情况时，会更新task的tag为MAPJOIN_ONLY_NOBACKUP

即如下的set语句`newTask.setTaskTag(Task.MAPJOIN_ONLY_NOBACKUP);`

### 解决

最简单直接的方式就是将noconditionaltask设置为false，即当需要进行convert join时仍需要backup task.这样可以有效避免任务直接报错。

### 参考

- [LanguageManual+JoinOptimization](https://cwiki.apache.org/confluence/display/Hive/LanguageManual+JoinOptimization)
- [hive debug](https://my.oschina.net/kavn/blog/867314)





