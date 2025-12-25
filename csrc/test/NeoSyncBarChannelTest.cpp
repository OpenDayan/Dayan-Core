#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VNeoSyncBarChannel.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VNeoSyncBarChannel *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void mainLoop()
{
    dut->io_srcStreamI_0_bits = 0x1001 | (sim_time << 16);
    dut->io_srcStreamI_1_bits = 0x2002 | (sim_time << 16);
    dut->io_srcStreamI_2_bits = 0x3003 | (sim_time << 16);
    dut->io_srcStreamI_3_bits = 0x4004 | (sim_time << 16);
    if((sim_time % 2) == 0)
        dut->io_srcStreamI_0_valid = 1;
    else
        dut->io_srcStreamI_0_valid = 0;
    if((sim_time % 2) == 1)
        dut->io_srcStreamI_1_valid = 1;
    else
        dut->io_srcStreamI_1_valid = 0;
    if((sim_time % 4) == 2)
        dut->io_srcStreamI_2_valid = 1;
    else
        dut->io_srcStreamI_2_valid = 0;
    if((sim_time % 4) == 3)
        dut->io_srcStreamI_3_valid = 1;
    else
        dut->io_srcStreamI_3_valid = 0;

    dut->io_srcStreamO_0_ready = 1;
    dut->io_srcStreamO_1_ready = 1;
    dut->io_srcStreamO_2_ready = 1;
    dut->io_srcStreamO_3_ready = 1;

    dut->io_dstStreamI_0_bits = 0x101 | (sim_time << 16);
    dut->io_dstStreamI_1_bits = 0x202 | (sim_time << 16);
    dut->io_dstStreamI_2_bits = 0x303 | (sim_time << 16);
    dut->io_dstStreamI_3_bits = 0x404 | (sim_time << 16);

    if(sim_time > 10) {
        if((sim_time % 2) == 0)
            dut->io_dstStreamI_0_valid = 1;
        else
            dut->io_dstStreamI_0_valid = 0;
        if((sim_time % 2) == 0)
            dut->io_dstStreamI_1_valid = 1;
        else
            dut->io_dstStreamI_1_valid = 0;
        if((sim_time % 4) == 0)
            dut->io_dstStreamI_2_valid = 1;
        else
            dut->io_dstStreamI_2_valid = 0;
        if((sim_time % 4) == 0)
            dut->io_dstStreamI_3_valid = 1;
        else
            dut->io_dstStreamI_3_valid = 0;
    }

    dut->io_dstStreamO_0_ready = 1;
    dut->io_dstStreamO_1_ready = 1;
    dut->io_dstStreamO_2_ready = 1;
    dut->io_dstStreamO_3_ready = 1;
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VNeoSyncBarChannel;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/NeoSyncBarChannel.fst");
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
        printf("\033[42;32mVerilator: NeoSyncBarChannel Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: NeoSyncBarChannel Test FAILED!\033[0m\n");
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
