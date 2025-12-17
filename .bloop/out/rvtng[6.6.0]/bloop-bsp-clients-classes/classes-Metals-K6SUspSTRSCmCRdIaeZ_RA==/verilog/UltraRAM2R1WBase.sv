//  Xilinx UltraRAM Simple Dual Port.  This code implements
//  a parameterizable UltraRAM block with 1 Read and 1 write
//  when addra == addrb, old data will show at doutb
// Fixed 2 Cycle Latency
module DPIUltraRAM2R1WBase #(
    parameter AWIDTH = 12,   // Address Width
    parameter DWIDTH = 64    // Data Width
) (
    input wire core_clk,               // Clock
    input wire reset,                  // Reset
    input wire write_enable,           // Write Enable
    input wire [AWIDTH-1:0] addra,     // Write Address
    input wire [DWIDTH-1:0] dina,      // Data <wire_or_reg>

    input wire read_enable0,           // Read Enable
    input wire [AWIDTH-1:0] addrb0,    // Read Address
    output reg [DWIDTH-1:0] doutb0,    // Data Output

    input wire read_enable1,           // Read Enable
    input wire [AWIDTH-1:0] addrb1,    // Read Address
    output reg [DWIDTH-1:0] doutb1,    // Data Output
    input wire[31:0] id
);

`ifndef SYNTHESIS
    import "DPI-C" function longint UltraRamRead(
        input  int id,
        input  int raddr
    );

    import "DPI-C" function longint UltraRamWrite(
        input  int id,
        input  int waddr,
        input  longint wdata
    );

    /* verilator lint_off WIDTHEXPAND */
    reg [DWIDTH-1:0] readreg0, readreg1;
    reg [63:0] wret;
    always @(posedge core_clk) begin
        if(read_enable1) readreg1 <= UltraRamRead(id, addrb1);
        if(read_enable0) readreg0 <= UltraRamRead(id, addrb0);
        if(write_enable) wret <= UltraRamWrite(id, addra, dina);
    end

    reg read_enable0_q, read_enable1_q;
    always @(posedge core_clk) begin
        read_enable0_q <= read_enable0;
        read_enable1_q <= read_enable1;
        if(read_enable0_q) doutb0 <= readreg0;
        if(read_enable1_q) doutb1 <= readreg1;
    end
    /* verilator lint_on WIDTHEXPAND */
`else
    UltraRAMBase #(
        .AWIDTH(AWIDTH),
        .DWIDTH(DWIDTH)
    ) base0 (
        .core_clk(core_clk),
        .reset(reset),
        .write_enable(write_enable),
        .addra(addra),
        .dina(dina),
        .read_enable(read_enable0),
        .addrb(addrb0),
        .doutb(doutb0)
    );
    UltraRAMBase #(
        .AWIDTH(AWIDTH),
        .DWIDTH(DWIDTH)
    ) base1 (
        .core_clk(core_clk),
        .reset(reset),
        .write_enable(write_enable),
        .addra(addra),
        .dina(dina),
        .read_enable(read_enable1),
        .addrb(addrb1),
        .doutb(doutb1)
    );
`endif

endmodule
