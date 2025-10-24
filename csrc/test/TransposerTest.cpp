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
    static long long fireTime = -1;
    static long long lastTime = 0;
    fireTime += dut->in_ready;
    dut->in_valid = 1;
    dut->in_bits_stream_0_0 = (0x00 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_1 = (0x01 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_2 = (0x02 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_3 = (0x03 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_4 = (0x04 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_5 = (0x05 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_6 = (0x06 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_0_7 = (0x07 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_0 = (0x10 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_1 = (0x11 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_2 = (0x12 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_3 = (0x13 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_4 = (0x14 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_5 = (0x15 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_6 = (0x16 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_1_7 = (0x17 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_0 = (0x20 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_1 = (0x21 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_2 = (0x22 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_3 = (0x23 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_4 = (0x24 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_5 = (0x25 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_6 = (0x26 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_2_7 = (0x27 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_0 = (0x30 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_1 = (0x31 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_2 = (0x32 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_3 = (0x33 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_4 = (0x34 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_5 = (0x35 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_6 = (0x36 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_3_7 = (0x37 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_0 = (0x40 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_1 = (0x41 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_2 = (0x42 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_3 = (0x43 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_4 = (0x44 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_5 = (0x45 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_6 = (0x46 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_4_7 = (0x47 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_0 = (0x50 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_1 = (0x51 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_2 = (0x52 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_3 = (0x53 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_4 = (0x54 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_5 = (0x55 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_6 = (0x56 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_5_7 = (0x57 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_0 = (0x60 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_1 = (0x61 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_2 = (0x62 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_3 = (0x63 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_4 = (0x64 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_5 = (0x65 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_6 = (0x66 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_6_7 = (0x67 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_0 = (0x70 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_1 = (0x71 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_2 = (0x72 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_3 = (0x73 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_4 = (0x74 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_5 = (0x75 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_6 = (0x76 | ((fireTime & 63) << 8)) & 0xffff;
    dut->in_bits_stream_7_7 = (0x77 | ((fireTime & 63) << 8)) & 0xffff;
    if((fireTime - lastTime) == (8 >> (dut->in_bits_elemSize))) {
        // dut->inElemSize = rand() & 0x3;
        lastTime = fireTime;
    }
    dut->out_ready = // rand() &
    1;
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
    dut->in_bits_elemSize = 0;
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
