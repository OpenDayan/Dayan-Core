package fpexputils

import chisel3._
import chisel3.util._

/** VHDL entity IntAdder_33_Freq150_uid354 的 Chisel 实现
  *
  * 该加法器执行以下操作： R = X (当前周期) + Y (延迟1周期) + Cin (延迟4周期)
  */
class IntAdder_33_Freq150_uid354 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(33.W))
        val Y   = Input(UInt(33.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(33.W))
    })

    // 1. 将输入 Y 寄存一拍，延迟一个时钟周期
    //    对应 VHDL: Y_d1 <= Y;
    val y_delayed_1 = RegNext(io.Y)

    // 2. 将输入 Cin 寄存四拍，延迟四个时钟周期
    //    对应 VHDL: Cin_d1 <= Cin; Cin_d2 <= Cin_d1; ...
    //    使用 ShiftRegister 工具可以更简洁地实现多级延迟
    val cin_delayed_4 = ShiftRegister(io.Cin, 4)

    // 3. 执行加法运算
    //    X 是当前周期的组合逻辑输入
    //    y_delayed_1 是延迟一拍的 Y
    //    cin_delayed_4 是延迟四拍的 Cin
    //    .asUInt 用于将 Bool 类型的 Cin 转换为 UInt 类型参与计算
    //    对应 VHDL: Rtmp <= X + Y_d1 + Cin_d4;
    val sum = io.X + y_delayed_1 + cin_delayed_4.asUInt

    // 4. 将计算结果连接到输出端口
    io.R := sum
}
