package convertutils

import chisel3._

object ConvertMode {
  val TX_CONVERT_ROUND = 0.U
  val TX_CONVERT_FLOOR = 1.U
  val TX_CONVERT_CEIL  = 2.U
}

// NEMU C code:
// typedef enum __TX_CONVERT_MODE {
//   TX_CONVERT_ROUND = 0,
//   TX_CONVERT_FLOOR = 1,
//   TX_CONVERT_CEIL = 2
// } ConvertMode;