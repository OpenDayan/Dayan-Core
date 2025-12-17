module DPIRamBase (
    input  wire clk,
    input  wire reset,
    input  wire [31:0] id,
    
    input  wire  re,
    input  wire  [31:0] raddr,
    output logic [63:0] rdata,

    input  wire we,
    input  wire [31:0] waddr,
    input  wire [63:0] wdata
);

    import "DPI-C" function longint DPIRamProvider(
        input  int id,
        input  int raddr,
        input  byte re,
        // output longint rdata,

        input  int waddr,
        input  byte we,
        input  longint wdata
        );
    
    always @(posedge clk) if(!reset) rdata = DPIRamProvider(id, raddr, {7'd0, re}, waddr, {7'd0, we}, wdata);

endmodule