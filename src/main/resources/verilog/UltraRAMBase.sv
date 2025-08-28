//  Xilinx UltraRAM Simple Dual Port.  This code implements
//  a parameterizable UltraRAM block with 1 Read and 1 write
//  when addra == addrb, old data will show at doutb

`timescale 1ns/1ns

// Fixed 2 Cycle Latency
module UltraRAMBase #(
    parameter AWIDTH = 12,   // Address Width
    parameter DWIDTH = 72,  // Data Width
    parameter ID = 1         // Unique ID of URAM
) (
    input wire core_clk,               // Clock
    input wire reset,                  // Reset
    input wire write_enable,           // Write Enable
    input wire read_enable,            // Read Enable
    input wire [DWIDTH-1:0] dina,      // Data <wire_or_reg>
    input wire [AWIDTH-1:0] addra,     // Write Address
    input wire [AWIDTH-1:0] addrb,     // Read  Address
    output reg [DWIDTH-1:0] doutb,     // Data Output
    input wire[31:0] id
);

(* ram_style = "ultra" *)
reg [DWIDTH-1:0] mem [(1<<AWIDTH)-1:0];        // Memory Declaration
reg [DWIDTH-1:0] readreg;

always @(posedge clk) begin
    if(read_enable) readreg <= mem[addrb];
    if(write_enable) mem[addra] <= dina;
end

reg read_enable_q;
always @(posedge clk) begin
    if(reset) read_enable_q <= '0;
    else read_enable_q <= read_enable;
    if(read_enable_q) doutb <= readreg;
    else doutb <= '0;
end

endmodule
