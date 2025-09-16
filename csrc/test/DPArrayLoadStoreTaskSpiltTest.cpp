#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VDPArrayLoadStoreTaskSpilt.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VDPArrayLoadStoreTaskSpilt *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void mainLoop()
{
    dut->cmdIn_valid = 1;
    dut->cmdIn_bits_bank = 1;
    dut->cmdIn_bits_baseAddr = 0x10000;
    dut->cmdIn_bits_baseDimMajor = 0;
    dut->cmdIn_bits_baseDimMinor = 0;
    dut->cmdIn_bits_ctrl = 0;
    dut->cmdIn_bits_lastLoadAB = 1;
    dut->cmdIn_bits_log2SDimMajor = 2;
    dut->cmdIn_bits_log2SDimMinor = 2;
    dut->cmdIn_bits_pMajor = 63;
    dut->cmdIn_bits_pMinor = 127;
    dut->cmdIn_bits_waitLoadC = 0;

    dut->cmdBuffer_ready = 1;
    dut->cmdURAM_ready = 1;
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VDPArrayLoadStoreTaskSpilt;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VDPArrayLoadStoreTaskSpilt.fst");
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
        printf("\033[42;32mVerilator: DPArrayLoadStoreTaskSpilt Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: DPArrayLoadStoreTaskSpilt Test FAILED!\033[0m\n");
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
