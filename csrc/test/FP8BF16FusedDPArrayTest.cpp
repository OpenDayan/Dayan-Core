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
        #include "FP8BF16FusedArrayTestBF16Gen.hpp"
    }
    if(0) {
        // CIN Test
        dut->cin_exp_0_1 = FP32ToSExp(1.5f);
        dut->cin_frac_0_1 = FP32ToSFrac(1.5f, 25);
        std::vector<float> r(4);
        r[0] = 0.0f;
        r[1] = 1.5f;
        r[2] = 0.0f;
        r[3] = 0.0f;
        results.push(r);
    }
    if(results.size() > 9) {
        auto r = results.front();
        #include "FP8BF16FusedArrayTestCheckGen.hpp"
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
