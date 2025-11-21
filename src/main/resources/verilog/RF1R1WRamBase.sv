module RF1R1WRamBase#(
    parameter addressWidth = 5,
    parameter dataWidth = 2048
)(
    input  wire clk,
    input  wire reset,
    
    input  wire re,
    input  wire [addressWidth - 1 : 0] raddr,
    output wire [dataWidth    - 1 : 0] rdata,

    input  wire we,
    input  wire [addressWidth - 1 : 0] waddr,
    input  wire [dataWidth    - 1 : 0] wdata
);

    // LUTRAM Should be infered in FPGA
    (* ram_style="distributed" *) reg [dataWidth - 1 : 0] ram [0 : 2 ** addressWidth - 1];
    reg [dataWidth - 1 : 0] ramQPort;
    always_ff @(posedge clk)
    begin
        if(we) ram[waddr] <= wdata;
        if(re) ramQPort <= ram[raddr];
    end

    assign rdata = ramQPort;

endmodule