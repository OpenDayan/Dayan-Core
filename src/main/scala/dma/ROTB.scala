package dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

// ReOrder and Transpose Buffer
// Reorder TL/AXI Response Message to FIFO Order, and do Transpose Addresses Calculation to help transpose.
/*
  本模块以组（2^logN * 64B）为单位完成访存请求的重排序、Padding 及转置。每组请求，包含若干（2^logN）个内存事务。
  所有内存事务均为 Unburst 类型。
  对外提供一个组分配接口，用于分配组缓冲区及组内的内存事务 ID。注意，该模块内建一个查找表，提供内存事务 ID -> 组事务 ID 的映射查找。
  对外提供一个内存事务完成接口，用于完成一个内存事务。该接口支持配置为 FIFO Order(URAM) 或 Out of Order(TL-UH/AXI4)。
  内存事务完成接口捆绑数据输入。
  注意：Tilelink Request ID(Source) 在相应 Response 到达之前不可被复用，AXI ID 可以复用，并保证相同 ID 的若干请求按照请求顺序返回。
  本设计需要同时兼容 AXI 及 Tilelink，因此假设 ID 不可复用，每一次 64B（总线宽）请求，均需要占用一个 ID，若干 ID 联合组成一组请求。
*/

class ROTB extends Module {

}
