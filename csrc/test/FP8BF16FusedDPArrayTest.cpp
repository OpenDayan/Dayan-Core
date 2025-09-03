#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VFP8BF16FusedDPArray.h"
#include <vector>
#include <queue>
#include "fputils.hpp"
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VFP8BF16FusedDPArray *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

#define EPS 10e-2
#define FPCheck(ref, target, str) if(fabs((ref) - (target)) >= EPS) {printf("%s Not match, ref: %f, target: %f\n", str, (ref), (target)); if(!stopFlag) stopFlag = 2;}

static inline void mainLoop()
{
    static std::queue<std::vector<float>> results;
    if(1 || (unsigned)rand() % 2) {
        // Test an 2x2x2 BF16-DPA
        auto randArray = GenRandomFloats(8, 10);
        dut->a_0_0 = getLower(FP32ToBF16(randArray[0]));
        dut->a_0_1 = getUpper(FP32ToBF16(randArray[0]));
        dut->a_0_2 = getLower(FP32ToBF16(randArray[1]));
        dut->a_0_3 = getUpper(FP32ToBF16(randArray[1]));
        dut->a_1_0 = getLower(FP32ToBF16(randArray[2]));
        dut->a_1_1 = getUpper(FP32ToBF16(randArray[2]));
        dut->a_1_2 = getLower(FP32ToBF16(randArray[3]));
        dut->a_1_3 = getUpper(FP32ToBF16(randArray[3]));
        dut->b_0_0 = getLower(FP32ToBF16(randArray[4]));
        dut->b_0_1 = getUpper(FP32ToBF16(randArray[4]));
        dut->b_0_2 = getLower(FP32ToBF16(randArray[5]));
        dut->b_0_3 = getUpper(FP32ToBF16(randArray[5]));
        dut->b_1_0 = getLower(FP32ToBF16(randArray[6]));
        dut->b_1_1 = getUpper(FP32ToBF16(randArray[6]));
        dut->b_1_2 = getLower(FP32ToBF16(randArray[7]));
        dut->b_1_3 = getUpper(FP32ToBF16(randArray[7]));
        randArray[0] = BF16Clip(randArray[0]);
        randArray[1] = BF16Clip(randArray[1]);
        randArray[2] = BF16Clip(randArray[2]);
        randArray[3] = BF16Clip(randArray[3]);
        randArray[4] = BF16Clip(randArray[4]);
        randArray[5] = BF16Clip(randArray[5]);
        randArray[6] = BF16Clip(randArray[6]);
        randArray[7] = BF16Clip(randArray[7]);
        std::vector<float> r(4);
        r[0] = randArray[0 * 4 + 0 * 2 + 0] * randArray[1 * 4 + 0 * 2 + 0] + randArray[0 * 4 + 0 * 2 + 1] * randArray[1 * 4 + 0 * 2 + 1];
        r[1] = randArray[0 * 4 + 0 * 2 + 0] * randArray[1 * 4 + 1 * 2 + 0] + randArray[0 * 4 + 0 * 2 + 1] * randArray[1 * 4 + 1 * 2 + 1];
        r[2] = randArray[0 * 4 + 1 * 2 + 0] * randArray[1 * 4 + 0 * 2 + 0] + randArray[0 * 4 + 1 * 2 + 1] * randArray[1 * 4 + 0 * 2 + 1];
        r[3] = randArray[0 * 4 + 1 * 2 + 0] * randArray[1 * 4 + 1 * 2 + 0] + randArray[0 * 4 + 1 * 2 + 1] * randArray[1 * 4 + 1 * 2 + 1];
        results.push(r);
    }
    if(results.size() > 7) {
        auto r = results.front();
        FPCheck(r[0], *(float*)&dut->fp32Dbg_0_0, "0_0");
        FPCheck(r[1], *(float*)&dut->fp32Dbg_0_1, "0_1");
        FPCheck(r[2], *(float*)&dut->fp32Dbg_1_0, "1_0");
        FPCheck(r[3], *(float*)&dut->fp32Dbg_1_1, "1_1");
        results.pop();
    }
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VFP8BF16FusedDPArray;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VFP8BF16FusedDPArray.fst");
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
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        mainLoop();
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        sim_time++;
        if(stopFlag != 0) {
            if((--stopFlag) == 0) break;
        }
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}
