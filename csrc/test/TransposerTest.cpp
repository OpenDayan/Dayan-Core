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
    dut->inStream_0_0 = (0x00 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_1 = (0x01 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_2 = (0x02 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_3 = (0x03 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_4 = (0x04 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_5 = (0x05 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_6 = (0x06 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_0_7 = (0x07 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_0 = (0x10 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_1 = (0x11 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_2 = (0x12 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_3 = (0x13 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_4 = (0x14 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_5 = (0x15 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_6 = (0x16 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_1_7 = (0x17 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_0 = (0x20 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_1 = (0x21 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_2 = (0x22 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_3 = (0x23 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_4 = (0x24 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_5 = (0x25 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_6 = (0x26 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_2_7 = (0x27 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_0 = (0x30 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_1 = (0x31 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_2 = (0x32 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_3 = (0x33 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_4 = (0x34 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_5 = (0x35 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_6 = (0x36 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_3_7 = (0x37 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_0 = (0x40 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_1 = (0x41 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_2 = (0x42 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_3 = (0x43 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_4 = (0x44 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_5 = (0x45 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_6 = (0x46 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_4_7 = (0x47 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_0 = (0x50 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_1 = (0x51 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_2 = (0x52 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_3 = (0x53 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_4 = (0x54 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_5 = (0x55 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_6 = (0x56 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_5_7 = (0x57 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_0 = (0x60 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_1 = (0x61 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_2 = (0x62 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_3 = (0x63 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_4 = (0x64 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_5 = (0x65 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_6 = (0x66 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_6_7 = (0x67 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_0 = (0x70 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_1 = (0x71 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_2 = (0x72 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_3 = (0x73 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_4 = (0x74 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_5 = (0x75 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_6 = (0x76 | ((sim_time & 63) << 8)) & 0xffff;
    dut->inStream_7_7 = (0x77 | ((sim_time & 63) << 8)) & 0xffff;
    if((sim_time - lastTime) == (8 >> (dut->inElemSize))) {
        // dut->inElemSize = rand() & 0x3;
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
    dut->inElemSize = 0;
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
