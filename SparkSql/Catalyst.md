# 关于Catalyst

## Catalyst是什么

本意为催化剂<化>,我们可以理解为spark的优化器.spark社区所希望的catalyst是可以根据用户的代码自动的采取更加有效的执行策略.

## Catalyst历史

**这个等下再补**



## Catalyst地位

![Catalyst blog figure 2](https://ws4.sinaimg.cn/large/006tNbRwly1fx2qxnxi0ej30sg06jmym.jpg)

Catalyst 主要应用于上图的4个部分

1. Analysis
2. Logical Plan Optimization
3. Physical Plan
4. code generation

## Catalyst和Scala

Catalyst 使用scala中的模式匹配(pattern matching)和偏函数(partial function)的特性，这大大节省了代码量以及提升了可读性。

### pattern matching

### partial function



## Tree

Tree是catalyst中最主要使用的数据结构.Tree的特征主要有如下几点

- Tree是由node组成
- node拥有自己的类型,同时可以有0个或者多个children node
- node type都是TreeNode的子类

借用官方的图,如下就是Tree的形象化表示

```
  Add(
    Attribute(x),
    Add(Literal(1), Literal(2)))
```

![Catalyst blog figure 1](https://ws4.sinaimg.cn/large/006tNbRwly1fx30wqrwckj308c04uaa7.jpg)

## Rule

简单来说，Rule是Catalyst中转变Tree的一些方法.充分利用了scala函数式语言中模式匹配(pattern matching)和偏函数(partial function)这样的特性，使得代码更加的精简和易读。

同样引用官方的示例(常量合并)

```scala
tree.transform {

  case Add(Literal(c1), Literal(c2)) => Literal(c1+c2)

  case Add(left, Literal(0)) => left

  case Add(Literal(0), right) => right

}
```

**那么上面的Tree在应用这个Rule之后，应该变成如下这样.**(缺少一张图)





### 简介

Rule本身也是一个抽象类，子类需要重写apply方法去实现特定的处理逻辑.

Rule的定义如下

```scala
abstract class Rule[TreeType <: TreeNode[_]] extends Logging {

  /** Name for this rule, automatically inferred based on class name. */
  val ruleName: String = {
    val className = getClass.getName
    if (className endsWith "$") className.dropRight(1) else className
  }

  def apply(plan: TreeType): TreeType
}
```

### 样例

#### ComputeCurrentTime

让我们考虑一个场景,当你写下面一段Sql

```sql
SELECT 
CURRENT_TIME() AS ETL_TIME,
ID,
NAME
FROM TABLE 
WHERE DATE = CURRENT_DATE()
```

这段sql中同时要计算date和time，为了消除可能产生的不一致,ComputeCurrentTime这样一条Rule会计算一次，然后将结果进行替换

```scala
object ComputeCurrentTime extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = {
    val currentDates = mutable.Map.empty[String, Literal]
    val timeExpr = CurrentTimestamp()
    val timestamp = timeExpr.eval(EmptyRow).asInstanceOf[Long]
    val currentTime = Literal.create(timestamp, timeExpr.dataType)

    plan transformAllExpressions {
      case CurrentDate(Some(timeZoneId)) =>
        currentDates.getOrElseUpdate(timeZoneId, {
          Literal.create(
            DateTimeUtils.millisToDays(timestamp / 1000L, DateTimeUtils.getTimeZone(timeZoneId)),
            DateType)
        })
      case CurrentTimestamp() => currentTime
    }
  }
}
```

### 调用

Sparksql 定义了大量的Rule，但是一般并不是单独去调用某一个Rule,而是将他们组合起来，这里便有了batch的概念.一个batch便是一系列Rule的组合.要明白Rule的调用过程，需要了解如下的几个概念

#### batch

Batch 代表了一套Rule的组合

```scala
protected case class Batch(name: String, strategy: Strategy, rules: Rule[TreeType]*)
```

#### Strategy

这里的Strategy,则是代表Batch调用的策略.他有一个Int类型的属性maxIterations,代表这个Batch的最大调用次数.

Strategy有两个子类，一个是Once(maxIterations = 1 )，一个是FixedPoint(需要传入maxIterations,调用次数为maxIterations或者达到fixedpoint所需要的次数，取两者中小的那个.这个逻辑在RuleExecutor中的execute方法中实现)

#### RuleExecutor

RuleExecutor为一个抽象类，他的子类(Analysis、optimizer)需要实现Batches、execute等方法.

execute方法

```scala
def execute(plan: TreeType): TreeType = {
    var curPlan = plan
    val queryExecutionMetrics = RuleExecutor.queryExecutionMeter

    batches.foreach { batch =>
      val batchStartPlan = curPlan
      var iteration = 1
      var lastPlan = curPlan
      var continue = true

      // Run until fix point (or the max number of iterations as specified in the strategy.
      while (continue) {
        curPlan = batch.rules.foldLeft(curPlan) {
          case (plan, rule) =>
            val startTime = System.nanoTime()
            val result = rule(plan)
            val runTime = System.nanoTime() - startTime

            if (!result.fastEquals(plan)) {
              queryExecutionMetrics.incNumEffectiveExecution(rule.ruleName)
              queryExecutionMetrics.incTimeEffectiveExecutionBy(rule.ruleName, runTime)
              logTrace(
                s"""
                  |=== Applying Rule ${rule.ruleName} ===
                  |${sideBySide(plan.treeString, result.treeString).mkString("\n")}
                """.stripMargin)
            }
            queryExecutionMetrics.incExecutionTimeBy(rule.ruleName, runTime)
            queryExecutionMetrics.incNumExecution(rule.ruleName)

            // Run the structural integrity checker against the plan after each rule.
            if (!isPlanIntegral(result)) {
              val message = s"After applying rule ${rule.ruleName} in batch ${batch.name}, " +
                "the structural integrity of the plan is broken."
              throw new TreeNodeException(result, message, null)
            }

            result
        }
        iteration += 1
        if (iteration > batch.strategy.maxIterations) {
          // Only log if this is a rule that is supposed to run more than once.
          if (iteration != 2) {
            val message = s"Max iterations (${iteration - 1}) reached for batch ${batch.name}"
            if (Utils.isTesting) {
              throw new TreeNodeException(curPlan, message, null)
            } else {
              logWarning(message)
            }
          }
          continue = false
        }

        if (curPlan.fastEquals(lastPlan)) {
          logTrace(
            s"Fixed point reached for batch ${batch.name} after ${iteration - 1} iterations.")
          continue = false
        }
        lastPlan = curPlan
      }

      if (!batchStartPlan.fastEquals(curPlan)) {
        logDebug(
          s"""
            |=== Result of Batch ${batch.name} ===
            |${sideBySide(batchStartPlan.treeString, curPlan.treeString).mkString("\n")}
          """.stripMargin)
      } else {
        logTrace(s"Batch ${batch.name} has no effect.")
      }
    }

    curPlan
  }
```

















# 不得不说的TreeNode

首先来看spark中TreeNode的定义

```scala
abstract class TreeNode[BaseType <: TreeNode[BaseType]] extends Product {
  self: BaseType =>
    
  def children: Seq[BaseType]
  def transformUp(rule: PartialFunction[BaseType, BaseType]): BaseType = {...}
  def transformDown(rule: PartialFunction[BaseType, BaseType]): BaseType = {...}
  def transform(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    transformDown(rule)
  }
...
}
```

TreeNode本身是一个抽象类,定义了成员变量以及一些对NodeType操作的方法。

catalyst的哲学带点函数式的色彩，认为query plan是immutable会比较好处理，不用考虑很多杂七杂八的事情。







## 成员

成员包括如下的一些



## 继承结构

**这里缺继承关系的图**

### Expression

> `Expression` is a executable [node](https://jaceklaskowski.gitbooks.io/mastering-spark-sql/spark-sql-catalyst-TreeNode.html) (in a Catalyst tree) that can [evaluate](https://jaceklaskowski.gitbooks.io/mastering-spark-sql/spark-sql-Expression.html#eval) a result value given input values, i.e. can produce a JVM object per `InternalRow`.

简单来说,Expression通常指不需要触发执行引擎而能够直接计算的单元，比如简单的四则运算、逻辑判断等等.

- Expression通常是一个可以执行的node.例如我们sql中常见的一些关键字IF、SUM、COALESCE等等，都会被解析成一个个的Expression.
- Expression本身同样是一个抽象类,会有很多不同的实现.
- Exprssion是对InternalRow的加工，InternalRow可以理解为一行的数据.

下图展示了Expression的几种重要的实现，我们来进行介绍

#### LeafExpression

没有孩子节点的expression，即叶子节点.常见的叶子节点有CurrentDate、Pi、Uuid等

#### UnaryExpression

只有一个孩子节点的expression,即一元表达式这种类型的expression比较常见,有Month、ABS等等

#### BnaryExpression

有2个孩子节点的expression,即二元表达式,这种类型的expression也比较多，有ADD、ROUND等等

#### TernaryExpression

有3个孩子节点的expression,即三元表达式，这种类型的expression比较少，有SubString、RegExpReplace











#### internalRow



http://www.voidcn.com/article/p-vvuwhsgs-bb.html

Currently, we use GenericRow both for Row and InternalRow, which is confusing because it could contain Scala type also Catalyst types.

We should have different implementation for them, to avoid some potential bugs.











### QueryPlan

























Scala 偏函数:

http://twitter.github.io/scala_school/zh_cn/pattern-matching-and-functional-composition.html#PartialFunction



treenode体系：

https://jaceklaskowski.gitbooks.io/mastering-spark-sql/spark-sql-catalyst-TreeNode.html



=>用法：http://orchome.com/401



treenode解析中文版:https://segmentfault.com/a/1190000016294010



http://dataunion.org/11019.html



偏函数：

https://www.jianshu.com/p/0a8a15dbb348

http://twitter.github.io/scala_school/zh_cn/pattern-matching-and-functional-composition.html#PartialFunction





代码理解问题:

https://stackoverflow.com/questions/33062753/how-to-explain-treenode-type-restriction-and-self-type-in-sparks-treenode

```scala
abstract class TreeNode[BaseType <: TreeNode[BaseType]] extends Product {
  self: BaseType =>
  ...
}
```



`BaseType` is the base type of a tree and in Spark SQL can be:

- [LogicalPlan](https://jaceklaskowski.gitbooks.io/mastering-spark-sql/spark-sql-LogicalPlan.html) for logical plan trees
- [SparkPlan](https://jaceklaskowski.gitbooks.io/mastering-spark-sql/spark-sql-SparkPlan.html) for physical plan trees
- [Expression](https://jaceklaskowski.gitbooks.io/mastering-spark-sql/spark-sql-Expression.html) for expression trees







TreeNode的继承结构主要分为Expression和QueryPlan,QueryPlan又分为LogicalPlan和SparkPlan























