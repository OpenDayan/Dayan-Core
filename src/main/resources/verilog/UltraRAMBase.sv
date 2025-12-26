//  Xilinx UltraRAM Simple Dual Port.  This code implements
//  a parameterizable UltraRAM block with 1 Read and 1 write
//  when addra == addrb, old data will show at doutb
// Fixed 2 Cycle Latency
module UltraRAMBase #(
    parameter AWIDTH = 12,   // Address Width
    parameter DWIDTH = 72,  // Data Width
    parameter ID = 1         // Unique ID of URAM
) (
    input wire core_clk,               // Clock
    input wire reset,                  // Reset
    input wire [DWIDTH/8-1:0] byte_enable,
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

always @(posedge core_clk) begin
    if(read_enable) readreg <= mem[addrb];
    if(write_enable) begin
        for(integer i = 0; i < (DWIDTH/8); i = i + 1) begin
            if(byte_enable[i]) mem[addra][i * 8 +: 8] <= dina[i * 8 +: 8];
        end
    end
end

reg read_enable_q;
always @(posedge core_clk) begin
    if(reset) read_enable_q <= '0;
    else read_enable_q <= read_enable;
    if(read_enable_q) doutb <= readreg;
end

endmodule
