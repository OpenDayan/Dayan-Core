#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VDPArrayCtrl.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VDPArrayCtrl *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void mainLoop()
{
    dut->inst_i_valid = 1;
    dut->inst_i_bits_log2SM = 2;
    dut->inst_i_bits_log2SN = 2;
    dut->inst_i_bits_log2SK = 2;
    dut->inst_i_bits_pM = 0;
    dut->inst_i_bits_pN = 1;
    dut->inst_i_bits_pK = 3;
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VDPArrayCtrl;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VDPArrayCtrl.fst");
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
        if(stopFlag != 0) {
            if((--stopFlag) == 1) break;
        }
    }
    if(!stopFlag) {
        printf("\033[42;32mVerilator: DPArrayCtrl Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: DPArrayCtrl Test FAILED!\033[0m\n");
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}
