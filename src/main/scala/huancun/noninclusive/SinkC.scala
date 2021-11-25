package huancun.noninclusive

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.tilelink.{TLBundleC, TLMessages}
import huancun._

class SinkC(implicit p: Parameters) extends BaseSinkC {

  val beats = blockBytes / beatBytes
  val buffer = Reg(Vec(bufBlocks, Vec(beats, UInt((beatBytes * 8).W))))
  val beatValsSave = RegInit(VecInit(Seq.fill(bufBlocks) {
    VecInit(Seq.fill(beats) { false.B })
  }))
  val beatValsThrough = RegInit(VecInit(Seq.fill(bufBlocks) {
    VecInit(Seq.fill(beats) { false.B })
  }))
  val beatVals = VecInit(Seq.fill(bufBlocks) {
    VecInit(Seq.fill(beats) { false.B })
  })
  beatVals.zipWithIndex.map {
    case (b, i) =>
      b.zip(beatValsSave(i).zip(beatValsThrough(i))).map {
        case (a, (s, t)) =>
          a := s || t
      }
  }
  val bufVals = VecInit(beatVals.map(_.asUInt().orR())).asUInt()
  val full = bufVals.andR()
  val c = io.c
  val isRelease = c.bits.opcode === TLMessages.Release
  val isReleaseData = c.bits.opcode === TLMessages.ReleaseData
  val isProbeAck = c.bits.opcode === TLMessages.ProbeAck
  val isProbeAckData = c.bits.opcode === TLMessages.ProbeAckData
  val isResp = isProbeAck || isProbeAckData
  val isReq = isRelease || isReleaseData
  val (first, last, done, count) = edgeIn.count(c)
  val hasData = edgeIn.hasData(c.bits)
  val noSpace = full && hasData
  val insertIdx = PriorityEncoder(~bufVals)
  val insertIdxReg = RegEnable(insertIdx, c.fire() && first)
  val (tag, set, off) = parseAddress(c.bits.address)

  c.ready := Mux(first, !noSpace && !(isReq && !io.alloc.ready), true.B)

  // alloc.ready depends on alloc.valid
  io.alloc.valid := c.valid && isReq && first && !noSpace
  io.alloc.bits.channel := "b100".U
  io.alloc.bits.opcode := c.bits.opcode
  io.alloc.bits.param := c.bits.param
  io.alloc.bits.size := c.bits.size
  io.alloc.bits.source := c.bits.source
  io.alloc.bits.tag := tag
  io.alloc.bits.set := set
  io.alloc.bits.off := off
  io.alloc.bits.bufIdx := insertIdx
  io.alloc.bits.needHint.foreach(_ := false.B)
  io.alloc.bits.alias.foreach(_ := 0.U)
  io.alloc.bits.preferCache := true.B
  io.alloc.bits.dirty := c.bits.echo.lift(DirtyKey).getOrElse(true.B)
  io.alloc.bits.fromProbeHelper := false.B
  io.alloc.bits.fromCmoHelper := false.B
  io.alloc.bits.needProbeAckData.foreach(_ := false.B)
  assert(!io.alloc.fire() || c.fire() && first, "alloc fire, but c channel not fire!")

  io.resp.valid := c.fire() && isResp
  io.resp.bits.hasData := hasData
  io.resp.bits.param := c.bits.param
  io.resp.bits.source := c.bits.source
  io.resp.bits.last := last
  io.resp.bits.set := set
  io.resp.bits.bufIdx := Mux(first, insertIdx, insertIdxReg)

  // buffer write
  when(c.fire() && hasData) {
    when(first) {
      buffer(insertIdx)(count) := c.bits.data
      beatValsSave(insertIdx)(count) := true.B
      beatValsThrough(insertIdx)(count) := true.B
    }.otherwise({
      buffer(insertIdxReg)(count) := c.bits.data
      beatValsSave(insertIdxReg)(count) := true.B
      beatValsThrough(insertIdxReg)(count) := true.B
    })
  }

  val task = io.task.bits
  val task_r = RegEnable(io.task.bits, io.task.fire())
  val busy = RegInit(false.B)
  val w_counter_save = RegInit(0.U(beatBits.W))
  val w_counter_through = RegInit(0.U(beatBits.W))
  val task_w_safe = !(io.sourceD_r_hazard.valid &&
    io.sourceD_r_hazard.bits.safe(task.set, task.way))

  io.task.ready := !busy && task_w_safe
  when(io.task.fire()) {
    busy := true.B
    when(!task.save && !task.drop) {
      beatValsSave(task.bufIdx).foreach(_ := false.B)
      w_counter_save := (beats - 1).U
    }
    when(!task.release && !task.drop) {
      beatValsThrough(task.bufIdx).foreach(_ := false.B)
      w_counter_through := (beats - 1).U
    }
  }

  io.bs_waddr.valid := busy && task_r.save
  io.bs_waddr.bits.way := task_r.way
  io.bs_waddr.bits.set := task_r.set
  io.bs_waddr.bits.beat := w_counter_save
  io.bs_waddr.bits.write := true.B
  io.bs_waddr.bits.noop := !beatValsSave(task_r.bufIdx)(w_counter_save)
  io.bs_wdata.data := buffer(task_r.bufIdx)(w_counter_save)

  io.release.valid := busy && task_r.release && beatValsThrough(task_r.bufIdx)(w_counter_through)
  io.release.bits.address := Cat(task_r.tag, task_r.set, task_r.off)
  io.release.bits.data := buffer(task_r.bufIdx)(w_counter_through)
  io.release.bits.opcode := task_r.opcode
  io.release.bits.param := task_r.param
  io.release.bits.source := task_r.source
  io.release.bits.size := task_r.size
  io.release.bits.corrupt := false.B
  io.release.bits.user.lift(PreferCacheKey).foreach(_ := true.B)
  io.release.bits.echo.lift(DirtyKey).foreach(_ := task_r.dirty)

  val w_fire_save = io.bs_waddr.fire() && !io.bs_waddr.bits.noop
  val w_fire_through = io.release.fire()
  val w_fire = w_fire_save || w_fire_through
  when(w_fire_save) { w_counter_save := w_counter_save + 1.U }
  when(w_fire_through) { w_counter_through := w_counter_through + 1.U }

  val w_done = (w_counter_save === (beats - 1).U) && (w_counter_through === (beats - 1).U) && w_fire
  when(w_done || busy && task_r.drop) {
    w_counter_save := 0.U
    w_counter_through := 0.U
    busy := false.B
    beatValsSave(task_r.bufIdx).foreach(_ := false.B)
    beatValsThrough(task_r.bufIdx).foreach(_ := false.B)
  }
}
