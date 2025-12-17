
package rvtng.vpu

import chisel3._

object GemminiISA {
  // funct values
  val CONFIG_CMD = 60.U
  val LOAD2_CMD = 61.U
  val LOAD_CMD = 62.U
  val STORE_CMD = 63.U
  val COMPUTE_AND_FLIP_CMD = 64.U
  val COMPUTE_AND_STAY_CMD = 65.U
  val PRELOAD_CMD = 66.U
  val FLUSH_CMD = 67.U

  val LOOP_WS = 68.U
  val LOOP_WS_CONFIG_BOUNDS = 69.U
  val LOOP_WS_CONFIG_ADDRS_AB = 70.U
  val LOOP_WS_CONFIG_ADDRS_DC = 71.U
  val LOOP_WS_CONFIG_STRIDES_AB = 72.U
  val LOOP_WS_CONFIG_STRIDES_DC = 73.U

  val LOAD3_CMD = 74.U

  // TODO add orows and ocols to this as well
  val LOOP_CONV_WS = 75.U // no_bias, wrot180, trans_output_1203, trans_weight_1203, trans_input_3120, dw, max_pixels_per_row | no_pool, downsample, input_dilated, act
  val LOOP_CONV_WS_CONFIG_1 = 76.U // batch_size, in_dim, in_channels, out_channels | out_dim, pool_out_dim, stride, padding
  val LOOP_CONV_WS_CONFIG_2 = 77.U // kernel_dim, pool_size, pool_stride, pool_padding | batches, porows, pocols, pochs
  val LOOP_CONV_WS_CONFIG_3 = 78.U // krows, kcols, kchs, lpad | rpad, upad, dpad, plpad
  val LOOP_CONV_WS_CONFIG_4 = 79.U // prad, pupad, pdpad, orows | ocols, kernel_dilation
  val LOOP_CONV_WS_CONFIG_5 = 80.U // *weights | *output
  val LOOP_CONV_WS_CONFIG_6 = 81.U // *bias, *input

  val CLKGATE_EN = 82.U

  val STORE_SPAD_CMD = 83.U
  val LOOP_WS_CONFIG_SPAD_AB = 84.U
  val LOOP_WS_CONFIG_SPAD_C = 85.U

  // rs1[2:0] values
  val CONFIG_EX = 0.U
  val CONFIG_LOAD = 1.U
  val CONFIG_STORE = 2.U
  val CONFIG_NORM = 3.U

  val tcsr_xchg_nmask_cmd = "b10000000".U
  val async_nram_load = "b10000110".U
  val async_nram_store = "b10000101".U
  val matmul = "b00000010".U
  val MATMUL_SUBFLOAT = "b00000000".U
  val MATMUL_FLOAT    = "b00000001".U
  val MATMUL_FIX      = "b00000010".U
  val MATMUL_E4M3 = "b011100".U
  val MATMUL_BF16 = "b01".U
  val URAM_MV = "b10000100".U
  val INT_OP = "b00001000".U
  val FP_OP = "b00001001".U
  val SFU_OP = "b10010000".U
  val CONVERT_OP = "b10001110".U
  val REDUCE_OP = "b10001101".U

}

