#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VFP8BF16FusedDPUnit.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VFP8BF16FusedDPUnit *dut;
unsigned long long sim_time = 0;

uint16_t FP32ToBF16(float a) {
    uint32_t aI = *(int*)&a;
    return (aI >> 16) & 0xffff;
}

float BF16ToFP32(uint16_t a) {
    uint32_t e = a << 16;
    float aF = *(float*)&e;
    return aF;
}

static inline void mainLoop()
{
    dut->bf16 = 1;
    if(sim_time < 100) {
        dut->a_in_0_0 = 0;
        dut->a_in_0_1 = 0;
        dut->a_in_1_0 = 0;
        dut->a_in_1_1 = 0;
        dut->b_in_0_0 = 0;
        dut->b_in_0_1 = 0;
        dut->b_in_1_0 = 0;
        dut->b_in_1_1 = 0;
    } else {
        float a0 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t aB0 = FP32ToBF16(a0);
        float a1 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t aB1 = FP32ToBF16(a1);
        float b0 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t bB0 = FP32ToBF16(b0);
        float b1 = (float) 10 * (((double)rand() / (double)RAND_MAX) - 0.5f); uint16_t bB1 = FP32ToBF16(b1);
        dut->a_in_0_0 = aB0 & 0xff;
        dut->a_in_0_1 = (aB0 >> 8) & 0xff;
        dut->a_in_1_0 = aB1 & 0xff;
        dut->a_in_1_1 = (aB1 >> 8) & 0xff;
        dut->b_in_0_0 = bB0 & 0xff;
        dut->b_in_0_1 = (bB0 >> 8) & 0xff;
        dut->b_in_1_0 = bB1 & 0xff;
        dut->b_in_1_1 = (bB1 >> 8) & 0xff;
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
    while (sim_time < MAX_SIM_TIME)
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
