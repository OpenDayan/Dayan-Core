#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VFP8BF16FusedDPUnit.h"
#include <vector>
#include <queue>
#include <math.h>
#include "fputils.hpp"
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VFP8BF16FusedDPUnit *dut;
unsigned long long sim_time = 0;

#define EPS 10e-2
#define FPCheck(ref, target, str) if(fabs((ref) - (target)) >= EPS) {printf("%s Not match, ref: %f, target: %f\n", str, (ref), (target)); stopFlag = true;}

bool stopFlag = false;

static inline void mainLoop()
{
    static std::queue<std::vector<float>> result;
    if((unsigned) rand() % 2){
        dut->bf16 = 1;
        float a0 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t aB0 = FP32ToBF16(a0); a0 = BF16ToFP32(aB0);
        float a1 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t aB1 = FP32ToBF16(a1); a1 = BF16ToFP32(aB1);
        float b0 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t bB0 = FP32ToBF16(b0); b0 = BF16ToFP32(bB0);
        float b1 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t bB1 = FP32ToBF16(b1); b1 = BF16ToFP32(bB1);
        dut->a_in_0_0 = aB0 & 0xff;
        dut->a_in_0_1 = (aB0 >> 8) & 0xff;
        dut->a_in_1_0 = aB1 & 0xff;
        dut->a_in_1_1 = (aB1 >> 8) & 0xff;
        dut->b_in_0_0 = bB0 & 0xff;
        dut->b_in_0_1 = (bB0 >> 8) & 0xff;
        dut->b_in_1_0 = bB1 & 0xff;
        dut->b_in_1_1 = (bB1 >> 8) & 0xff;
        std::vector<float> r(4);
        r[0] = a0 * b0;
        r[1] = a0 * b1;
        r[2] = a1 * b0;
        r[3] = a1 * b1;
        result.push(r);
    } else {
        dut->bf16 = 0;
        float a00 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t aB00 = FP32ToFP8<4>(a00);a00 = FP8ToFP32<4>(aB00);
        float a01 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t aB01 = FP32ToFP8<4>(a01);a01 = FP8ToFP32<4>(aB01);
        float a10 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t aB10 = FP32ToFP8<4>(a10);a10 = FP8ToFP32<4>(aB10);
        float a11 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t aB11 = FP32ToFP8<4>(a11);a11 = FP8ToFP32<4>(aB11);
        float b00 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t bB00 = FP32ToFP8<4>(b00);b00 = FP8ToFP32<4>(bB00);
        float b01 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t bB01 = FP32ToFP8<4>(b01);b01 = FP8ToFP32<4>(bB01);
        float b10 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t bB10 = FP32ToFP8<4>(b10);b10 = FP8ToFP32<4>(bB10);
        float b11 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint8_t bB11 = FP32ToFP8<4>(b11);b11 = FP8ToFP32<4>(bB11);
        dut->a_in_0_0 = aB00;
        dut->a_in_0_1 = aB01;
        dut->a_in_1_0 = aB10;
        dut->a_in_1_1 = aB11;
        dut->b_in_0_0 = bB00;
        dut->b_in_0_1 = aB01;
        dut->b_in_1_0 = bB10;
        dut->b_in_1_1 = aB11;
        std::vector<float> r(4);
        r[0] = a00 * b00 + a01 * b01;
        r[1] = a00 * b10 + a01 * b11;
        r[2] = a10 * b00 + a11 * b01;
        r[3] = a10 * b10 + a11 * b11;
        result.push(r);
    }
    if(result.size() > 4) {
        auto r = result.front();
        FPCheck(r[0], *(float*)&dut->fp32Dbg_0_0, "0_0");
        FPCheck(r[1], *(float*)&dut->fp32Dbg_0_1, "0_1");
        FPCheck(r[2], *(float*)&dut->fp32Dbg_1_0, "1_0");
        FPCheck(r[3], *(float*)&dut->fp32Dbg_1_1, "1_1");
        result.pop();
    }
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VFP8BF16FusedDPUnit;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VFP8BF16FusedDPUnit.fst");
    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    m_trace->dump(contextp->time());
    contextp->timeInc(1);
    for (int i = 0; i < 20; i++)
    {
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
    }
    dut->reset = 0;
    while (sim_time < MAX_SIM_TIME && !stopFlag)
    {
        dut->clock ^= 1;
        dut->eval();
        mainLoop();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        sim_time++;
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}
