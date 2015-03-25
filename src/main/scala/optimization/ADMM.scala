package optimization

import com.github.fommil.netlib.BLAS

import org.apache.spark.mllib.optimization.{Optimizer,Gradient}
import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.{Vector,Vectors,DenseVector}
import org.apache.spark.mllib.rdd.RDDFunctions._

import scala.collection.mutable.ArrayBuffer

import optimization.{Updater=>DistUpdater}

/**
 * Created by diego on 1/28/15.
 */

class ADMM(private var gradient: Gradient, private var updater: DistUpdater) extends Optimizer with Logging {
  private var numIterations: Int = 2
  private var regParam: Double = 1.0
  private var stepSize: Double = 1.0
  private var rho: Double = 0.0001
  private var stepSizeFunction: Option[(Int) => Double] = None

  /*
   *  Set the step size function
   *  default: 1 / (1 + regParam * sqrt(t)) if regParam > 0
   *  if regParam = 0, then 1/sqrt(t)
   */

  private val stepSizeFunctionWithReg = (iterCount:Int) => {
    1.0 / (1.0 + this.regParam * math.sqrt(iterCount))
  }
  private val stepSizeFunctionNoReg = (iterCount:Int) => {
    1.0 / math.sqrt(iterCount)
  }

  def setStepSizeFunction(func: (Int) => Double): this.type = {
    this.stepSizeFunction = Some(func)
    this
  }

  /*
   * set rho in ADMM; default = 0.0001
   */

  def setRho(value: Double): this.type = {
    this.rho = value
    this
  }

  /*
   * Set the number of distributed iterations; default = 10
   */

  def setNumIterations(iters: Int): this.type = {
    this.numIterations = iters
    this
  }

  /*
   * Set the regularization parameter. Default 0.0.
   */

  def setRegParam(regParam: Double): this.type = {
    this.regParam = regParam
    this
  }

  /*
   * Set the initial step size of SGD for the first step. Default 1.0.
   * In subsequent steps, the step size will decrease with stepSize/sqrt(t)
   */

  def setStepSize(step: Double): this.type = {
    this.stepSize = step
    this
  }

  /*
   * Set the gradient function (of the loss function of one single data example)
   */

  def setGradient(gradient: Gradient): this.type = {
    this.gradient = gradient
    this
  }

  /*
   * Set the updater function to actually perform a gradient step in a given direction.
   * The updater is responsible to perform the update from the regularization term as well,
   * and therefore determines what kind or regularization is used, if any.
   */

  def setUpdater(updater: DistUpdater): this.type = {
    this.updater = updater
    this
  }

  def optimize(data: RDD[(Double, Vector)], initialWeights: Vector): Vector = {
    val stepFunction = this.stepSizeFunction match {
      case Some(func) => func
      case None => if(regParam > 0) stepSizeFunctionWithReg else stepSizeFunctionNoReg
    }
    val (weights, _) = ADMM.runADMM(
      data,
      gradient,
      updater,
      stepSize,
      rho,
      stepFunction,
      numIterations,
      regParam,
      initialWeights)
    weights
  }
}

/*
 * run distributed admm
 * return: weights, loss in each iteration
 * loss = sum of losses for each record based on current weight at that local iteration + regularization value of the weight for the next iteration
 */

object ADMM extends Logging {
  def runADMM(
                       data: RDD[(Double, Vector)],
                       gradient: Gradient,
                       updater: DistUpdater,
                       stepSize: Double,
                       rho: Double,
                       stepSizeFunction: (Int) => Double,
                       numIterations: Int,
                       regParam: Double,
                       initialWeights: Vector): (Vector, Array[Double]) = {

    val stochasticLossHistory = new ArrayBuffer[Double](numIterations)

    // these are the final weights; in the algorithm they are the average weights across all partitions
    var weights = Vectors.dense(initialWeights.toArray)

    val numberOfFeatures = weights.size
    val noPartitions = data.partitions.length
    val zeroVector = Vectors.dense(new Array[Double](numberOfFeatures))

    /*
     * the first iteration: corresponds to regular single iteration of IPA
     */

    // for each partition in the rdd
    val oneIterRdd = data.mapPartitionsWithIndex{ case (idx,iter) =>
      var w = Vectors.dense(new Array[Double](numberOfFeatures))
      var iterCount = 1
      var loss = 0.0
      while(iter.hasNext) {
        val (label,features) = iter.next()
        // gradient
        val (newGradient,newLoss) = gradient.compute(features, label, w)
        // update current point
        val (w1,_) = updater.compute(w, newGradient, stepSize, stepSizeFunction, iterCount, regParam)
        loss += newLoss
        w = w1
        iterCount += 1
      }
      List((w,loss,iterCount-1,idx)).iterator
    }

    /*
     * iterations
     */

    // broadcast number of records per partition
    val noRecordsPerPartition = Array.fill[Int](noPartitions)(0)
    oneIterRdd.map{ t => (t._4,t._3) }.collect().foreach{ case(idx,value) => noRecordsPerPartition(idx) = value }
    val bCastNoRecords = data.context.broadcast(noRecordsPerPartition)
    // array of penalties; one penalty vector per partition; we store them as single vector
    var penalties = Vectors.zeros(noPartitions*numberOfFeatures)
    // penalties corresponding to the regularization term
    var regularizationPenalties = Vectors.zeros(numberOfFeatures)
    // solution corresponding to the regularization term; it is obtained explicitly
    var regularizationWeights = Vectors.zeros(numberOfFeatures)

    // prevIterRdd: for each partition (record in the rdd) it has: solution (partition) weights,loss,partition id
    def runOneIteration(j:Int,prevIterRdd:RDD[(Vector,Double,Int)]): Unit = {
      if (j < 0) return

      /*
       * average solution
       */

      val (sumWeight,totalLoss) = prevIterRdd.map{ t => (t._1,t._2) }.treeAggregate((Vectors.zeros(numberOfFeatures), 0.0))(
        seqOp = (c, v) => {
          // c: (weight,loss), v: (one weight vector, one loss)
          // should be this if mllib.BLAS accessible: org.apache.spark.mllib.linalg.BLAS.axpy(1.0,v._1,c._1)
          BLAS.getInstance().daxpy(numberOfFeatures,1.0,v._1.asInstanceOf[DenseVector].values,1,c._1.asInstanceOf[DenseVector].values,1)
          (c._1,v._2+c._2)
        },
        combOp = (c1, c2) => {
          // c: (weight,loss)
          // should be this if mllib.BLAS accessible: org.apache.spark.mllib.linalg.BLAS.axpy(1.0,c2._1,c1._1)
          BLAS.getInstance().daxpy(numberOfFeatures,1.0,c2._1.asInstanceOf[DenseVector].values,1,c1._1.asInstanceOf[DenseVector].values,1)
          (c1._1, c1._2 + c2._2)
        })

      // adjust the average to account for the regularization term
      BLAS.getInstance().daxpy(numberOfFeatures,1.0,regularizationWeights.asInstanceOf[DenseVector].values, 1, sumWeight.asInstanceOf[DenseVector].values,1)
      // divide the sum to get the average
      BLAS.getInstance().dscal(numberOfFeatures,1.0/(noPartitions+1),sumWeight.asInstanceOf[DenseVector].values,1)
      weights = sumWeight

      // to compute the regularization value
      val regVal = updater.compute(weights, zeroVector, 0, x => x, 1, regParam)._2

      stochasticLossHistory.append(totalLoss+regParam*regVal)

      // broadcast weights; in a single iteration these are the average weights
      val bcWeights = data.context.broadcast(weights)
      val bcPenalties = data.context.broadcast(penalties)

      /*
       * update penalties
       */

      // update penalties on each record in the rdd
      val pen = prevIterRdd.map{ case(partitionWeight,_,idx) =>
        val w = bcWeights.value
        val p = bcPenalties.value
        val penalties = Vectors.dense(numberOfFeatures)
        // penalty = penalty + rho(partitionWeight - w)
        BLAS.getInstance().daxpy(w.size, rho, partitionWeight.asInstanceOf[DenseVector].values, 0, 1, p.asInstanceOf[DenseVector].values, numberOfFeatures*idx, 1)
        BLAS.getInstance().daxpy(w.size, -rho, w.asInstanceOf[DenseVector].values, 0, 1, p.asInstanceOf[DenseVector].values, numberOfFeatures*idx, 1)
        // set all other values in p to be zero; needed for later doing the sum
        BLAS.getInstance().dscal(numberOfFeatures*idx,0.0,p.asInstanceOf[DenseVector].values,1)
        BLAS.getInstance().dscal(p.size-numberOfFeatures*(idx+1),0.0,p.asInstanceOf[DenseVector].values,numberOfFeatures*(idx+1),1)
        p
      }

      // we need to gather all of them (we sum them)
      penalties = pen.treeAggregate(Vectors.zeros(penalties.size))(
        seqOp = (c, v) => {
          BLAS.getInstance().daxpy(c.size,1.0,c.asInstanceOf[DenseVector].values,1,v.asInstanceOf[DenseVector].values,1)
          v
        },
        combOp = (c1, c2) => {
          BLAS.getInstance().daxpy(c1.size,1.0,c1.asInstanceOf[DenseVector].values,1,c2.asInstanceOf[DenseVector].values,1)
          c2
        }
      )

      bcPenalties.destroy()

      // update penalty for regularization term
      BLAS.getInstance().daxpy(numberOfFeatures,rho,regularizationWeights.asInstanceOf[DenseVector].values,1,regularizationPenalties.asInstanceOf[DenseVector].values,1)
      BLAS.getInstance().daxpy(numberOfFeatures,-rho,weights.asInstanceOf[DenseVector].values,1,regularizationPenalties.asInstanceOf[DenseVector].values,1)

      /*
       * compute new weights for each partition
       */

      val bcNewPenalties = data.context.broadcast(penalties)

      val oneIterRdd = data.mapPartitionsWithIndex{ case (idx,iter) =>
        var w = bcWeights.value
        val averageWeight = bcWeights.value
        val noRecords = bCastNoRecords.value(idx)
        val factor = rho/noRecords
        val penalties = bcNewPenalties.value
        var iterCount = 1
        var loss = 0.0
        // gradient = loss gradient + penalties/noRecords + rho/noRecords * (w - averageWeight)
        while(iter.hasNext) {
          val (label,features) = iter.next()
          // gradient
          val (newGradient,newLoss) = gradient.compute(features, label, w)
          // adjust with penalties
          BLAS.getInstance().daxpy(numberOfFeatures,1.0/noRecords,penalties.asInstanceOf[DenseVector].values,numberOfFeatures*idx,1,newGradient.asInstanceOf[DenseVector].values,0,1)
          // adjust for the averaging factor
          BLAS.getInstance().daxpy(numberOfFeatures,factor,w.asInstanceOf[DenseVector].values,1,newGradient.asInstanceOf[DenseVector].values,1)
          BLAS.getInstance().daxpy(numberOfFeatures,factor,averageWeight.asInstanceOf[DenseVector].values,1,newGradient.asInstanceOf[DenseVector].values,1)
          // update current point; note that regularization in this case must be excluded
          val (w1,_) = updater.compute(w, newGradient, stepSize, stepSizeFunction, iterCount, 0.0)
          loss += newLoss
          w = w1
          iterCount += 1
        }
        List((w,loss,idx)).iterator
      }

      bcWeights.destroy()
      bcNewPenalties.destroy()

      // compute regularization solution; explicit formula = (rho * average - regularization penalty)/(rho + regParam)
      regularizationWeights = Vectors.zeros(numberOfFeatures)
      BLAS.getInstance().daxpy(numberOfFeatures,-1.0/(rho+regParam),regularizationPenalties.asInstanceOf[DenseVector].values,1,regularizationWeights.asInstanceOf[DenseVector].values,1)
      BLAS.getInstance().daxpy(numberOfFeatures,rho/(rho+regParam),weights.asInstanceOf[DenseVector].values,1,regularizationWeights.asInstanceOf[DenseVector].values,1)

      runOneIteration(j-1,oneIterRdd)
    }

    runOneIteration(numIterations-2,oneIterRdd.map{ t => (t._1,t._2,t._4) })

    bCastNoRecords.destroy()

    logInfo("ADMM finished. Last 10 losses %s".format(stochasticLossHistory.takeRight(10).mkString(", ")))

    (weights,stochasticLossHistory.toArray)
  }
}
