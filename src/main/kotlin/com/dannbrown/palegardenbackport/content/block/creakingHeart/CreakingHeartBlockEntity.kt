package com.dannbrown.palegardenbackport.content.block.creakingHeart

import com.dannbrown.palegardenbackport.ModContent
import com.dannbrown.palegardenbackport.content.entity.creaking.CreakingEntity
import com.dannbrown.palegardenbackport.init.ModEntityTypes
import com.dannbrown.palegardenbackport.init.ModSounds
import com.mojang.datafixers.util.Either
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.SpawnUtil
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.apache.commons.lang3.mutable.Mutable
import org.apache.commons.lang3.mutable.MutableObject
import java.util.*
import java.util.function.Consumer
import kotlin.math.floor
import kotlin.math.sqrt

class CreakingHeartBlockEntity(type: BlockEntityType<CreakingHeartBlockEntity>, blockPos: BlockPos, blockState: BlockState): BlockEntity(type, blockPos, blockState) {
  var outputSignal: Int = 0
  var emitter: Int = 0
  var emitterTarget: Vec3? = null
  var creakingInfo: Either<CreakingEntity, UUID>? = null
  var ticksExisted: Long = 0
  var ticker: Int = 0

  fun getAnalogOutputSignal(): Int {
    return this.outputSignal
  }

  fun computeAnalogOutputSignal(): Int {
    if (this.creakingInfo != null && !this.getCreakingProtector().isEmpty) {
      val distance: Double = this.distanceToCreaking()
      val value: Double = when {
        distance < 0.0 -> 0.0
        distance > 32.0 -> 32.0
        else -> distance
      } / 32.0
      return 15 - floor(value * 15.0).toInt()
    }
    else return 0
  }

  fun removeProtector(damageSource: DamageSource?) {
    val mob = getCreakingProtector().orElse(null)
    if (mob is CreakingEntity) {
      if (damageSource == null) {
        mob.tearDown()
      }
      else {
        mob.creakingDeathEffects(damageSource)
        mob.setTearingDown()
      }
      this.clearCreakingInfo()
    }
  }

  fun creakingHurt() {
    val mob = getCreakingProtector().orElse(null)
    if (mob is CreakingEntity) {
      val level = this.level
      if (level is ServerLevel) {
        if (this.emitter <= 0) {
          val mobPos: Vec3 = mob.boundingBox.center
            .add(
              level.random.nextDouble() * mob.boundingBox.xsize/3 * if(level.random.nextBoolean()) 1 else -1,
              level.random.nextDouble() * mob.boundingBox.ysize/3 * if(level.random.nextBoolean()) 1 else -1,
              level.random.nextDouble() * mob.boundingBox.zsize/3 * if(level.random.nextBoolean()) 1 else -1
            )
          CreakingHeartBlock.emitParticleTrail(level, mobPos, Vec3.atCenterOf(this.blockPos), 16545810, 10)
          val chance = this.level!!.getRandom().nextIntBetweenInclusive(2, 3)

          for (i in 0 until chance) {
//            this.spreadResin()
//              .ifPresent { blockPos: BlockPos ->
//                this.level!!.playSound(
//                  null,
//                  blockPos,
//                  ModSounds.BLOCK_OF_RESIN_PLACE.get(),
//                  SoundSource.BLOCKS,
//                  1.0f,
//                  1.0f
//                )
//                this.level!!.gameEvent(GameEvent.BLOCK_PLACE, blockPos, GameEvent.Context.of(this.level!!.getBlockState(blockPos)))
//              }
          }
          this.emitter = 50
          this.emitterTarget = mob.boundingBox.center
        }
      }
    }
  }

  fun isProtector(mob: CreakingEntity): Boolean {
    return this.getCreakingProtector()
      .map { entity: CreakingEntity -> entity === mob }
      .orElse(false)
  }


  private fun spreadResin(): Optional<BlockPos> {
    val posMutable: Mutable<BlockPos?> = MutableObject(null)

    BlockPos.breadthFirstTraversal(this.worldPosition, 2, 64,
      { blockPos: BlockPos, consumer: Consumer<BlockPos?> ->
        val var3: Iterator<*> = Util.shuffledCopy(Direction.values(), level!!.random).iterator()
        while (var3.hasNext()) {
          val direction = var3.next() as Direction
          val relative = blockPos.relative(direction)
          if (CreakingHeartBlock.isPaleOakLog(level!!.getBlockState(relative))) {
            consumer.accept(relative)
          }
        }
      },
      { blockPos: BlockPos ->
        if (!CreakingHeartBlock.isPaleOakLog(level!!.getBlockState(blockPos))){
          return@breadthFirstTraversal true
        }
        else {
          val iterator: Iterator<*> = Util.shuffledCopy(Direction.values(), level!!.random).iterator()
          val randomDir = iterator.next() as Direction
          val oppositeDirection = randomDir.opposite
          val relative = blockPos.relative(randomDir)
          var relativeState = level!!.getBlockState(relative)
          do {
            if (!iterator.hasNext()) {
              return@breadthFirstTraversal true
            }

            if (relativeState.isAir) {
              relativeState = ModContent.RESIN_CLUMP.get().defaultBlockState()
            }
            else if (relativeState.`is`(Blocks.WATER) && relativeState.fluidState.isSource) {
              relativeState = ModContent.RESIN_CLUMP.get().defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)
            }
          } while (!relativeState.`is`(ModContent.RESIN_CLUMP.get()) || MultifaceBlock.hasFace(relativeState, oppositeDirection))
          level!!.setBlock(relative, relativeState.setValue(MultifaceBlock.getFaceProperty(oppositeDirection), true) as BlockState, 3)
          posMutable.value = relative
          return@breadthFirstTraversal false
        }
      })
    return Optional.ofNullable(posMutable.value)
  }

  override fun load(compoundTag: CompoundTag) {
    super.load(compoundTag)
    if (compoundTag.contains("creaking")) {
      this.setCreakingInfo(compoundTag.getUUID("creaking"))
    }
    else {
      this.clearCreakingInfo()
    }
  }


  override fun saveAdditional(compoundTag: CompoundTag) {
    super.saveAdditional(compoundTag)
    if (!this.getCreakingProtector().isEmpty) {
      compoundTag.putUUID("creaking", this.getCreakingProtector().get().uuid)
    }
  }

  private fun getCreakingProtector(): Optional<CreakingEntity> {
    if (this.creakingInfo == null) {
      return NO_CREAKING
    }
    else {
      if (creakingInfo!!.left().isPresent) {
        val mob: CreakingEntity = creakingInfo!!.left().get()
        if (!mob.isRemoved) {
          return Optional.of<CreakingEntity>(mob)
        }
        this.setCreakingInfo(mob.uuid)
      }
      if (this.level is ServerLevel) {
        if (creakingInfo!!.right().isPresent) {
          val mob = (this.level as ServerLevel).getEntity(creakingInfo!!.right().get())
          if (mob is CreakingEntity) {
            this.setCreakingInfo(mob)
            return Optional.of<CreakingEntity>(mob)
          }
          if (this.ticksExisted >= 30L) {
            this.clearCreakingInfo()
          }
          return NO_CREAKING
        }
      }
      return NO_CREAKING
    }
  }

  private fun distanceToCreaking(): Double {
    return getCreakingProtector().map { entity: CreakingEntity -> sqrt(entity.distanceToSqr(Vec3.atBottomCenterOf(this.blockPos))) }.orElse(0.0)
  }

  fun clearCreakingInfo() {
    this.creakingInfo = null
    this.setChanged()
  }

  fun setCreakingInfo(entity: CreakingEntity) {
    this.creakingInfo = Either.left(entity)
    this.setChanged()
  }

  fun setCreakingInfo(uuid: UUID) {
    this.creakingInfo = Either.right(uuid)
    this.ticksExisted = 0L
    this.setChanged()
  }

  companion object{
    val NO_CREAKING: Optional<CreakingEntity> = Optional.empty<CreakingEntity>()
    val ON_TOP_OF_COLLIDER_NO_LEAVES: SpawnUtil.Strategy =
      SpawnUtil.Strategy { serverLevel: ServerLevel, blockPos: BlockPos, blockState: BlockState, blockPos2: BlockPos, blockState2: BlockState ->
        blockState2.getCollisionShape(serverLevel, blockPos2).isEmpty && !blockState.`is`(BlockTags.LEAVES) && Block.isFaceFull(blockState.getCollisionShape(serverLevel, blockPos), Direction.UP)
      }

    private fun spawnProtector(serverLevel: ServerLevel, blockEntity: CreakingHeartBlockEntity): CreakingEntity? {
      val blockPos = blockEntity.blockPos
      val spawnedMob: Optional<CreakingEntity> = SpawnUtil.trySpawnMob(ModEntityTypes.CREAKING.get(), MobSpawnType.SPAWNER, serverLevel, blockPos, 5, 16, 8, ON_TOP_OF_COLLIDER_NO_LEAVES)
      if (spawnedMob.isEmpty) return null
      else {
        val entity: CreakingEntity = spawnedMob.get()
        serverLevel.gameEvent(entity, GameEvent.ENTITY_PLACE, entity.position())
        serverLevel.broadcastEntityEvent(entity, 60.toByte())
        entity.setTransient(blockPos)
        return entity
      }
    }

    fun serverTick(level: Level, blockPos: BlockPos, blockState: BlockState, blockEntity: CreakingHeartBlockEntity) {
      ++blockEntity.ticksExisted
      if (level is ServerLevel) {
        val analogSignal: Int = blockEntity.computeAnalogOutputSignal()
        if (blockEntity.outputSignal != analogSignal) {
          blockEntity.outputSignal = analogSignal
          level.updateNeighbourForOutputSignal(blockPos, ModContent.CREAKING_HEART.get())
        }

        if (blockEntity.emitter > 0) {
          if (blockEntity.emitter > 50) {
            val protector = blockEntity.getCreakingProtector()
            CreakingHeartBlock.emitParticleTrail(level, protector.get().position(), Vec3.atCenterOf(blockPos), 16545810, 10)
            CreakingHeartBlock.emitParticleTrail(level, protector.get().position(), Vec3.atCenterOf(blockPos), 6250335, 10)
          }

          if (blockEntity.emitter % 10 == 0 && blockEntity.emitterTarget != null) {
            blockEntity.getCreakingProtector().ifPresent { creakingEntity: CreakingEntity ->
              blockEntity.emitterTarget = creakingEntity.boundingBox.center
            }
            val center = Vec3.atCenterOf(blockPos)
            val dis: Float = 0.2f + 0.8f * (100 - blockEntity.emitter).toFloat() / 100.0f
            val diff = center.subtract(blockEntity.emitterTarget).scale(dis.toDouble()).add(blockEntity.emitterTarget)
            val pos = BlockPos.containing(diff)
            val pVolume: Float = blockEntity.emitter.toFloat() / 2.0f / 100.0f + 0.5f
            level.playSound(null, pos, ModSounds.CREAKING_HEART_HURT.get(), SoundSource.BLOCKS, pVolume, 1.0f)
          }

          --blockEntity.emitter
        }

        if (blockEntity.ticker-- < 0) {
          blockEntity.ticker = if (blockEntity.level == null) 20 else blockEntity.level!!.random.nextInt(5) + 20
          val mob: CreakingEntity?
          if (blockEntity.creakingInfo == null) {
            if (!CreakingHeartBlock.hasRequiredLogs(blockState, level, blockPos)) {
              level.setBlock(blockPos, blockState.setValue(CreakingHeartBlock.ACTIVE, false), 3)
            }
            else if (blockState.getValue(CreakingHeartBlock.ACTIVE) as Boolean) {
              if (CreakingHeartBlock.isNaturalNight(level)) {
                if (level.getDifficulty() != Difficulty.PEACEFUL) {
                  if (level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
                    val player = level.getNearestPlayer(blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble(), 32.0, false)
                    if (player != null) {
                      mob = spawnProtector(level, blockEntity)
                      if (mob != null) {
                        blockEntity.setCreakingInfo(mob)
                        mob.playSound(ModSounds.CREAKING_SPAWN.get())
                        level.playSound(null, blockEntity.blockPos, ModSounds.CREAKING_SPAWN.get(), SoundSource.BLOCKS, 1.0f, 1.0f)
                      }
                    }
                  }
                }
              }
            }
          }
          else {
            val entityOptional: Optional<CreakingEntity> = blockEntity.getCreakingProtector()
            if (entityOptional.isPresent) {
              if (!CreakingHeartBlock.isNaturalNight(level) || blockEntity.distanceToCreaking() > 34.0 || entityOptional.get().playerIsStuckInYou()) {
                blockEntity.removeProtector(null)
                return
              }

              if (!CreakingHeartBlock.hasRequiredLogs(blockState, level, blockPos) && blockEntity.creakingInfo == null) {
                level.setBlock(blockPos, blockState.setValue(CreakingHeartBlock.ACTIVE, false), 3)
              }
            }
          }
        }
      }
    }
  }
}