package ca.uwaterloo.cs.bigdata2016w.sjcampbell.assignment2

import collection.mutable.HashMap

import org.apache.log4j._
import org.apache.hadoop.fs._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.Partitioner
import org.rogach.scallop._

object ComputeBigramRelativeFrequencyPairs extends Tokenizer {
  val log = Logger.getLogger(getClass().getName())

  // Partition based on first word in key of (String, String) 
  class FirstWordPartitioner(numParts: Int) extends Partitioner {
    def numPartitions: Int = numParts

    def getPartition(key: Any): Int = {
      val pair = key.asInstanceOf[(String, String)]
      (pair._1.hashCode() & Int.MaxValue) % numParts
    }
  }
  
  def partitionMapper(pairCounts : Iterator[((String, String), Int)]) : Iterator[((String, String), Float)] = {
    var sum = 0F
    var res = List[((String, String), Int)]()
    
    // Could this use a map?
    pairCounts.map(pairCount => {
      if (pairCount._1.asInstanceOf[(String, String)]._2 == "*"){
        sum = pairCount._2
        (pairCount._1, sum)
      }
      else {
        if (sum == 0) { println("ERROR: Divide by zero imminent!") }
        
        (pairCount._1, pairCount._2 / sum)
      }
    })
  }
  
  def main(argv: Array[String]) {
    val args = new Conf(argv)

    log.info("Input: " + args.input())
    log.info("Output: " + args.output())
    log.info("Number of reducers: " + args.reducers())

    val conf = new SparkConf().setAppName("Bigram Relative Frequency - Pairs")
    val sc = new SparkContext(conf)
    sc.setJobDescription("Computing bigram relative frequency using pairs")

    val outputDir = new Path(args.output())
    FileSystem.get(sc.hadoopConfiguration).delete(outputDir, true)

    val textFile = sc.textFile(args.input())
    textFile.flatMap (line => {
      val tokens = tokenize(line)
      if (tokens.length > 1) {
        tokens.sliding(2).flatMap(p => 
          List(
                ((p(0), "*"), 1),
                ((p(0), p(1)), 1)
              )
          )
      }
      else List()
    })
    // RDD[(String, String), Int]  Next: Combine
    .reduceByKey(new FirstWordPartitioner(args.reducers()), _ + _)
    
    // RDD[(String, String), Int] Next: Shuffle/sort
    .repartitionAndSortWithinPartitions(new FirstWordPartitioner(args.reducers()))

    // RDD[(String, String, Int] Next: Calculate relative frequency 
    .mapPartitions(partitionMapper, true) 

    // Returns RDD[((String, String), Double)]
    .saveAsTextFile(args.output())
    
    println("!!! Job Completed !!!")
  }
}