package io.iohk.ethereum.snappy

import io.iohk.ethereum.blockchain.data.GenesisDataLoader
import io.iohk.ethereum.db.components.Storages.PruningModeComponent
import io.iohk.ethereum.db.components.{SharedLevelDBDataSources, Storages}
import io.iohk.ethereum.db.dataSource.{LevelDBDataSource, LevelDbConfig}
import io.iohk.ethereum.db.storage.pruning.ArchivePruning
import io.iohk.ethereum.domain.{Blockchain, BlockchainImpl}
import io.iohk.ethereum.ledger.{Ledger, LedgerImpl}
import io.iohk.ethereum.nodebuilder._
import io.iohk.ethereum.snappy.Config.{DualDB, SingleDB}
import io.iohk.ethereum.snappy.Prerequisites._
import io.iohk.ethereum.utils.Config.DbConfig
import io.iohk.ethereum.validators.Validators
import io.iohk.ethereum.vm.VM


object Prerequisites {
  trait NoPruning extends PruningModeComponent {
    val pruningMode = ArchivePruning
  }

  trait Storages extends SharedLevelDBDataSources with NoPruning with Storages.DefaultStorages
}

class Prerequisites(config: Config) {

  private def levelDb(dbPath: String): LevelDBDataSource =
    LevelDBDataSource (
      new LevelDbConfig {
        val verifyChecksums: Boolean = true
        val paranoidChecks: Boolean = true
        val createIfMissing: Boolean = true
        val path: String = dbPath
      }
    )

  val sourceStorages: Storages = new Storages {
    override lazy val dataSource = levelDb(config.sourceDbPath)
  }

  val targetStorages: Option[Storages] = config.mode match {
    case DualDB =>
      Some(new Storages {
        override lazy val dataSource = levelDb(config.targetDbPath)
      })

    case SingleDB => None
  }

  val sourceBlockchain: BlockchainImpl = BlockchainImpl(sourceStorages.storages)
  val targetBlockchain: Option[BlockchainImpl] = targetStorages.map(ts => BlockchainImpl(ts.storages))

  private val components =
    new LedgerBuilder
      with ValidatorsBuilder
      with BlockchainConfigBuilder
      with BlockChainBuilder
      with StorageBuilder


  val ledger: Ledger = targetBlockchain match {
    case Some(blockchain) => new LedgerImpl(VM, blockchain, components.blockchainConfig)
    case None => new LedgerImpl(VM, sourceBlockchain, components.blockchainConfig)
  }

  val validators: Validators = components.validators

  targetStorages.foreach { ts =>
    val genesisLoader = new GenesisDataLoader(
      BlockchainImpl(ts.storages),
      components.blockchainConfig,
      new DbConfig {
        val batchSize: Int = 1000
      }
    )

    genesisLoader.loadGenesisData()
  }
}