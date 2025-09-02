module UnsignedMulBase #(
    parameter AWIDTH = 26,
    parameter BWIDTH = 17
)(
    input  wire[AWIDTH-1:0] a,
    input  wire[BWIDTH-1:0] b,
    output wire[AWIDTH+BWIDTH-1:0] c
);

    assign c = a * b;

endmodule