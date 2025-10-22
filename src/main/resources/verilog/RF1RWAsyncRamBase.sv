module RF1R1WAsyncRamBase#(
    parameter addressWidth = 5,
    parameter dataWidth = 2048,
    parameter selfReset = 0
)(
    input  wire clk,
    input  wire reset,

    input  wire we,
    input  wire [addressWidth - 1 : 0] waddr,
    input  wire [dataWidth    - 1 : 0] wdata,
    output wire [dataWidth    - 1 : 0] rwdata
);
    logic[addressWidth - 1 : 0] reset_addr;
    wire[dataWidth - 1 : 0] reset_wdata;
    if(selfReset) begin
        always_ff @(posedge clk) begin
            // TODO: USE LFSR HERE
            if(reset) reset_addr <= reset_addr + 1'd1;
        end
        assign reset_wdata = '0;
    end else begin
        assign reset_addr = waddr;
        assign reset_wdata = wdata;
    end

    wire[addressWidth - 1 : 0] iwaddr = reset ? reset_addr : waddr;
    wire[dataWidth - 1 : 0] iwdata = reset ? reset_wdata : wdata;

    // LUTRAM Should be infered in FPGA
    (* ram_style="distributed" *) reg [dataWidth - 1 : 0] ram [0 : 2 ** addressWidth - 1];
    always_ff @(posedge clk)
    begin
        if(we) ram[iwaddr] <= iwdata;
    end

    assign rwdata = ram[iwaddr];

endmodule