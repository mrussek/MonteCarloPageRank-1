package ca.uwaterloo.cs.bigdata2016w.sjcampbell.assignment5

import org.rogach.scallop._

class Conf(args: Seq[String]) extends ScallopConf(args) {
  mainOptions = Seq(input, numExecutors)
  val input = opt[String](descr = "input path", required = true)
  val numExecutors = opt[Int](descr = "number of executors", required = false, default = Some(1))
  val date = opt[String](descr = "date", required = false)
}