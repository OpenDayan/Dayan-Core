#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VMatProducerTaskGen.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VMatProducerTaskGen *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void mainLoop() {
    dut->io_cmdIn_valid = 1;
    dut->io_cmdIn_bits_addr = 0;
    dut->io_cmdIn_bits_stride = 1024;
    dut->io_cmdIn_bits_swizzle = 0;
    dut->io_cmdIn_bits_major = 2;
    dut->io_cmdIn_bits_repeat = 0;
    dut->io_cmdIn_bits_minor = 59;
    dut->io_cmdOut_ready = 1;
    dut->io_ackIn = dut->io_cmdOut_valid;
    dut->io_ackOut_ready = 1;
    dut->io_releaseIn = dut->io_cmdOut_valid;
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VMatProducerTaskGen;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/MatProducerTaskGen.fst");
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
        printf("\033[42;32mVerilator: MatProducerTaskGen Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: MatProducerTaskGen Test FAILED!\033[0m\n");
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
