// See LICENSE for license details.

package rocketchip

import Chisel._
import cde.{Parameters, Field}
import junctions._
import diplomacy._
import uncore.tilelink._
import uncore.tilelink2._
import uncore.devices._
import util._
import rocket._
import coreplex._

// the following parameters will be refactored properly with TL2
case object GlobalAddrMap extends Field[AddrMap]
case object ConfigString extends Field[String]
case object NCoreplexExtClients extends Field[Int]
/** Enable or disable monitoring of Diplomatic buses */
case object TLEmitMonitors extends Field[Bool]
/** Function for building Coreplex */
case object BuildCoreplex extends Field[(CoreplexConfig, Parameters) => BaseCoreplexModule[BaseCoreplex, BaseCoreplexBundle]]

/** Base Top with no Periphery */
abstract class BaseTop(q: Parameters) extends LazyModule {
  // the following variables will be refactored properly with TL2
  val pInterrupts = new RangeManager
  val pBusMasters = new RangeManager
  val pDevices = new ResourceManager[AddrMapEntry]

  TLImp.emitMonitors = q(TLEmitMonitors)

  // Add a SoC and peripheral bus
  val socBus = LazyModule(new TLXbar)
  val peripheryBus = LazyModule(new TLXbar)
  lazy val peripheryManagers = socBus.node.edgesIn(0).manager.managers

  lazy val c = CoreplexConfig(
    nTiles = q(NTiles),
    nExtInterrupts = pInterrupts.sum,
    nSlaves = pBusMasters.sum,
    nMemChannels = q(NMemoryChannels),
    hasSupervisor = q(UseVM)
  )

  lazy val genGlobalAddrMap = GenerateGlobalAddrMap(q, pDevices.get, peripheryManagers)
  private val qWithMap = q.alterPartial({case GlobalAddrMap => genGlobalAddrMap})

  lazy val genConfigString = GenerateConfigString(qWithMap, c, pDevices.get, peripheryManagers)
  implicit val p = qWithMap.alterPartial({
    case ConfigString => genConfigString
    case NCoreplexExtClients => pBusMasters.sum})

  val legacy = LazyModule(new TLLegacy()(p.alterPartial({ case TLId => "L2toMMIO" })))

  peripheryBus.node :=
    TLWidthWidget(p(SOCBusKey).beatBytes)(
    TLBuffer()(
    TLAtomicAutomata(arithmetic = p(PeripheryBusKey).arithAMO)(
    socBus.node)))

  socBus.node :=
    TLWidthWidget(legacy.tlDataBytes)(
    TLHintHandler()(
    legacy.node))

  TopModule.contents = Some(this)
}

abstract class BaseTopBundle(val p: Parameters) extends Bundle {
  val success = Bool(OUTPUT)
  val traffic_enable = Bool(INPUT)
}

abstract class BaseTopModule[+L <: BaseTop, +B <: BaseTopBundle](
    val p: Parameters, l: L, b: => B) extends LazyModuleImp(l) {
  val outer: L = l
  val io: B = b

  val coreplex = p(BuildCoreplex)(outer.c, p)
  val coreplexIO = Wire(coreplex.io)

  val pBus =
    Module(new TileLinkRecursiveInterconnect(1, p(GlobalAddrMap).subMap("io:pbus"))(
      p.alterPartial({ case TLId => "L2toMMIO" })))
  pBus.io.in.head <> coreplexIO.master.mmio
  outer.legacy.module.io.legacy <> pBus.port("TL2")

  println("Generated Address Map")
  for (entry <- p(GlobalAddrMap).flatten) {
    val name = entry.name
    val start = entry.region.start
    val end = entry.region.start + entry.region.size - 1
    val prot = entry.region.attr.prot
    val protStr = (if ((prot & AddrMapProt.R) > 0) "R" else "") +
                  (if ((prot & AddrMapProt.W) > 0) "W" else "") +
                  (if ((prot & AddrMapProt.X) > 0) "X" else "")
    val cacheable = if (entry.region.attr.cacheable) " [C]" else ""
    println(f"\t$name%s $start%x - $end%x, $protStr$cacheable")
  }

  println("\nGenerated Interrupt Vector")
  outer.pInterrupts.print

  println("\nGenerated Configuration String")
  println(p(ConfigString))
  ConfigStringOutput.contents = Some(p(ConfigString))

  io.success := coreplexIO.success
  coreplexIO.traffic_enable := io.traffic_enable
}

trait DirectConnection {
  implicit val p: Parameters
  val coreplexIO: BaseCoreplexBundle
  val coreplex: BaseCoreplexModule[BaseCoreplex, BaseCoreplexBundle]

  coreplexIO <> coreplex.io
}

trait AsyncConnectionBundle {
  val coreclk = Clock(INPUT)
  val corerst = Bool(INPUT)
}

trait AsyncConnection {
  val io: AsyncConnectionBundle
  val coreplexIO: BaseCoreplexBundle

  val multiClockCoreplexIO = coreplexIO.asInstanceOf[MultiClockCoreplexBundle]
  multiClockCoreplexIO.tcrs.foreach { tcr =>
    tcr.clock := io.coreclk
    tcr.reset := io.corerst
  }
}
