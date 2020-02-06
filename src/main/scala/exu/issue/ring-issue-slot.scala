//******************************************************************************
// Copyright (c) 2015 - 2020, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Ring Microarchitecture Issue Slot
//--------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util.Valid

import freechips.rocketchip.config.Parameters

import boom.common._
import boom.util._
import FUConstants._

/**
 * IO bundle to interact with Issue slot
 *
 * @param numWakeupPorts number of wakeup ports for the slot
 */
class RingIssueSlotIO(implicit p: Parameters) extends BoomBundle
{
  val valid         = Output(Bool())
  val will_be_valid = Output(Bool())
  val request       = Output(Bool())
  val grant         = Input(Bool())

  val brinfo        = Input(new BrResolutionInfo())
  val kill          = Input(Bool()) // pipeline flush
  val clear         = Input(Bool()) // entry being moved elsewhere (not mutually exclusive with grant)
  val ldspec_miss   = Input(Bool())

  val slow_wakeups  = Input(Vec(coreWidth, Valid(UInt(ipregSz.W))))
  val fast_wakeup   = Input(Valid(UInt(ipregSz.W)))

  val in_uop        = Flipped(Valid(new MicroOp())) // Received from dispatch or another slot during compaction
  val out_uop       = Output(new MicroOp()) // The updated slot uop; will be shifted upwards in a collasping queue
  val uop           = Output(new MicroOp()) // The current slot's uop. Sent down the pipeline when issued

  val debug = {
    val result = new Bundle {
      val p1 = Bool()
      val p2 = Bool()
      val state = UInt(width=2.W)
    }
    Output(result)
  }
}

/**
 * Single issue slot. Holds a uop within the issue queue
 *
 * @param numWakeupPorts number of wakeup ports
 */
class RingIssueSlot(implicit p: Parameters)
  extends BoomModule
  with IssueUnitConstants
{
  val io = IO(new RingIssueSlotIO)

  // slot invalid?
  // slot is valid, holding 1 uop
  // slot is valid, holds 2 uops (like a store)
  def is_invalid = state === s_invalid
  def is_valid = state =/= s_invalid

  val next_state      = Wire(UInt()) // the next state of this slot (which might then get moved to a new slot)
  val next_uopc       = Wire(UInt()) // the next uopc of this slot (which might then get moved to a new slot)
  val next_lrs1_rtype = Wire(UInt()) // the next reg type of this slot (which might then get moved to a new slot)
  val next_lrs2_rtype = Wire(UInt()) // the next reg type of this slot (which might then get moved to a new slot)

  val state = RegInit(s_invalid)
  val p1    = RegInit(false.B)
  val p2    = RegInit(false.B)

  val slot_uop = RegInit(NullMicroOp)
  val next_uop = Mux(io.in_uop.valid, io.in_uop.bits, slot_uop)

  //-----------------------------------------------------------------------------
  // next slot state computation
  // compute the next state for THIS entry slot (in a collasping queue, the
  // current uop may get moved elsewhere, and a new uop can enter

  when (io.kill) {
    state := s_invalid
  } .elsewhen (io.in_uop.valid) {
    state := io.in_uop.bits.iw_state
  } .elsewhen (io.clear) {
    state := s_invalid
  } .otherwise {
    state := next_state
  }

  //-----------------------------------------------------------------------------
  // "update" state
  // compute the next state for the micro-op in this slot. This micro-op may
  // be moved elsewhere, so the "next_state" travels with it.

  // defaults
  next_state := state
  next_uopc := slot_uop.uopc
  next_lrs1_rtype := slot_uop.lrs1_rtype
  next_lrs2_rtype := slot_uop.lrs2_rtype

  when (io.kill) {
    next_state := s_invalid
  } .elsewhen ((io.grant && (state === s_valid_1)) ||
    (io.grant && (state === s_valid_2) && p1 && p2)) {
    next_state := s_invalid
  } .elsewhen (io.grant && (state === s_valid_2)) {
    next_state := s_valid_1
    when (p1) {
      slot_uop.uopc := uopSTD
      next_uopc := uopSTD
      slot_uop.lrs1_rtype := RT_X
      next_lrs1_rtype := RT_X
    } .otherwise {
      slot_uop.lrs2_rtype := RT_X
      next_lrs2_rtype := RT_X
    }
  }

  when (io.in_uop.valid) {
    slot_uop := io.in_uop.bits
    assert (is_invalid || io.clear || io.kill, "trying to overwrite a valid issue slot.")
  }

  // Wakeup Compare Logic

  // these signals are the "next_p*" for the current slot's micro-op.
  // they are important for shifting the current slot_uop up to an other entry.
  val next_p1 = WireInit(p1)
  val next_p2 = WireInit(p2)

  when (io.in_uop.valid) {
    p1 := !(io.in_uop.bits.prs1_busy)
    p2 := !(io.in_uop.bits.prs2_busy)
  }

  // Perform slow wakeups
  // TODO can optimize this once column steering is implemented
  // Or, should we leave it like this to allow greater dispatching flexibility? TBD.
  for (w <- 0 until coreWidth) {
    when (io.slow_wakeups(w).valid &&
         (io.slow_wakeups(w).bits === next_uop.prs1)) {
      p1 := true.B
    }
    when (io.slow_wakeups(w).valid &&
         (io.slow_wakeups(w).bits === next_uop.prs2)) {
      p2 := true.B
    }
  }

  // These should be false unless a fast wakeup hit during the previous cycle
  slot_uop.prs1_bypass := false.B
  slot_uop.prs2_bypass := false.B

  // Can't currently issue two cycles after recieving a fast wakeup,
  // as there is only 1 bypass path and the result still needs to reach the regfile.
  when (next_uop.prs1_bypass) {
    p1 := false.B
  }
  when (next_uop.prs2_bypass) {
    p2 := false.B
  }

  // Perform fast wakeup
  val fast_prs = next_uop.GetFastOperand
  when (io.fast_wakeup.valid && fast_prs === io.fast_wakeup.bits) {
    when (slot_uop.fast_prs_sel) {
      p2 := true.B
      slot_uop.prs2_bypass := true.B
    } .otherwise {
      p1 := true.B
      slot_uop.prs1_bypass := true.B
    }
  }

  // Handle branch misspeculations
  val next_br_mask = GetNewBrMask(io.brinfo, slot_uop)

  // was this micro-op killed by a branch? if yes, we can't let it be valid if
  // we compact it into an other entry
  when (IsKilledByBranch(io.brinfo, slot_uop)) {
    next_state := s_invalid
  }

  when (!io.in_uop.valid) {
    slot_uop.br_mask := next_br_mask
  }

  //-------------------------------------------------------------
  // Request Logic

  when (state === s_valid_1) {
    io.request := p1 && p2
  } .elsewhen (state === s_valid_2) {
    io.request := (p1 || p2)
  } .otherwise {
    io.request := false.B
  }

  //assign outputs
  io.valid := is_valid
  io.uop := slot_uop

  // micro-op will vacate due to grant.
  val may_vacate = io.grant && ((state === s_valid_1) || (state === s_valid_2) && p1 && p2)
  io.will_be_valid := is_valid && !may_vacate

  io.out_uop            := slot_uop
  io.out_uop.iw_state   := next_state
  io.out_uop.uopc       := next_uopc
  io.out_uop.lrs1_rtype := next_lrs1_rtype
  io.out_uop.lrs2_rtype := next_lrs2_rtype
  io.out_uop.br_mask    := next_br_mask
  io.out_uop.prs1_busy  := !p1
  io.out_uop.prs2_busy  := !p2

  when (state === s_valid_2) {
    when (p1 && p2) {
      ; // send out the entire instruction as one uop
    } .elsewhen (p1) {
      io.uop.uopc := slot_uop.uopc
      io.uop.lrs2_rtype := RT_X
    } .elsewhen (p2) {
      io.uop.uopc := uopSTD
      io.uop.lrs1_rtype := RT_X
    }
  }

  // debug outputs
  io.debug.p1 := p1
  io.debug.p2 := p2
  io.debug.state := state
}
