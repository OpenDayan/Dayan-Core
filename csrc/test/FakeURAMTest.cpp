#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VFakeURAM.h"
#include <vector>
#include <queue>

#include "svdpi.h"
#include "VFakeURAM__Dpi.h"

#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VFakeURAM *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

#define MAXID 4

extern "C" long long DPIRamProvider(int id, int raddr, char re, int waddr, char we, long long int wdata)
{
    // printf("ID: [%d] Call DPI-C Interface to Fetch Data!\n", id);
    static long int uram[1024 * 1024 / 8]; // 1024KBytes URAM
    static int maxCycle = 0;
    static int cycle[MAXID];
    static int Lwaddr[MAXID];
    static long int Lwdata[MAXID];

    if(cycle[id] > maxCycle) {
        maxCycle = cycle[id];
        for(int i = 0; i < MAXID ; i++) {
            if(Lwaddr[i] != -1) uram[Lwaddr[i]] = Lwdata[i];
        }
    }
    cycle[id]++;

    if(we) {
        Lwaddr[id] = waddr;
        Lwdata[id] = wdata;
    } else {
        Lwaddr[id] = -1;
    }
    return re ? uram[raddr] : 0;
}

static inline void mainLoop()
{
    static int i = 0;
    dut->wreq_i_0_valid = 1;
    dut->wreq_i_0_bits_address = (i + 2) << 3;
    dut->wdata_i_0 = i;
    dut->rreq_i_0_valid = 1;
    dut->rreq_i_0_bits_address = (i + 0) << 3;
    dut->rreq_i_1_valid = 1;
    dut->rreq_i_1_bits_address = (i + 1) << 3;
    dut->rreq_i_2_valid = 1;
    dut->rreq_i_2_bits_address = (i + 2) << 3;
    dut->rreq_i_3_valid = 1;
    dut->rreq_i_3_bits_address = (i + 3) << 3;
    i++;
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VFakeURAM;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VFakeURAM.fst");
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
        if (stopFlag != 0)
        {
            if ((--stopFlag) == 1)
                break;
        }
    }
    if (!stopFlag)
    {
        printf("\033[42;32mVerilator: FakeURAM Test PASS!\033[0m\n");
    }
    else
    {
        printf("\033[41;32mVerilator: FakeURAM Test FAILED!\033[0m\n");
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
