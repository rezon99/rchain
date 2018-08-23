package coop.rchain.casper

import cats.{Applicative, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.EquivocationRecord.SequenceNumber
import coop.rchain.casper.Estimator.{BlockHash, Validator}
import coop.rchain.casper.protocol.{BlockMessage, Bond, Justification}
import coop.rchain.casper.util.{DoublyLinkedDag, ProtoUtil}
import coop.rchain.casper.util.ProtoUtil.{
  bonds,
  findCreatorJustificationAncestorWithSeqNum,
  toLatestMessages
}

import scala.collection.mutable

/**
  * A summary of the neglected equivocation algorithm is as follows.
  *
  * Every equivocation has one "base equivocation block" and multiple "children equivocation blocks" where the
  * "children equivocation blocks" have a sequence number that is one greater than the "base equivocation block".
  * To detect neglected equivocations, we keep a set of "equivocation record"s. An "equivocation record" is a tuple
  * containing equivocator's ID, the sequence number of the equivocation base block and a set of block hashes of blocks
  * that point to enough evidence to slash an equivocation corresponding to the "equivocation record".
  * Each time we discover an equivocation, we add a new "equivocation record" entry to the set with the validator's ID
  * and the base equivocation block's sequence number filled in. Each time we add a block to our view,
  * we loop through our "equivocations record"s and see if the block we want to add has enough information to detect
  * the equivocation corresponding to the "equivocation record". There are three cases:
  *
  * Case 1) The block has enough information and the block contains the equivocator in its justification,
  *         we slash the creator of that block
  * Case 2) The block has enough information and the block properly has rotated out the equivocator from its
  *         justification, we update the "equivocation record" so that the set contains this block.
  * Case 3) The block doesn't have enough information and so we do nothing.
  *
  * To ascertain whether a block has enough information to detect a particular equivocation, we loop through the
  * block's justifications and accumulate a set of children equivocation blocks that are reachable from
  * the block's justifications. If at any point while looping through the block's justifications, if we come across a
  * justification block that is in the set of block hashes, we immediately ascertain the block has enough information
  * to detect the equivocation corresponding to the "equivocation record". If at any point the set of children
  * equivocation blocks becomes larger than one in size, we also immediately ascertain the block has enough information
  * to detect the equivocation corresponding to the "equivocation record".
  */
sealed trait EquivocationDiscoveryStatus
case object EquivocationNeglected extends EquivocationDiscoveryStatus
case object EquivocationDetected  extends EquivocationDiscoveryStatus
case object EquivocationOblivious extends EquivocationDiscoveryStatus

// This is the sequence number of the equivocator's base block
case class EquivocationRecord(equivocator: Validator,
                              equivocationBaseBlockSeqNum: SequenceNumber,
                              equivocationDetectedBlockHashes: Set[BlockHash])

object EquivocationRecord {
  type SequenceNumber = Int
}

object EquivocationDetector {
  def checkEquivocations(blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
                         block: BlockMessage,
                         dag: BlockDag): Either[InvalidBlock, ValidBlock] = {
    val maybeCreatorJustification   = creatorJustificationHash(block)
    val maybeLatestMessageOfCreator = dag.latestMessages.get(block.sender)
    val isNotEquivocation           = maybeCreatorJustification == maybeLatestMessageOfCreator
    if (isNotEquivocation) {
      Right(Valid)
    } else if (requestedAsDependency(block, blockBufferDependencyDag)) {
      Left(AdmissibleEquivocation)
    } else {
      Left(IgnorableEquivocation)
    }
  }

  private def requestedAsDependency(block: BlockMessage,
                                    blockBufferDependencyDag: DoublyLinkedDag[BlockHash]): Boolean =
    blockBufferDependencyDag.parentToChildAdjacencyList.contains(block.blockHash)

  private def creatorJustificationHash(block: BlockMessage): Option[BlockHash] =
    for {
      maybeCreatorJustification <- ProtoUtil.creatorJustification(block)
    } yield maybeCreatorJustification.latestBlockHash

  // See summary of algorithm above
  def checkNeglectedEquivocationsWithUpdate[F[_]: Monad: BlockStore](
      equivocationsTracker: mutable.Set[EquivocationRecord],
      block: BlockMessage,
      dag: BlockDag): F[Either[InvalidBlock, ValidBlock]] =
    for {
      neglectedEquivocationDetected <- isNeglectedEquivocationDetectedWithUpdate[F](
                                        equivocationsTracker,
                                        block,
                                        dag)
      status = if (neglectedEquivocationDetected) {
        Left(NeglectedEquivocation)
      } else {
        Right(Valid)
      }
    } yield status

  private def isNeglectedEquivocationDetectedWithUpdate[F[_]: Monad: BlockStore](
      equivocationsTracker: mutable.Set[EquivocationRecord],
      block: BlockMessage,
      dag: BlockDag): F[Boolean] =
    equivocationsTracker.toList.existsM { equivocationRecord =>
      for {
        neglectedEquivocationDetected <- updateEquivocationsTracker[F](equivocationsTracker,
                                                                       block,
                                                                       dag,
                                                                       equivocationRecord)
      } yield neglectedEquivocationDetected
    }

  /**
    * If an equivocation is detected, it is added to the equivocationDetectedBlockHashes, which keeps track
    * of the block hashes that correspond to the blocks from which an equivocation can be justified.
    *
    * @return Whether a neglected equivocation was discovered.
    */
  private def updateEquivocationsTracker[F[_]: Monad: BlockStore](
      equivocationsTracker: mutable.Set[EquivocationRecord],
      block: BlockMessage,
      dag: BlockDag,
      equivocationRecord: EquivocationRecord): F[Boolean] =
    for {
      equivocationDiscoveryStatus <- getEquivocationDiscoveryStatus[F](block,
                                                                       dag,
                                                                       equivocationRecord)
      neglectedEquivocationDetected = equivocationDiscoveryStatus match {
        case EquivocationNeglected =>
          true
        case EquivocationDetected =>
          val updatedEquivocationDetectedBlockHashes = equivocationRecord.equivocationDetectedBlockHashes + block.blockHash
          equivocationsTracker.remove(equivocationRecord)
          equivocationsTracker.add(
            equivocationRecord.copy(
              equivocationDetectedBlockHashes = updatedEquivocationDetectedBlockHashes))
          false
        case EquivocationOblivious =>
          false
      }
    } yield neglectedEquivocationDetected

  private def getEquivocationDiscoveryStatus[F[_]: Monad: BlockStore](
      block: BlockMessage,
      dag: BlockDag,
      equivocationRecord: EquivocationRecord): F[EquivocationDiscoveryStatus] = {
    val equivocatingValidator = equivocationRecord.equivocator
    val latestMessages        = toLatestMessages(block.justifications)
    val maybeEquivocatingValidatorBond =
      bonds(block).find(_.validator == equivocatingValidator)
    maybeEquivocatingValidatorBond match {
      case Some(Bond(_, stake)) =>
        getEquivocationDiscoveryStatusForBondedValidator[F](equivocationRecord,
                                                            latestMessages,
                                                            stake)
      case None =>
        /*
         * Since block has dropped equivocatingValidator from the bonds, it has acknowledged the equivocation.
         * The combination of Validate.transactions and Validate.bondsCache ensure that you can only drop
         * validators through transactions to the proof of stake contract.
         */
        Applicative[F].pure(EquivocationDetected)
    }
  }

  private def getEquivocationDiscoveryStatusForBondedValidator[F[_]: Monad: BlockStore](
      equivocationRecord: EquivocationRecord,
      latestMessages: Map[Validator, BlockHash],
      stake: SequenceNumber): F[EquivocationDiscoveryStatus] =
    if (stake > 0) {
      for {
        equivocationDetectable <- isEquivocationDetectable[F](latestMessages.toSeq,
                                                              equivocationRecord,
                                                              Set.empty[BlockMessage])
      } yield
        if (equivocationDetectable) {
          EquivocationNeglected
        } else {
          EquivocationOblivious
        }
    } else {
      // TODO: This case is not necessary if assert(stake > 0) in the PoS contract
      Applicative[F].pure(EquivocationDetected)
    }

  private def isEquivocationDetectable[F[_]: Monad: BlockStore](
      latestMessages: Seq[(Validator, BlockHash)],
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[BlockMessage]): F[Boolean] =
    latestMessages match {
      case Nil => false.pure[F]
      case (_, justificationBlockHash) +: remainder =>
        isEquivocationDetectableAfterViewingBlock[F](justificationBlockHash,
                                                     equivocationRecord,
                                                     equivocationChildren,
                                                     remainder)
    }

  private def isEquivocationDetectableAfterViewingBlock[F[_]: Monad: BlockStore](
      justificationBlockHash: BlockHash,
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[BlockMessage],
      remainder: Seq[(Validator, BlockHash)]): F[Boolean] =
    if (equivocationRecord.equivocationDetectedBlockHashes.contains(justificationBlockHash)) {
      true.pure[F]
    } else {
      for {
        justificationBlock <- ProtoUtil.unsafeGetBlock[F](justificationBlockHash)
        equivocationDetected <- isEquivocationDetectableThroughChildren[F](equivocationRecord,
                                                                           equivocationChildren,
                                                                           remainder,
                                                                           justificationBlock)
      } yield equivocationDetected
    }

  private def isEquivocationDetectableThroughChildren[F[_]: Monad: BlockStore](
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[BlockMessage],
      remainder: Seq[(Validator, BlockHash)],
      justificationBlock: BlockMessage): F[Boolean] = {
    val equivocatingValidator = equivocationRecord.equivocator
    val equivocationBaseBlockSeqNum =
      equivocationRecord.equivocationBaseBlockSeqNum
    for {
      updatedEquivocationChildren <- maybeAddEquivocationChild[F](justificationBlock,
                                                                  equivocatingValidator,
                                                                  equivocationBaseBlockSeqNum,
                                                                  equivocationChildren)
      equivocationDetected <- if (updatedEquivocationChildren.size > 1) {
                               true.pure[F]
                             } else {
                               isEquivocationDetectable[F](remainder,
                                                           equivocationRecord,
                                                           updatedEquivocationChildren)
                             }
    } yield equivocationDetected
  }

  private def maybeAddEquivocationChild[F[_]: Monad: BlockStore](
      justificationBlock: BlockMessage,
      equivocatingValidator: Validator,
      equivocationBaseBlockSeqNum: SequenceNumber,
      equivocationChildren: Set[BlockMessage]): F[Set[BlockMessage]] =
    if (justificationBlock.sender == equivocatingValidator) {
      // This is a special case as the justificationBlock might be the equivocation child
      if (justificationBlock.seqNum > equivocationBaseBlockSeqNum) {
        addEquivocationChild[F](justificationBlock,
                                equivocationBaseBlockSeqNum,
                                equivocationChildren)
      } else {
        equivocationChildren.pure[F]
      }
    } else {
      // Latest according to the justificationBlock
      val maybeLatestEquivocatingValidatorBlockHash: Option[BlockHash] =
        toLatestMessages(justificationBlock.justifications).get(equivocatingValidator)
      maybeLatestEquivocatingValidatorBlockHash match {
        case Some(blockHash) =>
          for {
            latestEquivocatingValidatorBlock <- ProtoUtil.unsafeGetBlock[F](blockHash)
            updatedEquivocationChildren <- if (latestEquivocatingValidatorBlock.seqNum > equivocationBaseBlockSeqNum) {
                                            addEquivocationChild[F](
                                              latestEquivocatingValidatorBlock,
                                              equivocationBaseBlockSeqNum,
                                              equivocationChildren)
                                          } else { equivocationChildren.pure[F] }
          } yield updatedEquivocationChildren
        case None =>
          throw new Exception(
            "justificationBlock is missing justification pointers to equivocatingValidator even though justificationBlock isn't a part of equivocationDetectedBlockHashes for this equivocation record.")
      }
    }

  private def addEquivocationChild[F[_]: Monad: BlockStore](
      justificationBlock: BlockMessage,
      equivocationBaseBlockSeqNum: SequenceNumber,
      equivocationChildren: Set[BlockMessage]): F[Set[BlockMessage]] =
    for {
      maybeJustificationParentWithSeqNum <- findCreatorJustificationAncestorWithSeqNum[F](
                                             justificationBlock,
                                             equivocationBaseBlockSeqNum + 1)
      updatedEquivocationChildren = maybeJustificationParentWithSeqNum match {
        case Some(equivocationChild) => equivocationChildren + equivocationChild
        case None =>
          throw new Exception(
            "creator justification ancestor with lower sequence number hasn't been added to the blockDAG yet.")
      }
    } yield updatedEquivocationChildren
}