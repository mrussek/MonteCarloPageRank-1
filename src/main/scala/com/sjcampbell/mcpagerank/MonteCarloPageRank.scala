package com.sjcampbell.mcpagerank

import org.apache.log4j._
import org.apache.hadoop.fs._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.rogach.scallop._


/*
 *  See Hadoop MapReduce implementation of this PageRank algorithm (Note how verbose it is compared to Spark) 
 *  https://github.com/lintool/bespin/blob/master/src/main/java/io/bespin/java/mapreduce/pagerank/RunPageRankBasic.java
 */
object MonteCarloPageRank {
  
    val log = Logger.getLogger(getClass().getName())
    
    // Probability that a surfer will jump to a random node = 0.15
    val randomJump = 0.15f
    
    def parseLine(line: String) : (Int, Array[Int]) = {
        val parts = line.split("\\s+")
        
        if (parts.length < 2)
            (parts(0).toInt, new Array[Int](0))
        else
            (parts(0).toInt, parts.drop(0).map(_.toInt))
    }
    
    // Adds two log probs.
    def sumLogProbs(a: Float, b: Float) : Float = {
        if (a == Float.NegativeInfinity)
          return b;
    
        if (b == Float.NegativeInfinity)
          return a;
    
        if (a < b) {
          return b + StrictMath.log1p(StrictMath.exp(a - b)).toFloat
        }
        
        return a + StrictMath.log1p(StrictMath.exp(b - a)).toFloat
    }
    
    def main(argv: Array[String]) {
        val args = new Conf(argv)
        val nodeCount = args.nodeCount()
        log.info("Input: " + args.input())
        log.info("Number of Nodes: " + nodeCount)
        log.info("Number of Iterations: " + args.iterations())
        log.info("Executors: " + args.numExecutors())
        
        val conf = new SparkConf().setAppName("BuildPageRankRecords")
        val sc = new SparkContext(conf)
        sc.setJobDescription("Takes an adjacency list and formats them into records that can be used by PageRank.")
        
        // Parse input adjacency list into (nodeID, Array[nodeId])
        val adjList = sc.textFile(args.input()).map(parseLine).cache()
        
        // Store PageRanks as log values, so that logarithmic arithmetic can be used to not lose precision on such small numbers.
        // Initialize ranks by setting them all to the log(1/nodeCount), which is equivalent to -log(nodeCount).
        val weight = -StrictMath.log(nodeCount).toFloat
        var ranks = adjList.mapValues(v => weight)
        
        for (i <- 0 to args.iterations()) {
            /* During each PR iteration, weight gets distributed evenly from each node to its neighbours.
             * To do this, join the current page ranks with the adjacency list, then distribute weights 
             * accordingly.
             */
            log.info("ITERATION: " + i)
            
            val missingMass = sc.accumulator(0f)
            
            val contributions = adjList.join(ranks).values.flatMap {
                case (neighbours, pageRank) => {
                    if (neighbours == null || neighbours.isEmpty) {
                        // This is a dangling node. So add the mass to a missing mass accumulator
                        missingMass += StrictMath.exp(pageRank).toFloat

                        // Return empty list that will get filtered out since we are in a flatmap function.
                        List[(Int, Float)]()
                    }
                    else {
                        // Divide a node's PageRank by the number of neighbours: ln(x) - ln(y) = ln(x/y)
                        val mass = pageRank - StrictMath.log(neighbours.size).toFloat
                        neighbours.map(neighbourId => (neighbourId, mass))
                    }
                }
            }
            
            /* Multiply each PageRank by (1 - randomJump) to scale it, considering it is the
             * probability that a link will be followed instead of jumping to a random node.
             * Add 'randomJump' to account for the probability that a random jump will occur to
             * that node.
             * Complete calculation by converting back to non-logarithmic PageRank: Math.exp(_)
             */

            // Jump = randomJump/nodeCount
            val jump = (StrictMath.log(randomJump) - StrictMath.log(nodeCount)).toFloat

            // Sum the page ranks for each node
            ranks = contributions.reduceByKey {
                case (val1, val2) => {
                    (sumLogProbs(val1, val2))
                }
            }
            
            val missing = missingMass.value

            // Distribute the missing mass from the dangling nodes across all nodes.
            // Also account for the random jump factor.
            ranks = ranks.map {
                case (nodeId, rank) => {
                    // Mass to distribute = (1 - randomJump) * (currentPageRank + missingMass/nodeCount)
                    val link =  StrictMath.log(1.0f - randomJump).toFloat + sumLogProbs(rank, (StrictMath.log(missing) - StrictMath.log(nodeCount)).toFloat)
                    (nodeId, sumLogProbs(jump, link))
                }
            }
        }

        ranks.sortBy((nodeRank) => { nodeRank._2 }, false, 1)
            .take(100)
            .foreach {
                case (nodeId, pageRank) => {
                    println(nodeId, pageRank)
                }
            }
    }
}