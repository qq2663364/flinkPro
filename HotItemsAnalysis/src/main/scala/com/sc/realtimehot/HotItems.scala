package com.sc.realtimehot

import com.sun.jmx.snmp.Timestamp
import org.apache.flink.api.common.functions.AggregateFunction
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.java.tuple.Tuple
import org.apache.flink.api.java.tuple.Tuple1
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.scala.function.WindowFunction
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector
import scala.collection.mutable.ListBuffer

/**
  * @Autor sc
  * @DATE 0019 10:02
  */

case class UserBehavior(userId: Long, itemId: Long, categoryId: Int, behavior: String, timestamp: Long)

case class ItemViewCount(itemId: Long, windowEnd: Long, count: Long)

object HotItems {
  def main(args: Array[String]): Unit = {
    //创建一个StreamExecutionEnvironment
    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    //设定Time类型为Eventtime
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    // 为了打印到控制台的结果不乱序，我们配置全局的并发为1，这里改变并发对结果正确性没有影响
    env.setParallelism(1)

    val dateDSteam: DataStream[String] = env.readTextFile("D:\\IdeaPro\\spark_gmall\\flink\\HotItemsAnalysis\\src\\main\\resources\\UserBehavior.csv")
    import org.apache.flink.api.scala._
    val userBehDS: DataStream[UserBehavior] = dateDSteam.map(line => {
      val lineArray: Array[String] = line.split(",")
      UserBehavior(lineArray(0).toLong, lineArray(1).toLong, lineArray(2).toInt, lineArray(3).toString, lineArray(4).toLong)
    })
    // 指定时间戳和watermark,*1000装换为毫秒
    userBehDS.assignAscendingTimestamps(_.timestamp * 1000)
      .filter(_.behavior == "pv")
      .keyBy("itemId")
      //定义窗口
      .timeWindow(Time.hours(1), Time.minutes(5))
      .aggregate(new CountAgg(), new WindowResultFunction())
      .keyBy("windowEnd")
      .process(new TopNHotItems(3))
      .print()

    env.execute("HotItems Jop")
  }

  // COUNT统计的聚合函数实现，每出现一条记录就加一
  class CountAgg() extends AggregateFunction[UserBehavior, Long, Long] {
    override def createAccumulator(): Long = 0L

    override def add(value: UserBehavior, accumulator: Long): Long = accumulator + 1

    override def getResult(accumulator: Long): Long = accumulator

    override def merge(a: Long, b: Long): Long = a + b
  }

  // 用于输出窗口的结果
  class WindowResultFunction() extends WindowFunction[Long, ItemViewCount, Tuple, TimeWindow] {
    override def apply(key: Tuple, window: TimeWindow, aggregateResult: Iterable[Long], out: Collector[ItemViewCount]): Unit = {
      val itemId: Long = key.asInstanceOf[Tuple1[Long]].f0
      val count: Long = aggregateResult.iterator.next()
      out.collect(ItemViewCount(itemId, window.getEnd, count))
    }
  }

  // 求某个窗口中前 N 名的热门点击商品，key 为窗口时间戳，输出为 TopN 的结果字符串
  class TopNHotItems(topSize: Int) extends KeyedProcessFunction[Tuple, ItemViewCount, String] {

    //定义状态listState
    private var itemState: ListState[ItemViewCount] = _

    override def open(parameters: Configuration): Unit = {
      super.open(parameters)
      //命名状态变量的名字和类型
      val itemStateDesc = new ListStateDescriptor[ItemViewCount]("itemState", classOf[ItemViewCount])
      itemState = getRuntimeContext.getListState(itemStateDesc)
    }

    override def processElement(input: ItemViewCount, context: KeyedProcessFunction[Tuple, ItemViewCount, String]#Context, collector: Collector[String]): Unit = {
      // 每条数据都保存到状态中
      itemState.add(input)
      // 注册 windowEnd+1 的 EventTime Timer, 当触发时，说明收齐了属于windowEnd窗口的所有商品数据
      // 也就是当程序看到windowend + 1的水位线watermark时，触发onTimer回调函数
      context.timerService.registerEventTimeTimer(input.windowEnd + 1)

    }

    override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[Tuple, ItemViewCount, String]#OnTimerContext, out: Collector[String]): Unit = {
      // 获取收到的所有商品点击量
      val allItems: ListBuffer[ItemViewCount] = ListBuffer()
      import scala.collection.JavaConversions._
      for (item <- itemState.get) {
        allItems += item
      }
      // 提前清除状态中的数据，释放空间
      itemState.clear()
      // 按照点击量从大到小排序
      val sortedItems = allItems.sortBy(_.count)(Ordering.Long.reverse).take(topSize)
      // 将排名信息格式化成 String, 便于打印
      val result: StringBuilder = new StringBuilder
      result.append("====================================\n")
      result.append("时间: ").append(new Timestamp(timestamp - 1)).append("\n")

      for (i <- sortedItems.indices) {
        val currentItem: ItemViewCount = sortedItems(i)
        // e.g.  No1：  商品ID=12224  浏览量=2413
        result.append("No").append(i + 1).append(":")
          .append("  商品ID=").append(currentItem.itemId)
          .append("  浏览量=").append(currentItem.count).append("\n")
      }
      result.append("====================================\n\n")
      // 控制输出频率，模拟实时滚动结果
      Thread.sleep(1000)
      out.collect(result.toString)

    }
  }

}
