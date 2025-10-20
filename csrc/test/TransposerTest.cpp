#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VTransposer.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VTransposer *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void mainLoop()
{
    static long long lastTime = 0;
    dut->inValid = 1;
    dut->inStream_0 = (0x00 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_1 = (0x01 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_2 = (0x02 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_3 = (0x03 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_4 = (0x04 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_5 = (0x05 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_6 = (0x06 | ((sim_time & 0x7) << 4)) & 0xff;
    dut->inStream_7 = (0x07 | ((sim_time & 0x7) << 4)) & 0xff;
    if((sim_time - lastTime) == (8 >> (dut->inElemSize))) {
        dut->inElemSize = rand() & 0x3;
        lastTime = sim_time;
    }
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VTransposer;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VTransposer.fst");
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
        printf("\033[42;32mVerilator: Transposer Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: Transposer Test FAILED!\033[0m\n");
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error occurred at " << filename << ":" << line << " (function: " << func << ") - " << msg << std::endl;
    stopFlag = 5;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "stop occurred at " << filename << ":" << line << " (function: " << func << ")" << std::endl;
    stopFlag = 5;
}
