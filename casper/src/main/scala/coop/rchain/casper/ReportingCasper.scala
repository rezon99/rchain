package coop.rchain.casper

import cats.data.EitherT
import cats.{FlatMap, Monad, Parallel}
import cats.effect.concurrent.{MVar, Ref}
import cats.implicits._
import cats.effect.{Concurrent, ContextShift, Sync}
import cats.mtl.FunctorTell
import cats.temp.par
import cats.temp.par.{Par => CatsPar}
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, BlockDagStorage}
import coop.rchain.casper.ReportingCasper.Report
import coop.rchain.casper.protocol.{BlockMessage, DeployData}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager.{ReplayFailure, StateHash}
import coop.rchain.casper.util.rholang.{
  DeployStatus,
  Failed,
  InternalErrors,
  InternalProcessedDeploy,
  ReplayStatusMismatch,
  RuntimeManager,
  UnusedCommEvent,
  UserErrors
}
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.metrics.Metrics.Source
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.{
  BindPattern,
  Bundle,
  EVar,
  Expr,
  ListParWithRandom,
  Par,
  ParMap,
  SortedParMap,
  TaggedContinuation
}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.TaggedContinuation.TaggedCont.ScalaBodyRef
import coop.rchain.models.Validator.Validator
import coop.rchain.models.Var.VarInstance.FreeVar
import coop.rchain.models.rholang.implicits._
import coop.rchain.rholang.RholangMetricsSource
import coop.rchain.rholang.interpreter.{
  DeployParameters,
  ErrorLog,
  EvaluateResult,
  HasCost,
  Interpreter,
  NormalizerEnv,
  Reduce,
  RhoType,
  RholangAndScalaDispatcher,
  Runtime,
  SystemProcesses
}
import coop.rchain.rholang.interpreter.Runtime.{
  setupMapsAndRefs,
  setupReducer,
  stdSystemProcesses,
  Arity,
  BlockData,
  BodyRef,
  BodyRefs,
  FixedChannels,
  InvalidBlocks,
  Name,
  Remainder,
  RhoDispatch,
  RhoDispatchMap,
  RhoHistoryRepository,
  RhoReplayISpace,
  RhoTuplespace,
  SystemProcess
}
import coop.rchain.rholang.interpreter.accounting.{_cost, Cost, CostAccounting}
import coop.rchain.rholang.interpreter.registry.RegistryBootstrap
import coop.rchain.rspace.{
  Blake2b256Hash,
  HotStore,
  RSpace,
  ReplayException,
  ReplayRSpace,
  Match => RSpaceMatch
}
import coop.rchain.rspace.history.Branch
import monix.execution.atomic.AtomicAny
import coop.rchain.rholang.interpreter.storage._
import coop.rchain.shared.Log

import scala.concurrent.ExecutionContext

trait ReportingCasper[F[_]] {
  def trace(hash: BlockHash): F[Report]
}

object ReportingCasper {

  sealed trait RhoEvent

  final case class DummyConsume() extends RhoEvent

  final case class DeployTrace(hash: String, events: List[RhoEvent])

  sealed trait Report

  final case class BlockNotFound(hash: String) extends Report

  final case class BlockTracesReport(hash: String, traces: List[DeployTrace]) extends Report

  def noop[F[_]: Sync]: ReportingCasper[F] = new ReportingCasper[F] {
    override def trace(hash: BlockHash): F[Report] =
      Sync[F].delay(BlockNotFound(hash.toStringUtf8))
  }

  def rhoReporter[F[_]: ContextShift: Monad: Concurrent: Log: Metrics: Span: CatsPar: BlockStore: BlockDagStorage](
      historyRepository: RhoHistoryRepository[F]
  )(implicit scheduler: ExecutionContext) =
    new ReportingCasper[F] {
      val codecK                                                     = serializeTaggedContinuation.toCodec
      implicit val m: RSpaceMatch[F, BindPattern, ListParWithRandom] = matchListPar[F]

      override def trace(hash: BlockHash): F[Report] =
        for {
          replayStore <- HotStore.empty(historyRepository)(codecK, Concurrent[F])
          replay = new ReplayRSpace[F, Par, BindPattern, ListParWithRandom, TaggedContinuation](
            historyRepository,
            AtomicAny(replayStore),
            Branch.REPLAY
          )
          r     <- ReportingRuntime.createWithEmptyCost[F](replay, Seq.empty)
          rm    <- fromRuntime(r)
          dag   <- BlockDagStorage[F].getRepresentation
          bmO   <- BlockStore[F].get(hash)
          state <- bmO.traverse(bm => replayBlock(bm, dag, rm))
          _     = println("state " + state)
        } yield BlockNotFound("yeah Im here, what of it?")
    }

  private def replayBlock[F[_]: Sync: BlockStore](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      runtimeManager: ReportingRuntimeManagerImpl[F]
  ): F[Either[ReplayFailure, StateHash]] = {
    val hash        = ProtoUtil.preStateHash(block)
    val deploys     = block.body.deploys.map(InternalProcessedDeploy.fromProcessedDeploy)
    val timestamp   = block.header.timestamp
    val blockNumber = block.body.state.blockNumber
    runtimeManager.replayComputeState(hash)(deploys, BlockData(timestamp, blockNumber))
  }

  def fromRuntime[F[_]: Concurrent: Sync: Metrics: Span: Log](
      runtime: ReportingRuntime[F]
  ): F[ReportingRuntimeManagerImpl[F]] =
    for {
      _                <- runtime.replaySpace.clear()
      _                <- Runtime.bootstrapRegistry(runtime, runtime.replayReducer :: Nil)
      replayCheckpoint <- runtime.replaySpace.createCheckpoint()
      replayHash       = ByteString.copyFrom(replayCheckpoint.root.bytes.toArray)
      runtime          <- MVar[F].of(runtime)
    } yield new ReportingRuntimeManagerImpl(replayHash, runtime)

  class ReportingRuntimeManagerImpl[F[_]: Concurrent: Metrics: Span: Log](
      val emptyStateHash: StateHash,
      runtimeContainer: MVar[F, ReportingRuntime[F]]
  ) {

    def replayComputeState(startHash: StateHash)(
        terms: Seq[InternalProcessedDeploy],
        blockData: BlockData
    ): F[Either[ReplayFailure, StateHash]] =
      Sync[F].bracket {
        runtimeContainer.take
      } { runtime =>
        replayDeploys(runtime, startHash, terms, replayDeploy(runtime))
      }(runtimeContainer.put)

    private def replayDeploys(
        runtime: ReportingRuntime[F],
        startHash: StateHash,
        terms: Seq[InternalProcessedDeploy],
        replayDeploy: InternalProcessedDeploy => F[Option[ReplayFailure]]
    ): F[Either[ReplayFailure, StateHash]] =
      (for {
        _ <- EitherT.right(runtime.replaySpace.reset(Blake2b256Hash.fromByteString(startHash)))
        _ <- EitherT(
              terms.tailRecM { ts =>
                if (ts.isEmpty)
                  ().asRight[ReplayFailure].asRight[Seq[InternalProcessedDeploy]].pure[F]
                // prune failed deploys
                else replayDeploy(ts.head).map(_.map(_.asLeft[Unit]).toRight(ts.tail))
              }
            )
        res <- EitherT.right[ReplayFailure](runtime.replaySpace.createCheckpoint())
      } yield res.root.toByteString).value

    private def replayDeploy(runtime: ReportingRuntime[F])(
        processedDeploy: InternalProcessedDeploy
    ): F[Option[ReplayFailure]] = {
      import processedDeploy._
      for {
        _              <- runtime.replaySpace.rig(processedDeploy.deployLog)
        softCheckpoint <- runtime.replaySpace.createSoftCheckpoint()
        replayEvaluateResult <- computeEffect(runtime, runtime.replayReducer)(
                                 processedDeploy.deploy
                               )
        EvaluateResult(_, errors) = replayEvaluateResult
        cont <- DeployStatus.fromErrors(errors) match {
                 case int: InternalErrors =>
                   (deploy.some, int: Failed).some.pure[F]
                 case replayStatus =>
                   if (status.isFailed != replayStatus.isFailed)
                     (deploy.some, ReplayStatusMismatch(replayStatus, status): Failed).some
                       .pure[F]
                   else if (errors.nonEmpty)
                     runtime.replaySpace
                       .revertToSoftCheckpoint(softCheckpoint) >> none[ReplayFailure]
                       .pure[F]
                   else {
                     runtime.replaySpace
                       .checkReplayData()
                       .attempt
                       .flatMap {
                         case Right(_) => none[ReplayFailure].pure[F]
                         case Left(ex: ReplayException) => {
                           Log[F]
                             .error(s"Failed during processing of deploy: ${processedDeploy}") >>
                             (none[DeployData], UnusedCommEvent(ex): Failed).some
                               .pure[F]
                         }
                         case Left(ex) =>
                           (none[DeployData], UserErrors(Vector(ex)): Failed).some
                             .pure[F]
                       }
                   }
               }
      } yield cont
    }

    private def computeEffect(runtime: ReportingRuntime[F], reducer: Reduce[F])(
        deploy: DeployData
    ): F[EvaluateResult] =
      for {
        _      <- runtime.deployParametersRef.set(ProtoUtil.getRholangDeployParams(deploy))
        result <- RuntimeManager.doInj(deploy, reducer, runtime.errorLog)(Sync[F], runtime.cost)
      } yield result
  }
}

class ReportingRuntime[F[_]: Sync](
    val replayReducer: Reduce[F],
    val replaySpace: RhoReplayISpace[F],
    val errorLog: ErrorLog[F],
    val cost: _cost[F],
    val deployParametersRef: Ref[F, DeployParameters],
    val blockData: Ref[F, BlockData],
    val invalidBlocks: Runtime.InvalidBlocks[F]
) extends HasCost[F] {
  def readAndClearErrorVector(): F[Vector[Throwable]] = errorLog.readAndClearErrorVector()
  def close(): F[Unit] =
    for {
      _ <- replaySpace.close()
    } yield ()
}

object ReportingRuntime {
  implicit val RuntimeMetricsSource: Source = Metrics.Source(RholangMetricsSource, "runtime")

  def createWithEmptyCost[F[_]: Concurrent: Log: Metrics: Span: par.Par](
      reporting: RhoReplayISpace[F],
      extraSystemProcesses: Seq[SystemProcess.Definition[F]] = Seq.empty
  ): F[ReportingRuntime[F]] = {
    implicit val P = par.Par[F].parallel
    for {
      cost <- CostAccounting.emptyCost[F]
      runtime <- {
        implicit val c = cost
        create(reporting, extraSystemProcesses)
      }
    } yield runtime
  }

  def create[F[_]: Concurrent: Log: Metrics: Span, M[_]](
      reporting: RhoReplayISpace[F],
      extraSystemProcesses: Seq[SystemProcess.Definition[F]] = Seq.empty
  )(
      implicit P: Parallel[F, M],
      cost: _cost[F]
  ): F[ReportingRuntime[F]] = {
    val errorLog                               = new ErrorLog[F]()
    implicit val ft: FunctorTell[F, Throwable] = errorLog
    for {
      mapsAndRefs                                                          <- setupMapsAndRefs(extraSystemProcesses)
      (deployParametersRef, blockDataRef, invalidBlocks, urnMap, procDefs) = mapsAndRefs
      reducer = setupReducer(
        ChargingRSpace.chargingRSpace[F](reporting),
        deployParametersRef,
        blockDataRef,
        invalidBlocks,
        extraSystemProcesses,
        urnMap
      )
      res <- Runtime.introduceSystemProcesses(reporting :: Nil, procDefs)
    } yield {
      assert(res.forall(_.isEmpty))
      new ReportingRuntime[F](
        reducer,
        reporting,
        errorLog,
        cost,
        deployParametersRef,
        blockDataRef,
        invalidBlocks
      )
    }
  }
}
